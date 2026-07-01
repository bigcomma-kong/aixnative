package com.aixnative.integration.marketdata.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * 한국은행 ECOS — 최신 기준금리·국고채(3년·10년) 실측 앵커.
 * 키 미설정/조회 실패 시 null → 분석은 기존 AI 추정 흐름 유지(graceful degrade).
 * (MASTERN MarketDataService.latestRates 이식 — 키 값은 신규 발급, 복사 금지.)
 */
@Component
class EcosClient(
    private val props: MarketDataProperties,
    @Qualifier("marketDataRestClient") private val rest: RestClient,
    private val mapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    data class Rates(val baseRate: Double?, val gov3y: Double?, val gov10y: Double?, val asOf: String)

    /** 최신 기준금리·국고채. 값이 하나도 없으면 null. */
    fun latestRates(): Rates? {
        if (!props.ecosEnabled) return null
        return try {
            val today = LocalDate.now()
            val from = today.minusMonths(3)
            val endM = today.format(YM)
            val startM = from.format(YM)
            val endD = today.format(YMD)
            val startD = from.withDayOfMonth(1).format(YMD)

            val base = lastValue("722Y001", "0101000", "M", startM, endM)   // 기준금리
            val g3 = lastValue("817Y002", "010200000", "D", startD, endD)  // 국고채 3년
            val g10 = lastValue("817Y002", "010210000", "D", startD, endD) // 국고채 10년
            if (base == null && g3 == null && g10 == null) return null
            Rates(base, g3, g10, today.format(AS_OF))
        } catch (e: Exception) {
            log.warn("[ECOS] latestRates 실패: {}", e.message)
            null
        }
    }

    private fun lastValue(stat: String, item: String, freq: String, start: String, end: String): Double? {
        return try {
            val url = "https://ecos.bok.or.kr/api/StatisticSearch/${props.ecosKey}/json/kr/1/2000/$stat/$freq/$start/$end/$item"
            val body = rest.get().uri(url).retrieve().body(String::class.java) ?: return null
            val rows: JsonNode = mapper.readTree(body).path("StatisticSearch").path("row")
            if (rows.isArray && rows.size() > 0) {
                rows[rows.size() - 1].path("DATA_VALUE").asText("").ifBlank { null }?.toDoubleOrNull()
            } else null
        } catch (e: Exception) {
            log.warn("[ECOS] {}/{} 조회 실패: {}", stat, item, e.message)
            null
        }
    }

    private companion object {
        val YM: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMM")
        val YMD: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd")
        val AS_OF: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy.MM")
    }
}
