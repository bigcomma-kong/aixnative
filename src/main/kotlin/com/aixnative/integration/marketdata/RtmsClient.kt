package com.aixnative.integration.marketdata

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.client.HttpStatusCodeException
import org.springframework.web.util.UriComponentsBuilder
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * 국토부 RTMS 상업업무용 실거래가 — 법정동코드(LAWD_CD)별 최근 거래 comps.
 * 키 미설정/거래 없음 시 빈 리스트(graceful degrade). 403 이면 더 이상 월별 호출 중단.
 * (MASTERN BuildingSearchService.getTransactionHistory 이식 — 키는 신규 발급, 복사 금지.)
 */
@Component
class RtmsClient(
    private val props: MarketDataProperties,
    @Qualifier("marketDataRestClient") private val rest: RestClient,
    private val mapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** 한 건의 상업업무용 거래 — dealYmd(yyyy.MM)·금액(만원 원문)·전용면적㎡·층·준공연도. */
    data class Trade(
        val dealYmd: String,
        val amountManwon: String,
        val areaSqm: String,
        val floor: String,
        val buildYear: String,
    )

    /** 한 건의 토지 매매 거래 — dealYmd·금액(만원)·필지면적㎡·지목·용도지역. */
    data class LandTrade(
        val dealYmd: String,
        val amountManwon: String,
        val areaSqm: String,
        val landType: String,
        val landUse: String,
    )

    /** 최근 [years]년 상업업무용 거래(최대 10건). 키 미설정 시 빈 리스트. */
    fun commercialTransactions(lawdCd: String, years: Int): List<Trade> =
        fetchMonthly(NRG_TRADE, lawdCd, years) { item, ymd ->
            val year = item.path("dealYear").asText(ymd.substring(0, 4))
            val mon = item.path("dealMonth").asText(ymd.substring(4))
            Trade(
                dealYmd = "$year.$mon",
                amountManwon = item.path("dealAmount").asText("-"),
                areaSqm = item.path("excluUseAr").asText(item.path("buildingAr").asText("-")),
                floor = item.path("floor").asText(item.path("floorNo").asText("-")),
                buildYear = item.path("buildYear").asText("-"),
            )
        }

    /** 최근 [years]년 토지 매매 거래(최대 10건). 키 미설정 시 빈 리스트. */
    fun landTransactions(lawdCd: String, years: Int): List<LandTrade> =
        fetchMonthly(LAND_TRADE, lawdCd, years) { item, ymd ->
            val year = item.path("dealYear").asText(ymd.substring(0, 4))
            val mon = item.path("dealMonth").asText(ymd.substring(4))
            LandTrade(
                dealYmd = "$year.$mon",
                amountManwon = item.path("dealAmount").asText("-"),
                areaSqm = item.path("dealArea").asText(item.path("landArea").asText("-")),
                landType = item.path("jimok").asText(item.path("landClassification").asText("-")),
                landUse = item.path("landUse").asText("-"),
            )
        }

    /**
     * RTMS 월별 역순 조회 공통 루프 — 최대 10건 수집, 403 이면 중단(graceful).
     * [endpoint] 만 다르고 페이로드 매핑은 [parse] 로 위임.
     */
    private fun <T> fetchMonthly(endpoint: String, lawdCd: String, years: Int, parse: (JsonNode, String) -> T): List<T> {
        if (props.dataGoKrKey.isBlank() || lawdCd.isBlank()) return emptyList()
        val results = ArrayList<T>()
        val currentYear = LocalDate.now().year
        var month = 0
        while (month < years * 12 && results.size < 10) {
            val date = LocalDate.now().minusMonths(month.toLong())
            month++
            if (date.year < currentYear - years) break
            val dealYmd = date.format(YM)
            try {
                val uri = UriComponentsBuilder
                    .fromHttpUrl(endpoint)
                    .queryParam("serviceKey", props.dataGoKrKey)
                    .queryParam("LAWD_CD", lawdCd)
                    .queryParam("DEAL_YMD", dealYmd)
                    .queryParam("_type", "json")
                    .queryParam("numOfRows", "10")
                    .build(false).encode().toUri()
                val body = rest.get().uri(uri).retrieve().body(String::class.java) ?: continue
                val items = mapper.readTree(body).path("response").path("body").path("items").path("item")
                when {
                    items.isArray -> for (item in items) {
                        if (results.size >= 10) break
                        results.add(parse(item, dealYmd))
                    }
                    items.isObject && !items.isMissingNode -> results.add(parse(items, dealYmd))
                }
            } catch (e: HttpStatusCodeException) {
                if (e.statusCode.value() == 403) {
                    log.warn("[RTMS] 403 (lawdCd={}) — 월별 호출 중단", lawdCd)
                    break
                }
                log.debug("[RTMS] {} 건너뜀: {}", dealYmd, e.message)
            } catch (e: Exception) {
                log.debug("[RTMS] {} 건너뜀: {}", dealYmd, e.message)
            }
        }
        return results
    }

    private companion object {
        const val NRG_TRADE = "https://apis.data.go.kr/1613000/RTMSDataSvcNrgTrade/getRTMSDataSvcNrgTrade"
        const val LAND_TRADE = "https://apis.data.go.kr/1613000/RTMSDataSvcLandTrade/getRTMSDataSvcLandTrade"
        val YM: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMM")
    }
}
