package com.aixnative.integration.marketdata.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.util.UriComponentsBuilder
import java.time.LocalDate

/**
 * V-World 국토정보 — 용도지역(req/data, 표준 인증키로 동작) + 개별공시지가(ned/data, NED 별도 등록 필요).
 * 키 미설정/조회 실패 시 null(graceful).
 *
 * 용도지역: 표준 V-World 인증키의 `req/data` GetFeature(LT_C_UQ111) 로 좌표 기반 조회 → 활용 API 신청만으로 동작.
 * 공시지가: `ned/data`(NED 국토정보)는 별도 플랫폼 등록이 필요해 표준 키로는 INCORRECT_KEY.
 *           [MarketDataProperties.vworldNedEnabled] 가 true 일 때만 호출(미설정 시 호출 자체를 생략).
 */
@Component
class VWorldClient(
    private val props: MarketDataProperties,
    @Qualifier("marketDataRestClient") private val rest: RestClient,
    private val mapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    data class Point(val x: String, val y: String)

    data class LandPrice(val year: String, val pricePerSqm: String, val landArea: String)

    /** 필지주소 → V-World 지오코더(req/address) 좌표(EPSG:4326). 키 미설정/미해석 시 null. */
    fun geocodeParcel(address: String?): Point? {
        if (props.vworldKey.isBlank() || address.isNullOrBlank()) return null
        return try {
            val uri = UriComponentsBuilder
                .fromHttpUrl("https://api.vworld.kr/req/address")
                .queryParam("service", "address")
                .queryParam("request", "getcoord")
                .queryParam("version", "2.0")
                .queryParam("crs", "EPSG:4326")
                .queryParam("type", "parcel")
                .queryParam("address", address)
                .queryParam("format", "json")
                .queryParam("key", props.vworldKey)
                .withDomain()
                .build(false).encode().toUri()
            val body = rest.get().uri(uri).retrieve().body(String::class.java) ?: return null
            val root = mapper.readTree(body)
            if (root.path("response").path("status").asText("") != "OK") return null
            val point = root.path("response").path("result").path("point")
            val x = point.path("x").asText("")
            val y = point.path("y").asText("")
            if (x.isBlank() || y.isBlank()) null else Point(x, y)
        } catch (e: Exception) {
            log.warn("[VWorld] 지오코딩 실패: {}", e.message)
            null
        }
    }

    /** 용도지역명(예: 일반상업지역). req/data GetFeature(LT_C_UQ111) 좌표 조회. 없으면 null. */
    fun landUseByPoint(point: Point): String? {
        if (props.vworldKey.isBlank()) return null
        return try {
            val uri = UriComponentsBuilder
                .fromHttpUrl("https://api.vworld.kr/req/data")
                .queryParam("service", "data")
                .queryParam("request", "GetFeature")
                .queryParam("version", "2.0")
                .queryParam("format", "json")
                .queryParam("size", "5")
                .queryParam("page", "1")
                .queryParam("geometry", "false")
                .queryParam("attribute", "true")
                .queryParam("crs", "EPSG:4326")
                .queryParam("data", ZONING_LAYER)
                .queryParam("key", props.vworldKey)
                .queryParam("geomFilter", "POINT(${point.x} ${point.y})")
                .withDomain()
                .build(false).encode().toUri()
            val body = rest.get().uri(uri).retrieve().body(String::class.java) ?: return null
            val root = mapper.readTree(body)
            if (root.path("response").path("status").asText("") != "OK") return null
            root.path("response").path("result").path("featureCollection").path("features")
                .mapNotNull { it.path("properties").path("uname").asText("").takeIf { n -> n.isNotBlank() } }
                .firstOrNull()
        } catch (e: Exception) {
            log.warn("[VWorld] 용도지역 실패: {}", e.message)
            null
        }
    }

    /**
     * 최신 개별공시지가(원/㎡). 당해→전년 순. NED 미등록(기본)이면 호출하지 않고 null.
     * NED 등록 완료 후 `VWORLD_NED_ENABLED=true` 로 켜면 동작(코드 변경 불필요).
     */
    fun landPrice(pnu: String): LandPrice? {
        if (!props.vworldNedEnabled || props.vworldKey.isBlank() || pnu.length != 19) return null
        val thisYear = LocalDate.now().year
        for (year in intArrayOf(thisYear, thisYear - 1)) {
            try {
                val uri = UriComponentsBuilder
                    .fromHttpUrl("https://api.vworld.kr/ned/data/getIndvdLandPriceAttr")
                    .queryParam("key", props.vworldKey)
                    .queryParam("pnu", pnu)
                    .queryParam("stdrYear", year.toString())
                    .queryParam("format", "json")
                    .queryParam("numOfRows", "1")
                    .withDomain()
                    .build(false).encode().toUri()
                val body = rest.get().uri(uri).retrieve().body(String::class.java) ?: continue
                val item = findNedItems(mapper.readTree(body), "indvdLandPrices").firstOrNull() ?: continue
                val price = item.path("pblntfPclnd").asText("")
                if (price.isNotBlank()) {
                    return LandPrice(item.path("stdrYear").asText(year.toString()), price, item.path("lndpclAr").asText(""))
                }
            } catch (e: Exception) {
                log.debug("[VWorld] {}년 공시지가 실패: {}", year, e.message)
            }
        }
        return null
    }

    /** 등록 도메인 제한 통과용 — 서버 호출엔 Referer 가 없으므로 domain 파라미터로 보낸다. */
    private fun UriComponentsBuilder.withDomain(): UriComponentsBuilder =
        if (props.vworldDomain.isNotBlank()) queryParam("domain", props.vworldDomain) else this

    /** V-World ned 응답 envelope — `{key}.field` 우선, 그 외 표준 위치 폴백. */
    private fun findNedItems(root: JsonNode, key: String): List<JsonNode> {
        val field = root.path(key).path("field")
        if (!field.isMissingNode) return if (field.isArray) field.toList() else listOf(field)
        val std = root.path("response").path("body").path("items").path("item")
        if (!std.isMissingNode) return if (std.isArray) std.toList() else listOf(std)
        return emptyList()
    }

    private companion object {
        /** 용도지역(국토교통부) 2D 레이어 코드. */
        const val ZONING_LAYER = "LT_C_UQ111"
    }
}
