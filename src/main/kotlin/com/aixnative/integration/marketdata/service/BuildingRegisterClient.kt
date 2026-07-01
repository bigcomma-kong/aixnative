package com.aixnative.integration.marketdata.service

import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.util.UriComponentsBuilder

/**
 * 국토부 건축물대장 표제부(BldRgstHubService.getBrTitleInfo) — 필지 → 건물 스펙(연면적·주용도·준공연도).
 * juso 로 해석한 법정동코드(10)+번+지 로 조회(DATA_GO_KR 키 재사용). 미설정/미해석 시 null(graceful).
 * 라이브 검증: 역삼동 736-1 → ARC PLACE / 업무시설 / 연면적 62,725㎡ / 준공 1998.
 */
@Component
class BuildingRegisterClient(
    private val props: MarketDataProperties,
    @Qualifier("marketDataRestClient") private val rest: RestClient,
    private val mapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** 건축물대장 표제부 요약. 없거나 실패 시 null. */
    data class BuildingInfo(
        val name: String?,        // 건물명(bldNm)
        val mainPurpose: String?, // 주용도(mainPurpsCdNm, 예: 업무시설)
        val totAreaSqm: String?,  // 연면적 ㎡(totArea)
        val useAprYear: String?,  // 준공연도 YYYY(useAprDay 앞 4자리)
    )

    fun titleInfo(parcel: JusoClient.Parcel): BuildingInfo? {
        if (props.dataGoKrKey.isBlank() || parcel.admCd.length != 10) return null
        return try {
            val sigungu = parcel.admCd.substring(0, 5)
            val bjdong = parcel.admCd.substring(5, 10)
            // juso landGbn: "2"=산 → 건축물대장 platGbCd "1", 그 외 "0"(대지)
            val platGb = if (parcel.landGbn == "2") "1" else "0"
            val uri = UriComponentsBuilder
                .fromHttpUrl(TITLE_INFO)
                .queryParam("serviceKey", props.dataGoKrKey)
                .queryParam("sigunguCd", sigungu)
                .queryParam("bjdongCd", bjdong)
                .queryParam("platGbCd", platGb)
                .queryParam("bun", pad4(parcel.bun))
                .queryParam("ji", pad4(parcel.ji))
                .queryParam("_type", "json")
                .queryParam("numOfRows", "10")
                .build(false).encode().toUri()
            val body = rest.get().uri(uri).retrieve().body(String::class.java) ?: return null
            val items = mapper.readTree(body).path("response").path("body").path("items").path("item")
            // 연면적 가장 큰 동(주건물)을 대표로.
            val item = when {
                items.isArray && items.size() > 0 ->
                    items.maxByOrNull { it.path("totArea").asText("0").toDoubleOrNull() ?: 0.0 }
                items.isObject && !items.isMissingNode -> items
                else -> null
            } ?: return null
            BuildingInfo(
                name = item.path("bldNm").asText(null)?.takeIf { it.isNotBlank() },
                mainPurpose = item.path("mainPurpsCdNm").asText(null)?.takeIf { it.isNotBlank() },
                totAreaSqm = item.path("totArea").asText(null)?.takeIf { it.isNotBlank() && it != "0" },
                useAprYear = item.path("useAprDay").asText(null)?.takeIf { it.length >= 4 }?.substring(0, 4),
            )
        } catch (e: Exception) {
            log.warn("[building] 건축물대장 조회 실패: {}", e.message)
            null
        }
    }

    private fun pad4(v: String): String = v.trim().toIntOrNull()?.let { "%04d".format(it) } ?: "0000"

    private companion object {
        const val TITLE_INFO = "https://apis.data.go.kr/1613000/BldRgstHubService/getBrTitleInfo"
    }
}
