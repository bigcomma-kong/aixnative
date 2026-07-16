package com.aixnative.ai.service

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient

/**
 * Pexels 무료 스톡사진 엔진([AbstractStockImageEngine]) - 키워드로 세로 사진 검색, key 는 Authorization 헤더.
 * 우선순위 Gemini(10) > Pixabay(20) > Pexels(30) > 편집형 폴백. 키 미설정/무결과/실패 시 null → 편집형 타이포 폴백.
 * AiProvider 미구현(텍스트 라우터 격리).
 */
@Component
@Order(30)
class PexelsStockClient(
    @Qualifier("imageGenRestClient") rest: RestClient,
    private val props: PexelsProperties,
    private val objectMapper: ObjectMapper,
) : AbstractStockImageEngine(rest) {

    override val name: String = "Pexels"

    override fun isConfigured(): Boolean = props.api.key.isNotBlank()

    /** GET /search?query=..&orientation=portrait (Authorization: key) → photos[0].src.large(없으면 portrait). */
    override fun searchPhotoUrl(query: String): String? {
        val uri = "${props.api.url}/search?query=${enc(query)}&orientation=portrait&per_page=5"
        val json = rest.get()
            .uri(uri)
            .header("Authorization", props.api.key)
            .retrieve()
            .body(String::class.java)
            ?: return null
        val src = objectMapper.readTree(json).path("photos").firstOrNull()?.path("src") ?: return null
        // large(~940px) 우선 = 렌더 속도(작은 data URI). portrait(800x1200)는 폴백.
        return src.path("large").asText("").ifBlank { src.path("portrait").asText("") }.ifBlank { null }
    }
}
