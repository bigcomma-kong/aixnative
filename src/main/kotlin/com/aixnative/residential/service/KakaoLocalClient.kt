package com.aixnative.residential.service

import com.aixnative.residential.domain.GeoPoint
import com.aixnative.residential.domain.NearbyPlace
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.util.UriComponentsBuilder

/**
 * 카카오 로컬 API - 주소 지오코딩 + 카테고리 기반 주변 시설(POI) 검색.
 * 인증: 헤더 `Authorization: KakaoAK {REST키}`. 키 미설정 시 전부 graceful(null/빈 리스트).
 *
 * marketDataRestClient(짧은 타임아웃) 재사용 - 느린 응답은 건너뛰어 리포트 요청을 막지 않는다.
 */
@Component
class KakaoLocalClient(
    private val props: ResidentialProperties,
    @Qualifier("marketDataRestClient") private val rest: RestClient,
    private val mapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** 주소 → 좌표(경위도) + 법정동코드(10). 실패/무결과 시 null. */
    fun geocode(address: String): GeoPoint? {
        if (!props.kakaoEnabled || address.isBlank()) return null
        val doc = runCatching {
            val uri = UriComponentsBuilder.fromHttpUrl(ADDRESS_URL)
                .queryParam("query", address)
                .queryParam("size", 1)
                .build(false).encode().toUri()
            val body = rest.get().uri(uri).header(AUTH, "KakaoAK ${props.kakaoRestKey}")
                .retrieve().body(String::class.java) ?: return null
            mapper.readTree(body).path("documents").firstOrNull()
        }.getOrElse { log.debug("[kakao] geocode 실패: {}", it.message); null } ?: return null

        val lon = doc.path("x").asText("").toDoubleOrNull() ?: return null
        val lat = doc.path("y").asText("").toDoubleOrNull() ?: return null
        val addr = doc.path("address")
        val road = doc.path("road_address")
        val bCode = addr.path("b_code").asText("").ifBlank { road.path("b_code").asText("") }
        val gu = addr.path("region_2depth_name").asText("").ifBlank { road.path("region_2depth_name").asText("") }
        val dong = addr.path("region_3depth_name").asText("").ifBlank { road.path("region_3depth_name").asText("") }
        return GeoPoint(
            longitude = lon,
            latitude = lat,
            bCode = bCode,
            roadAddress = road.path("address_name").asText("").ifBlank { null },
            jibunAddress = addr.path("address_name").asText("").ifBlank { null },
            areaLabel = listOf(gu, dong).filter { it.isNotBlank() }.joinToString(" ").ifBlank { null },
        )
    }

    /** 좌표 반경 내 카테고리 POI(거리순 최대 [size]건). 키 미설정/실패 시 빈 리스트. */
    fun nearby(lon: Double, lat: Double, categoryGroupCode: String, radiusM: Int = 1000, size: Int = 5): List<NearbyPlace> {
        if (!props.kakaoEnabled) return emptyList()
        return runCatching {
            val uri = UriComponentsBuilder.fromHttpUrl(CATEGORY_URL)
                .queryParam("category_group_code", categoryGroupCode)
                .queryParam("x", lon)
                .queryParam("y", lat)
                .queryParam("radius", radiusM.coerceAtMost(MAX_RADIUS))
                .queryParam("sort", "distance")
                .queryParam("size", size.coerceAtMost(15))
                .build(false).encode().toUri()
            val body = rest.get().uri(uri).header(AUTH, "KakaoAK ${props.kakaoRestKey}")
                .retrieve().body(String::class.java) ?: return emptyList()
            mapper.readTree(body).path("documents").map { d ->
                NearbyPlace(
                    name = d.path("place_name").asText("-"),
                    category = d.path("category_group_name").asText("").ifBlank {
                        d.path("category_name").asText("-").substringAfterLast('>').trim()
                    },
                    distanceM = d.path("distance").asText("").toIntOrNull(),
                    roadAddress = d.path("road_address_name").asText("").ifBlank { null },
                )
            }
        }.getOrElse { log.debug("[kakao] nearby({}) 실패: {}", categoryGroupCode, it.message); emptyList() }
    }

    private companion object {
        const val AUTH = "Authorization"
        const val ADDRESS_URL = "https://dapi.kakao.com/v2/local/search/address.json"
        const val CATEGORY_URL = "https://dapi.kakao.com/v2/local/search/category.json"
        const val MAX_RADIUS = 20000
    }
}
