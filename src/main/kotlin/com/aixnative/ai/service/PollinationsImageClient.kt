package com.aixnative.ai.service

import com.aixnative.social.service.ImageEngine
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientResponseException
import java.net.URLEncoder
import java.util.Base64
import kotlin.math.abs

/**
 * Pollinations 무료 AI 이미지 생성 엔진([ImageEngine]) - **키 불필요·건당 0원**.
 * 프롬프트를 URL path 로 넘기면 서버가 그림을 생성해 JPEG 로 반환(~60~120KB, 이미 작아 렌더 경량).
 *
 * Gemini(gemini-2.5-flash-image)는 API 무료 할당량이 0(빌링 필요)이라, 진짜 무료 생성형은 이쪽이 기본.
 * 우선순위 Pollinations(5) > Gemini(10, 빌링 시) > Pixabay(20) > Pexels(30) > 편집형 타이포 폴백.
 *
 * 생성 지연(수 초)은 배경 compose 작업에서 흡수하려고 [generate](compose 시 다운로드→base64)만 구현하고
 * [imageUrl]은 null 로 둔다(렌더 시점 생성 대기 회피). 실패/느림 시 호출부가 다음 엔진(스톡)으로 장면별 폴백.
 * 텍스트 라우터(AiServiceManager)에 안 잡히도록 AiProvider 미구현.
 */
@Component
@Order(5)
class PollinationsImageClient(
    @Qualifier("pollinationsRestClient") private val rest: RestClient,
    private val props: PollinationsProperties,
) : ImageEngine {

    private val log = LoggerFactory.getLogger(javaClass)

    override val name: String = "Pollinations"

    override fun isConfigured(): Boolean = props.enabled

    /**
     * 프롬프트 → AI 이미지(base64). 무료(익명) 티어는 지연 편차·버스트 429 가 잦아
     * 429/5xx/타임아웃은 백오프 후 [MAX_RETRIES]회 재시도(그 외 4xx 는 즉시 중단). 전부 실패 시 null(다음 엔진 폴백).
     */
    override fun generate(prompt: String, aspectRatio: String): String? {
        if (!isConfigured()) return null
        val (w, h) = dims(aspectRatio)
        val styled = "$prompt, cinematic photography, high detail, no text, no watermark, no real person's face"
        val seed = abs(prompt.hashCode()) % 1_000_000
        val uri = "${props.url}/${enc(styled)}" +
            "?width=$w&height=$h&model=${props.model}&nologo=true&seed=$seed"

        var lastErr: String? = null
        for (attempt in 0..MAX_RETRIES) {
            try {
                val bytes = rest.get().uri(uri).retrieve().body(ByteArray::class.java)
                if (bytes != null && isImage(bytes)) return Base64.getEncoder().encodeToString(bytes)
                lastErr = "이미지 아님/무결과(size=${bytes?.size ?: 0})"
            } catch (e: RestClientResponseException) {
                lastErr = "HTTP ${e.statusCode}"
                val retryable = e.statusCode.value() == 429 || e.statusCode.is5xxServerError
                if (!retryable) { log.warn("[Pollinations] 생성 {} - 재시도 안 함", lastErr); return null }
            } catch (e: Exception) {
                lastErr = e.message // 타임아웃·연결 등
            }
            if (attempt < MAX_RETRIES) Thread.sleep(RETRY_BACKOFF_MS * (attempt + 1))
        }
        log.warn("[Pollinations] 생성 실패({}회 시도): {}", MAX_RETRIES + 1, lastErr)
        return null
    }

    /** "4:5" 등 종횡비 → 너비 [BASE] 기준 픽셀. 세로는 [MAX_SIDE]로 상한(과대 이미지·지연 방지). */
    private fun dims(aspectRatio: String): Pair<Int, Int> {
        val parts = aspectRatio.split(":").mapNotNull { it.trim().toIntOrNull() }
        if (parts.size != 2 || parts[0] <= 0 || parts[1] <= 0) return BASE to (BASE * 5 / 4)
        val h = (BASE.toLong() * parts[1] / parts[0]).toInt().coerceIn(BASE / 2, MAX_SIDE)
        return BASE to h
    }

    /** 앞 매직바이트로 JPEG/PNG 인지 확인(에러 HTML·빈 응답을 이미지로 오인 방지). */
    private fun isImage(bytes: ByteArray): Boolean {
        if (bytes.size < MIN_BYTES || bytes.size > MAX_BYTES) return false
        val jpeg = bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte()
        val png = bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte()
        return jpeg || png
    }

    private fun enc(s: String): String = URLEncoder.encode(s, Charsets.UTF_8).replace("+", "%20")

    private companion object {
        const val BASE = 1024
        const val MAX_SIDE = 1536
        const val MIN_BYTES = 1024
        const val MAX_BYTES = 8 * 1024 * 1024
        const val MAX_RETRIES = 2
        const val RETRY_BACKOFF_MS = 1500L
    }
}
