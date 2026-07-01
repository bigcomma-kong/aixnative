package com.aixnative.underwriting.domain

import kotlin.math.pow
import kotlin.math.roundToLong

/**
 * 매각 BOV(Broker Opinion of Value) 3-Method 가격평가 — 결정론적 계산 (단위: 억원/%/년).
 * 숫자는 코드, 매각 전략·포지셔닝 서술은 AI (환각 차단, 평가수치 신뢰).
 *
 * Method 1 Direct Cap : 안정화 NOI / 시장 Cap Rate
 * Method 2 DCF        : 보유기간 NOI 할인 + 잔존가치(ExitNOI/ExitCap) 할인 (무차입 기준)
 * Method 3 Sales Comp : 비교거래 추정가(선택, 입력 없으면 제외)
 * Blended BOV         : 가용 방법 가중평균(DirectCap .4 / DCF .3 / Comp .3), 미가용분 정규화
 *                       (bovGuidelineText 의 문서 가중치와 일치 — Direct Cap 40 / DCF 30 / Comp 30)
 */
object BovValuator {

    /** 입력 가정 (단위: 억원/%/년). [salesCompValueEok] 0 이하이면 비교거래법 미사용. */
    data class Inputs(
        val noiEok: Double,            // 안정화/직전12개월 NOI
        val marketCapPct: Double,      // 시장 Cap Rate (Direct Cap)
        val discountRatePct: Double,   // DCF 할인율
        val exitCapPct: Double,        // 잔존가치 Cap
        val holdYears: Int = 5,
        val rentGrowthPct: Double = 3.0,
        val salesCompValueEok: Double = 0.0, // 비교거래 추정가(0 이하이면 미사용)
    )

    data class Result(
        val directCapValueEok: Double,
        val dcfValueEok: Double,
        val salesCompValueEok: Double,
        val bovValueEok: Double,
        val lowEok: Double,
        val highEok: Double,
        val impliedCapPct: Double,
    )

    fun compute(input: Inputs): Result {
        val directCap = if (input.marketCapPct > 0) input.noiEok / (input.marketCapPct / 100.0) else 0.0

        // DCF — 무차입 자산가치: 보유기간 NOI 할인 + 잔존가치 할인
        val n = maxOf(1, input.holdYears)
        val rate = input.discountRatePct / 100.0
        var noi = input.noiEok
        var dcf = 0.0
        for (y in 1..n) {
            if (y > 1) noi *= (1 + input.rentGrowthPct / 100.0)
            dcf += noi / (1 + rate).pow(y.toDouble())
        }
        val exitNoi = noi * (1 + input.rentGrowthPct / 100.0)
        val terminal = if (input.exitCapPct > 0) exitNoi / (input.exitCapPct / 100.0) else 0.0
        dcf += terminal / (1 + rate).pow(n.toDouble())

        val comp = if (input.salesCompValueEok > 0) input.salesCompValueEok else 0.0

        // 가중 BOV — 가용 방법만 정규화 (문서 가이드라인 40/30/30)
        val wDirect = if (directCap > 0) 0.4 else 0.0
        val wDcf = if (dcf > 0) 0.3 else 0.0
        val wComp = if (comp > 0) 0.3 else 0.0
        val wSum = wDirect + wDcf + wComp
        val bov = if (wSum > 0) (directCap * wDirect + dcf * wDcf + comp * wComp) / wSum else 0.0

        val low = bov * 0.925
        val high = bov * 1.075
        val impliedCap = if (bov > 0) input.noiEok / bov * 100.0 else 0.0

        return Result(r(directCap), r(dcf), r(comp), r(bov), r(low), r(high), r2(impliedCap))
    }

    private fun r(v: Double): Double = (v * 10.0).roundToLong() / 10.0
    private fun r2(v: Double): Double = (v * 100.0).roundToLong() / 100.0
}
