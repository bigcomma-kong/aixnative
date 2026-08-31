package com.aixnative.notice.domain

import kotlin.math.roundToLong

/**
 * 공고 파생 지표를 **코드가 계산한다**(순수 object). AI 에게 산수를 시키지 않는 것이 이 제품의 원칙이다 -
 * 곱셈은 틀려도 티가 안 나고, 틀린 평단가는 응찰 판단을 그대로 망친다.
 *
 * AI 는 공고문에서 숫자를 **옮기기만** 하고, 평단가·수익률은 여기서 다시 만든다.
 */
object NoticeCalculator {

    /** ㎡ → 평 환산 계수. */
    const val PYEONG_PER_M2 = 0.3025

    /**
     * 평당가(원/평) = 기준가 ÷ 전용평.
     *
     * @param priceKrw 1차 최저입찰가(없으면 감정가). 0 이하면 산출 불가.
     * @param areaM2 전용면적(㎡). 0 이하면 산출 불가.
     * @return 원 단위 정수, 산출 불가면 null(0 을 돌려주면 "0원/평"으로 표시돼 오해를 부른다).
     */
    fun pyeongPriceKrw(priceKrw: Long?, areaM2: Double?): Long? {
        if (priceKrw == null || priceKrw <= 0) return null
        if (areaM2 == null || areaM2 <= 0) return null
        val pyeong = areaM2 * PYEONG_PER_M2
        if (pyeong <= 0) return null
        return (priceKrw / pyeong).roundToLong()
    }

    /**
     * 총수익률(%) = 연 임대료 ÷ 매입가 x 100.
     *
     * @param monthlyRentKrw 월 임대료. null·0 이면 산출하지 않는다(공고에 임대 정보가 없는 경우가 흔하다).
     * @param priceKrw 매입 기준가(1차 최저입찰가 합계).
     * @return 소수 둘째 자리까지, 산출 불가면 null.
     */
    fun grossYieldPct(monthlyRentKrw: Long?, priceKrw: Long?): Double? {
        if (monthlyRentKrw == null || monthlyRentKrw <= 0) return null
        if (priceKrw == null || priceKrw <= 0) return null
        val pct = monthlyRentKrw.toDouble() * MONTHS_PER_YEAR / priceKrw * 100.0
        return (pct * 100).roundToLong() / 100.0
    }

    /**
     * 물건 1건의 기준가 - 1차 최저입찰가가 있으면 그것, 없으면 감정가.
     * 회차별 저감표가 있는 공고는 1차가 실제 응찰 시작가라 비교 기준으로 맞다.
     */
    fun basePriceKrw(roundPrices: List<Long>?, appraisalKrw: Long?): Long? =
        roundPrices?.firstOrNull { it > 0 } ?: appraisalKrw?.takeIf { it > 0 }

    private const val MONTHS_PER_YEAR = 12
}
