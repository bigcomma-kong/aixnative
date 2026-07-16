package com.aixnative.ai.service

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient

/**
 * Pixabay 무료 스톡사진 엔진([AbstractStockImageEngine]) - 키워드로 세로 사진 검색, key 는 쿼리 파라미터.
 * 발급이 수월(계정 페이지 노출)해 스톡 우선. 우선순위 Gemini(10) > Pixabay(20) > Pexels(30) > 편집형 폴백.
 * 키 미설정/무결과/실패 시 null → 편집형 타이포 폴백. AiProvider 미구현(텍스트 라우터 격리).
 */
@Component
@Order(20)
class PixabayStockClient(
    @Qualifier("imageGenRestClient") rest: RestClient,
    private val props: PixabayProperties,
    private val objectMapper: ObjectMapper,
) : AbstractStockImageEngine(rest) {

    override val name: String = "Pixabay"

    override fun isConfigured(): Boolean = props.api.key.isNotBlank()

    /**
     * GET /?key=..&q=..&orientation=vertical → hits[0].webformatURL(최대 640px, 없으면 largeImageURL).
     * webformat 우선 = 컨테이너 렌더 속도(작은 data URI). 배경+자막 오버레이라 640px 로 충분.
     */
    override fun searchPhotoUrl(query: String): String? {
        val uri = "${props.api.url}/?key=${props.api.key}" +
            "&q=${enc(query)}&image_type=photo&orientation=vertical&per_page=5&safesearch=true"
        val json = rest.get().uri(uri).retrieve().body(String::class.java) ?: return null
        val hit = objectMapper.readTree(json).path("hits").firstOrNull() ?: return null
        return hit.path("webformatURL").asText("").ifBlank { hit.path("largeImageURL").asText("") }.ifBlank { null }
    }
}
