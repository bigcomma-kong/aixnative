package com.aixnative.integration.marketdata

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient

/**
 * 한국부동산원 R-ONE 상업용부동산 임대동향 — 주소 권역 매칭 후 공실률·임대료·소득수익률(분기).
 * 오피스·리테일만 제공(물류·호텔 미제공). 키 미설정·권역 미매칭·실패 시 빈 문자열(graceful degrade).
 * (MASTERN MarketDataService 의 R-ONE 로직 이식 — 통계표 ID·권역 매칭 그대로, 키는 신규 발급.)
 */
@Component
class RebRoneClient(
    private val props: MarketDataProperties,
    @Qualifier("marketDataRestClient") private val rest: RestClient,
    private val mapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** R-ONE 임대시장 실측 facts 한 줄 — 공실률·임대료·소득수익률. 없으면 빈 문자열. */
    fun rentYieldFactLine(address: String?, assetType: String?): String {
        if (address.isNullOrBlank() || !props.rebEnabled) return ""
        val asset = roneAsset(assetType) ?: return ""
        val tbl = RONE_TBL[asset] ?: return ""
        return try {
            val addr = address.replace("\\s".toRegex(), "")
            val vac = roneRegionValue(tbl[0], addr, null) ?: return "" // 공실률 — 주소로 권역 결정
            val region = vac.getValue("region")
            val rent = roneRegionValue(tbl[1], addr, region) // 임대료 (같은 권역)
            val cap = roneRegionValue(tbl[2], addr, region)  // 소득수익률 (같은 권역)
            val label = if (asset == "retail") "중대형상가" else "오피스"
            buildString {
                append("\n[실측 임대시장 — 출처 한국부동산원 R-ONE] ")
                append(region).append("(").append(label).append("): ")
                append("공실률 ").append(fmt(vac["val"])).append("%(").append(vac["period"]).append(")")
                rent?.let { append(" · 임대료 ").append(fmt(it["val"])).append(it["ui"]).append("(").append(it["period"]).append(")") }
                cap?.let { append(" · 소득수익률 ").append(fmt(it["val"])).append("%/분기(").append(it["period"]).append(")") }
                append(" — 위는 한국부동산원 공식 통계이므로 공실률·임대료·수익률 작성 시 추정 대신 인용하고 신뢰도를 높이세요.")
            }
        } catch (e: Exception) {
            log.warn("[R-ONE] rentYieldFactLine 실패: {}", e.message)
            ""
        }
    }

    /** 최신분기에서 region 지정 시 그 권역 / 미지정 시 주소 매칭으로 권역 결정. {region,val,ui,period} 또는 null. */
    private fun roneRegionValue(statblId: String, addrNoSpace: String, region: String?): Map<String, String>? {
        return try {
            val rows = roneRows(statblId) ?: return null
            if (!rows.isArray || rows.isEmpty) return null
            // 최신 분기 식별
            var period = ""
            for (r in rows) {
                val w = r.path("WRTTIME_IDTFR_ID").asText("")
                if (w > period) period = w
            }
            var best: JsonNode? = null
            var bestLen = 0
            for (r in rows) {
                if (period != r.path("WRTTIME_IDTFR_ID").asText("")) continue
                val cls = r.path("CLS_NM").asText("").replace("\\s".toRegex(), "")
                if (cls.length < 2 || cls == "전국") continue
                if (region != null) {
                    if (region == r.path("CLS_NM").asText("")) { best = r; break }
                } else if (addrNoSpace.contains(cls) && cls.length > bestLen) {
                    best = r; bestLen = cls.length
                }
            }
            val node = best ?: return null
            mapOf(
                "region" to node.path("CLS_NM").asText(""),
                "val" to node.path("DTA_VAL").asText(""),
                "ui" to node.path("UI_NM").asText(""),
                "period" to node.path("WRTTIME_DESC").asText(period),
            )
        } catch (e: Exception) {
            log.warn("[R-ONE] {} 조회 실패: {}", statblId, e.message)
            null
        }
    }

    private fun roneRows(statblId: String): JsonNode? {
        val url = "$RONE_BASE?KEY=${props.rebKey}&Type=json&STATBL_ID=$statblId&DTACYCLE_CD=QY&pIndex=1&pSize=700"
        val body = rest.get().uri(url).retrieve().body(String::class.java) ?: return null
        val root = mapper.readTree(body)
        for (b in root.path("SttsApiTblData")) {
            if (b.has("row")) return b.path("row")
        }
        return null
    }

    /** assetType → R-ONE 자산구분(office/retail). 물류·호텔은 미제공 → null. null 입력은 office 가정. */
    private fun roneAsset(at: String?): String? {
        if (at == null) return "office"
        val a = at.lowercase()
        return when {
            a.contains("retail") || a.contains("리테일") || a.contains("상가") -> "retail"
            a.contains("office") || a.contains("오피스") -> "office"
            else -> null
        }
    }

    private fun fmt(v: Any?): String =
        v?.toString()?.toDoubleOrNull()?.let { (Math.round(it * 100) / 100.0).toString() } ?: v.toString()

    private companion object {
        const val RONE_BASE = "https://www.reb.or.kr/r-one/openapi/SttsApiTblData.do"

        // assetType → {공실률, 임대료, 소득수익률} 통계표ID (현행, MASTERN 확인값). 오피스/상가만 제공.
        val RONE_TBL: Map<String, Array<String>> = mapOf(
            "office" to arrayOf("TT244763134428698", "TT249843134237374", "A_2024_00390"),
            "retail" to arrayOf("T249633134845544", "T244363134858603", "A_2024_00391"),
        )
    }
}
