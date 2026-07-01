package com.aixnative.underwriting.domain

import kotlin.math.roundToLong

/**
 * 매입·매각 가격 예측 — 결정론적 밸류에이션 밴드 (단위: 억원/%). 매수 입찰가·매도 호가 판단용.
 * 숫자는 코드(소득환원 + 거래사례), 시장 해석·전략은 AI(환각 차단).
 *
 *   소득환원가(Income)  = NOI / 시장 Cap
 *   거래사례가(Comp)    = 실거래 중위 평당가 × 연면적(평)
 *   추정가(Estimate)    = 가용 방법 가중평균(Income .6 / Comp .4, 정규화)
 *   적정 매입가 밴드     = 추정가 × [할인 하한, 시장가 상한]  (입찰은 추정가 이하)
 *   예상 매각가 밴드     = 추정가 × [시장가, 상방]            (보유/경쟁입찰 프리미엄)
 *
 * 입력이 한쪽만 있어도(소득 또는 사례) 그 방법으로 산출하고 confidence 를 낮춘다.
 */
object PriceEstimator {

    /** 1평 = 3.305785㎡. */
    const val PYEONG_SQM = 3.305785

    /**
     * @param noiEok 안정화/직전 NOI(억). 없으면 소득환원 미사용.
     * @param marketCapPct 시장 Cap(%). 미입력 시 서비스가 자산유형 기본값 보정.
     * @param areaPyeong 연면적(평). 없으면 거래사례법 미사용.
     * @param compPyeongManwon 실거래 중위 평당가(만원/평). 없으면 거래사례법 미사용.
     * @param compCount 사용된 거래사례 건수(confidence 산정).
     */
    data class Inputs(
        val noiEok: Double? = null,
        val marketCapPct: Double = 0.0,
        val areaPyeong: Double? = null,
        val compPyeongManwon: Double? = null,
        val compCount: Int = 0,
    )

    data class Method(val name: String, val valueEok: Double)

    data class Result(
        val incomeValueEok: Double?,   // 소득환원가 (NOI/Cap)
        val compValueEok: Double?,     // 거래사례가 (평당가×면적)
        val estimateEok: Double,       // 가중 추정가
        val buyLowEok: Double,         // 적정 매입가 하한(협상 여지)
        val buyHighEok: Double,        // 적정 매입가 상한(= 시장 추정가)
        val sellLowEok: Double,        // 예상 매각가 하한(시장가)
        val sellHighEok: Double,       // 예상 매각가 상한(경쟁입찰 프리미엄)
        val impliedCapPct: Double,     // NOI / 추정가
        val confidence: String,        // HIGH | MEDIUM | LOW
        val methods: List<Method>,
    )

    fun compute(input: Inputs): Result {
        val income = if (input.noiEok != null && input.noiEok > 0 && input.marketCapPct > 0)
            input.noiEok / (input.marketCapPct / 100.0) else null

        val comp = if (input.areaPyeong != null && input.areaPyeong > 0 &&
            input.compPyeongManwon != null && input.compPyeongManwon > 0
        ) input.compPyeongManwon * input.areaPyeong / MANWON_PER_EOK else null

        // 가용 방법 가중평균(소득 .6 / 사례 .4, 미가용분 정규화)
        val wIncome = if (income != null) 0.6 else 0.0
        val wComp = if (comp != null) 0.4 else 0.0
        val wSum = wIncome + wComp
        val estimate = if (wSum > 0)
            ((income ?: 0.0) * wIncome + (comp ?: 0.0) * wComp) / wSum else 0.0

        val impliedCap = if (estimate > 0 && input.noiEok != null) input.noiEok / estimate * 100.0 else 0.0

        val methods = buildList {
            income?.let { add(Method("소득환원 (NOI/Cap)", r(it))) }
            comp?.let { add(Method("거래사례 (중위 평당가×연면적)", r(it))) }
        }

        return Result(
            incomeValueEok = income?.let { r(it) },
            compValueEok = comp?.let { r(it) },
            estimateEok = r(estimate),
            buyLowEok = r(estimate * BUY_LOW),
            buyHighEok = r(estimate * BUY_HIGH),
            sellLowEok = r(estimate * SELL_LOW),
            sellHighEok = r(estimate * SELL_HIGH),
            impliedCapPct = r2(impliedCap),
            confidence = confidence(income != null, comp != null, input.compCount),
            methods = methods,
        )
    }

    /** 두 방법 모두 + 사례 충분 → HIGH, 한 방법 → MEDIUM, 빈약 → LOW. */
    private fun confidence(hasIncome: Boolean, hasComp: Boolean, compCount: Int): String = when {
        hasIncome && hasComp && compCount >= COMP_MIN_HIGH -> "HIGH"
        hasIncome || hasComp -> "MEDIUM"
        else -> "LOW"
    }

    private const val MANWON_PER_EOK = 10_000.0
    private const val COMP_MIN_HIGH = 3
    private const val BUY_LOW = 0.93   // 입찰 협상 하한(-7%)
    private const val BUY_HIGH = 1.00  // 시장 추정가 = 입찰 상한
    private const val SELL_LOW = 1.00  // 시장가
    private const val SELL_HIGH = 1.10 // 경쟁입찰/보유 프리미엄(+10%)

    private fun r(v: Double): Double = (v * 10.0).roundToLong() / 10.0
    private fun r2(v: Double): Double = (v * 100.0).roundToLong() / 100.0
}
