package com.aixnative.ai.service

import com.aixnative.social.service.ImageEngine
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.core.annotation.Order
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientResponseException
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Base64
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam

/**
 * Google Gemini 이미지 생성 엔진([ImageEngine]) - generativelanguage generateContent.
 * `MistralClient` 구조 미러링(RestClient + graceful). 배경 스토리 이미지 생성 전용,
 * 텍스트 라우터(AiServiceManager)에 안 잡히도록 AiProvider 미구현.
 * 키 미설정/실패 시 null → 호출부([com.aixnative.social.service.StoryImageComposer])가 타이포 폴백.
 * (키는 신규 발급, MASTERN 값 복사 금지.)
 *
 * [StoryImageComposer] 는 설정된 엔진 중 [Order] 가 앞선 것을 쓴다 - Gemini(10)가 Pexels 스톡(20)보다
 * 우선(맞춤 그림 > 일반 스톡). 둘 다 미설정이면 편집형 타이포 폴백.
 */
@Component
@Order(10)
class GeminiImageClient(
    @Qualifier("imageGenRestClient") private val rest: RestClient,
    private val props: GeminiProperties,
    private val objectMapper: ObjectMapper,
) : ImageEngine {

    private val log = LoggerFactory.getLogger(javaClass)

    override val name: String = "Gemini"

    override fun isConfigured(): Boolean = props.api.key.isNotBlank()

    override fun generate(prompt: String, aspectRatio: String): String? {
        if (!isConfigured()) return null
        val fullPrompt = "$prompt. Vertical $aspectRatio composition, no text, no watermark, no real person's face."
        val body = mapOf(
            "contents" to listOf(
                mapOf("parts" to listOf(mapOf("text" to fullPrompt))),
            ),
            "generationConfig" to mapOf("responseModalities" to listOf("IMAGE")),
        )
        return try {
            val uri = "${props.api.url}/models/${props.api.model}:generateContent?key=${props.api.key}"
            val response = rest.post()
                .uri(uri)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(String::class.java)
                ?: return null
            extractImage(response)?.let { downscaleToJpeg(it) }
        } catch (e: RestClientResponseException) {
            log.warn("[Gemini] 이미지 생성 HTTP {} - {}", e.statusCode, e.responseBodyAsString.take(300))
            null
        } catch (e: Exception) {
            log.warn("[Gemini] 이미지 생성 실패: {}", e.message)
            null
        }
    }

    /** candidates[0].content.parts[].inlineData.data (base64). */
    private fun extractImage(responseBody: String): String? {
        val parts: JsonNode = objectMapper.readTree(responseBody)
            .path("candidates").firstOrNull()
            ?.path("content")?.path("parts")
            ?: return null
        if (!parts.isArray) return null
        for (part in parts) {
            val data = part.path("inlineData").path("data").asText("")
            if (data.isNotBlank()) return data
        }
        log.warn("[Gemini] 응답에 이미지 파트 없음")
        return null
    }

    /**
     * 생성 PNG(base64, ~1MB) → 최대 [MAX_SIDE]px JPEG(base64, ~100KB)로 축소.
     * 큰 data URI 4장을 satori/resvg 로 임베드 시 Cloud Run 1 vCPU 렌더가 수 분까지 느려지던 문제 방지
     * (배경 위 자막 오버레이라 소프트해도 무방 - 속도 우선). 디코드/인코드 실패 시 원본 반환(graceful).
     */
    private fun downscaleToJpeg(b64: String): String = try {
        val src = ImageIO.read(ByteArrayInputStream(Base64.getDecoder().decode(b64)))
        if (src == null) {
            b64
        } else {
            val scale = MAX_SIDE.toDouble() / maxOf(src.width, src.height)
            val tw = if (scale >= 1.0) src.width else (src.width * scale).toInt().coerceAtLeast(1)
            val th = if (scale >= 1.0) src.height else (src.height * scale).toInt().coerceAtLeast(1)
            val dst = BufferedImage(tw, th, BufferedImage.TYPE_INT_RGB)
            dst.createGraphics().apply {
                setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
                drawImage(src, 0, 0, tw, th, null)
                dispose()
            }
            val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
            val out = ByteArrayOutputStream()
            ImageIO.createImageOutputStream(out).use { ios ->
                writer.output = ios
                val param = writer.defaultWriteParam.apply {
                    if (canWriteCompressed()) {
                        compressionMode = ImageWriteParam.MODE_EXPLICIT
                        compressionQuality = JPEG_QUALITY
                    }
                }
                writer.write(null, IIOImage(dst, null, null), param)
            }
            writer.dispose()
            Base64.getEncoder().encodeToString(out.toByteArray())
        }
    } catch (e: Exception) {
        log.warn("[Gemini] 이미지 축소 실패(원본 사용): {}", e.message)
        b64
    }

    private companion object {
        const val MAX_SIDE = 1024
        const val JPEG_QUALITY = 0.82f
    }
}
