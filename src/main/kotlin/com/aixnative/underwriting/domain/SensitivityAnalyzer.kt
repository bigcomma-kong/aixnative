package com.aixnative.underwriting.domain

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToLong

/**
 * 민감도 - 27개 시나리오 격자와 토네이도(변수별 영향도). 결정론 [ProFormaCalculator] 를 그대로 다시 돌린다
 * (별도 DCF 를 두면 화면의 IRR 과 민감도 표의 IRR 이 서로 다른 모델에서 나와 숫자가 안 맞는다).
 *
 * 격자: Exit Cap(±10%) × 임대료 성장률(±1%p) × 대출금리(±10%) = 3^3 = 27.
 * 토네이도: 각 변수만 단독으로 흔든 레버리지 IRR 폭. 폭이 큰 순으로 정렬 - "무엇을 가장 확인해야 하나"의 답.
 */
object SensitivityAnalyzer {

    data class GridPoint(val exitCapPct: Double, val rentGrowthPct: Double, val loanRatePct: Double, val leveredIrrPct: Double)

    data class TornadoBar(
        val variable: String,
        val lowLabel: String,
        val highLabel: String,
        /** 변수를 불리한 쪽으로 흔든 IRR. */
        val lowIrrPct: Double,
        /** 변수를 유리한 쪽으로 흔든 IRR. */
        val highIrrPct: Double,
        val spreadPct: Double,
    )

    data class Result(
        val baseIrrPct: Double,
        val grid: List<GridPoint>,
        val tornado: List<TornadoBar>,
        val p10IrrPct: Double,
        val p50IrrPct: Double,
        val p90IrrPct: Double,
        /** 격자 중 레버리지 IRR 이 음수인 시나리오 수. */
        val negativeCount: Int,
    )

    private const val REL = 0.10
    private const val GROWTH_STEP = 1.0

    fun analyze(base: ProFormaCalculator.Inputs): Result {
        val baseIrr = irr(base)
        val grid = buildList {
            for (dCap in listOf(-REL, 0.0, REL)) for (dG in listOf(-GROWTH_STEP, 0.0, GROWTH_STEP)) for (dR in listOf(-REL, 0.0, REL)) {
                val v = base.copy(
                    exitCapPct = r2(base.exitCapPct * (1 + dCap)),
                    rentGrowthPct = r2(base.rentGrowthPct + dG),
                    loanRatePct = r2(max(0.0, base.loanRatePct * (1 + dR))),
                )
                add(GridPoint(v.exitCapPct, v.rentGrowthPct, v.loanRatePct, irr(v)))
            }
        }
        val tornado = listOf(
            bar("Exit Cap", "+10%", "-10%", base.copy(exitCapPct = base.exitCapPct * (1 + REL)), base.copy(exitCapPct = base.exitCapPct * (1 - REL))),
            bar("임대료 성장률", "-1%p", "+1%p", base.copy(rentGrowthPct = base.rentGrowthPct - GROWTH_STEP), base.copy(rentGrowthPct = base.rentGrowthPct + GROWTH_STEP)),
            bar("대출금리", "+10%", "-10%", base.copy(loanRatePct = base.loanRatePct * (1 + REL)), base.copy(loanRatePct = base.loanRatePct * (1 - REL))),
            bar("매입가", "+10%", "-10%", base.copy(askingPriceEok = base.askingPriceEok * (1 + REL)), base.copy(askingPriceEok = base.askingPriceEok * (1 - REL))),
            bar("NOI", "-10%", "+10%", base.copy(noiEok = base.noiEok * (1 - REL)), base.copy(noiEok = base.noiEok * (1 + REL))),
        ).sortedByDescending { it.spreadPct }
        val irrs = grid.map { it.leveredIrrPct }.sorted()
        return Result(
            baseIrrPct = baseIrr,
            grid = grid,
            tornado = tornado,
            p10IrrPct = r2(percentile(irrs, 0.10)),
            p50IrrPct = r2(percentile(irrs, 0.50)),
            p90IrrPct = r2(percentile(irrs, 0.90)),
            negativeCount = grid.count { it.leveredIrrPct < 0 },
        )
    }

    private fun bar(name: String, lowLabel: String, highLabel: String, low: ProFormaCalculator.Inputs, high: ProFormaCalculator.Inputs): TornadoBar {
        val lo = irr(low)
        val hi = irr(high)
        return TornadoBar(name, lowLabel, highLabel, lo, hi, r2(kotlin.math.abs(hi - lo)))
    }

    private fun irr(i: ProFormaCalculator.Inputs): Double = ProFormaCalculator.compute(i).leveredIrrPct

    private fun percentile(sorted: List<Double>, q: Double): Double {
        if (sorted.isEmpty()) return 0.0
        val idx = q * (sorted.size - 1)
        val lo = floor(idx).toInt()
        val hi = ceil(idx).toInt()
        return if (lo == hi) sorted[lo] else sorted[lo] + (sorted[hi] - sorted[lo]) * (idx - lo)
    }

    private fun r2(v: Double) = (v * 100).roundToLong() / 100.0
}
