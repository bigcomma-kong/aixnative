package com.aixnative.ai.service

import com.aixnative.social.service.ImageEngine
import org.slf4j.LoggerFactory
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientResponseException
import java.net.URLEncoder
import java.util.Base64

/**
 * 무료 스톡사진 엔진 공통 골격([ImageEngine]) - 프롬프트→키워드→provider 검색→사진 다운로드→base64.
 * provider(Pexels/Pixabay)별로 다른 건 검색 URL·응답 파싱뿐이라 [searchPhotoUrl] 만 구현하면 된다.
 *
 * 생성형이 아니라 실사진 매칭이라 관련성은 완벽하지 않다(키워드 검색 한계). 미설정/무결과/실패 시 null →
 * 호출부([com.aixnative.social.service.StoryImageComposer])가 편집형 타이포 폴백. 배경 운영비(무료 티어).
 */
abstract class AbstractStockImageEngine(
    protected val rest: RestClient,
) : ImageEngine {

    protected val log = LoggerFactory.getLogger(javaClass)

    override fun generate(prompt: String, aspectRatio: String): String? {
        if (!isConfigured()) return null
        val query = toQuery(prompt).ifBlank { return null }
        return try {
            val photoUrl = searchPhotoUrl(query) ?: run {
                log.info("[{}] '{}' 검색 결과 없음", name, query); return null
            }
            downloadBase64(photoUrl)
        } catch (e: RestClientResponseException) {
            log.warn("[{}] 검색 HTTP {} - {}", name, e.statusCode, e.responseBodyAsString.take(200)); null
        } catch (e: Exception) {
            log.warn("[{}] 스톡 조회 실패: {}", name, e.message); null
        }
    }

    /**
     * 스톡 엔진 - 다운로드 없이 검색된 사진 URL 을 반환(렌더러가 fetch). 미설정/무결과/실패 시 null.
     * 이게 우선 경로(큰 base64 미저장·빠른 렌더). [generate] 는 base64 폴백용으로 유지.
     */
    override fun imageUrl(prompt: String, aspectRatio: String): String? {
        if (!isConfigured()) return null
        val query = toQuery(prompt).ifBlank { return null }
        return try {
            searchPhotoUrl(query) ?: run { log.info("[{}] '{}' 검색 결과 없음", name, query); null }
        } catch (e: RestClientResponseException) {
            log.warn("[{}] 검색 HTTP {} - {}", name, e.statusCode, e.responseBodyAsString.take(200)); null
        } catch (e: Exception) {
            log.warn("[{}] 스톡 URL 조회 실패: {}", name, e.message); null
        }
    }

    /** provider별 검색 → 세로형 사진 URL 1개(없으면 null). 예외는 상위 generate/imageUrl 가 처리. */
    protected abstract fun searchPhotoUrl(query: String): String?

    /** 사진 바이트 → 프리픽스 없는 base64(렌더러가 mime 감지). */
    protected fun downloadBase64(url: String): String? {
        val bytes = rest.get().uri(url).retrieve().body(ByteArray::class.java) ?: return null
        if (bytes.isEmpty() || bytes.size > MAX_BYTES) return null
        return Base64.getEncoder().encodeToString(bytes)
    }

    /** 영어 이미지 프롬프트 → 스톡 검색 키워드(상위 명사류 몇 개). 불용어·렌더 지시어 제거. */
    protected fun toQuery(prompt: String): String {
        return prompt.lowercase()
            .replace(Regex("[^a-z0-9\\s]"), " ")
            .split(Regex("\\s+"))
            .filter { it.length > 2 && it !in STOPWORDS }
            .take(5)
            .joinToString(" ")
    }

    protected fun enc(s: String): String = URLEncoder.encode(s, Charsets.UTF_8)

    protected companion object {
        const val MAX_BYTES = 8 * 1024 * 1024
        val STOPWORDS = setOf(
            "the", "and", "with", "for", "his", "her", "their", "this", "that", "over",
            "into", "from", "image", "photo", "prompt", "text", "watermark", "real",
            "person", "people", "face", "logo", "brand", "vertical", "horizontal",
            "composition", "background", "scene", "style", "digital", "art", "illustration",
            "realistic", "detailed", "high", "quality", "shot", "view", "close", "wide",
            "showing", "depicting", "featuring", "wearing", "some", "very", "korean",
            "atmosphere", "concept", "abstract", "modern", "generic",
        )
    }
}
