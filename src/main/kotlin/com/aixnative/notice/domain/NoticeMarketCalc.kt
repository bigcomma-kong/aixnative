package com.aixnative.notice.domain

import com.fasterxml.jackson.databind.JsonNode
import kotlin.math.roundToLong

/**
 * 공고 물건을 주변 실거래(중위 평당가)와 대조한다 - **코드 계산**(AI 산수 금지 원칙).
 *
 * AI 투자판단(NOTICE_MARKET_VIEW) 프롬프트에는 이 결과를 사실로 넣고, 화면에도 AI 서술과 별도로 보여 준다.
 * 실거래 중위값은 상업업무용 RTMS 기준이라 주거·토지 공고에서는 비교 기준으로 약하다 - [MarketComparison.note] 로 밝힌다.
 */
object NoticeMarketCalc {

    /** 비교 기준 실거래 표본 하한 - 이보다 적으면 시세 대비 판단을 내지 않는다(분포가 아니라 우연이다). */
    const val MIN_COMPS = 3

    data class UnitComparison(
        val label: String,
        /** 1차(또는 감정가) 평단가(원/평). */
        val pyeongPriceKrw: Long?,
        /** 시세 대비(%) - 음수면 시세보다 싸다. */
        val vsMarketPct: Double?,
        /** 회차별 최저가가 시세 중위값 아래로 처음 내려오는 회차(1부터). 처음부터 아래면 1, 끝까지 위면 null. */
        val firstRoundBelowMarket: Int?,
        val roundCount: Int,
    )

    data class MarketComparison(
        val marketPyeongKrw: Long?,
        val compCount: Int,
        val units: List<UnitComparison>,
        val note: String,
    )

    /**
     * @param extraction 공고 추출 결과(enrichDerived 이후 - items[].pyeong_price_krw 포함)
     * @param medianPyeongManwon 주변 실거래 중위 평당가(만원/평), 없으면 null
     */
    fun compare(extraction: JsonNode, medianPyeongManwon: Long?, compCount: Int): MarketComparison {
        val market = medianPyeongManwon?.takeIf { it > 0 && compCount >= MIN_COMPS }?.let { it * 10_000 }
        val items = extraction.path("items").takeIf { it.isArray && it.size() > 0 }?.toList()
        val units = if (items != null) {
            items.mapIndexed { i, item -> unit(item, i, market) }
        } else {
            listOf(representative(extraction, market))
        }
        val note = when {
            market == null && medianPyeongManwon == null -> "주변 상업업무용 실거래를 찾지 못해 시세 대비 판단은 보류합니다."
            market == null -> "주변 실거래 표본이 ${compCount}건으로 적어 시세 대비 판단은 보류합니다(최소 ${MIN_COMPS}건)."
            else -> "시세 = 주변 상업업무용 실거래 ${compCount}건의 중위 평당가(국토부 RTMS). 주거·토지 공고는 비교 기준이 약합니다."
        }
        return MarketComparison(market, compCount, units, note)
    }

    private fun unit(item: JsonNode, index: Int, market: Long?): UnitComparison {
        val area = item.path("area_m2").takeIf { it.isNumber }?.asDouble()?.takeIf { it > 0 }
        val rounds = item.path("round_prices").takeIf { it.isArray }?.mapNotNull { n -> n.takeIf { it.isNumber }?.asLong()?.takeIf { it > 0 } }.orEmpty()
        val pyeong = item.path("pyeong_price_krw").takeIf { it.isNumber }?.asLong()
            ?: NoticeCalculator.pyeongPriceKrw(NoticeCalculator.basePriceKrw(rounds, item.path("appraisal_krw").asLong(0)), area)
        val label = listOf(item.path("no").asText(""), item.path("unit").asText(""))
            .filter { it.isNotBlank() && it != "null" }.joinToString(" ").ifBlank { "물건 ${index + 1}" }
        return UnitComparison(label, pyeong, vsMarket(pyeong, market), firstRoundBelow(rounds, area, market), rounds.size)
    }

    private fun representative(extraction: JsonNode, market: Long?): UnitComparison {
        val pyeong = extraction.path("derived").path("pyeong_price_krw").takeIf { it.isNumber }?.asLong()
        val label = extraction.path("target").path("name").asText("").takeIf { it.isNotBlank() && it != "null" } ?: "대상 물건"
        return UnitComparison(label, pyeong, vsMarket(pyeong, market), null, 0)
    }

    private fun vsMarket(pyeong: Long?, market: Long?): Double? {
        if (pyeong == null || market == null || market <= 0) return null
        return ((pyeong.toDouble() / market - 1.0) * 1000).roundToLong() / 10.0
    }

    private fun firstRoundBelow(rounds: List<Long>, area: Double?, market: Long?): Int? {
        if (market == null || area == null || rounds.isEmpty()) return null
        val idx = rounds.indexOfFirst { p -> (NoticeCalculator.pyeongPriceKrw(p, area) ?: Long.MAX_VALUE) <= market }
        return if (idx >= 0) idx + 1 else null
    }

    /** 프롬프트 주입용 사실 블록. */
    fun factBlock(c: MarketComparison): String = buildString {
        append("\n[코드 계산 - 시세 대비(그대로 인용, 다시 계산하지 말 것)]\n")
        append("- ").append(c.note).append('\n')
        c.marketPyeongKrw?.let { append("- 시세(중위 평당가): %,d원/평\n".format(it)) }
        c.units.forEach { u ->
            append("- ").append(u.label).append(": ")
            append(u.pyeongPriceKrw?.let { "평단가 %,d원/평".format(it) } ?: "평단가 산출 불가")
            u.vsMarketPct?.let { append(", 시세 대비 ").append(if (it > 0) "+" else "").append(it).append('%') }
            if (u.roundCount > 0) {
                append(", 회차 ").append(u.roundCount).append("개 중 ")
                append(u.firstRoundBelowMarket?.let { "${it}차부터 시세 이하" } ?: "끝까지 시세 이상 또는 판단 보류")
            }
            append('\n')
        }
    }
}
