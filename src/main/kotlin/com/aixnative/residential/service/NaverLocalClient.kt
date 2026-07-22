package com.aixnative.residential.service

import com.aixnative.residential.domain.NearbyPlace
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.util.UriComponentsBuilder

/**
 * 네이버 지역검색 API(openapi.naver.com/v1/search/local) 기반 주변 시설(POI).
 * 카카오맵(비즈앱 필요) 대체 - 네이버 앱의 client-id/secret(그 앱에 "검색" API 추가 필요, 무료).
 * 키워드 검색이라 반경/거리 개념은 없음("지역명 + 카테고리"로 최대 5곳). 미설정/실패 시 graceful 빈 리스트.
 */
@Component
class NaverLocalClient(
    private val props: ResidentialProperties,
    @Qualifier("marketDataRestClient") private val rest: RestClient,
    private val mapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun isConfigured(): Boolean = props.naverEnabled

    /** "{area} {term}"(예 "역삼동 지하철역")으로 검색해 최대 [display]곳. 실패/무결과 시 빈 리스트. */
    fun search(area: String, term: String, display: Int = 5): List<NearbyPlace> {
        if (!isConfigured() || area.isBlank()) return emptyList()
        return runCatching {
            val uri = UriComponentsBuilder.fromHttpUrl(LOCAL_URL)
                .queryParam("query", "$area $term")
                .queryParam("display", display.coerceIn(1, 5))
                .queryParam("sort", "random")
                .build(false).encode().toUri()
            val body = rest.get().uri(uri)
                .header("X-Naver-Client-Id", props.naverClientId)
                .header("X-Naver-Client-Secret", props.naverClientSecret)
                .retrieve().body(String::class.java) ?: return emptyList()
            mapper.readTree(body).path("items").map { it ->
                NearbyPlace(
                    name = stripTags(it.path("title").asText("-")),
                    category = it.path("category").asText("").substringAfterLast(">").trim()
                        .ifBlank { term },
                    distanceM = null, // 지역검색은 반경/거리 미제공
                    roadAddress = it.path("roadAddress").asText("").ifBlank { it.path("address").asText("").ifBlank { null } },
                )
            }
        }.getOrElse { log.debug("[naver] 지역검색({}) 실패: {}", term, it.message); emptyList() }
    }

    private fun stripTags(s: String): String = s.replace(TAG, "").trim()

    private companion object {
        const val LOCAL_URL = "https://openapi.naver.com/v1/search/local.json"
        val TAG = Regex("</?b>")
    }
}
