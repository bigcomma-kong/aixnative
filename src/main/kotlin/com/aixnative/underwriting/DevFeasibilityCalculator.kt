package com.aixnative.underwriting

import kotlin.math.roundToLong

/**
 * 개발사업 타당성 — 결정론적 수익성 계산 (단위: 억원/%). MASTERN 개발타당성 트랙의
 * 핵심 수치(총사업비·개발이익·Development Margin·Yield-on-Cost·Stabilized Value)를
 * AI 추정이 아닌 코드로 확정한다. 인허가·PF·리스크 서술은 AI(환각 차단).
 *
 *   총사업비 = (토지+공사+금융+기타) × (1 + 우발비율)
 *   GDV(자산가치) = 분양수입(있으면) else Stabilized Value(=Year1 NOI / Exit Cap)
 *   개발이익 = GDV − 총사업비
 *   Development Margin(=Profit on Cost) = 개발이익 / 총사업비 × 100
 *   Yield-on-Cost = 안정화 NOI / 총사업비 × 100
 */
object DevFeasibilityCalculator {

    /** 입력 (단위: 억원/%). [salesRevenueEok] 0 이하이면 분양수입 미사용(임대형 → Stabilized Value 사용). */
    data class Inputs(
        val landCostEok: Double,          // 토지비
        val constructionCostEok: Double,  // 공사비
        val financingCostEok: Double = 0.0, // 금융비용(PF 이자 등)
        val otherCostEok: Double = 0.0,   // 설계·인허가·마케팅 등 기타
        val contingencyPct: Double = 5.0, // 우발비율(기본 공사+토지+금융+기타의 5%)
        val stabilizedNoiEok: Double = 0.0, // 안정화 Year1 NOI(임대형)
        val exitCapPct: Double = 0.0,     // Stabilized Value 산정용 Exit Cap
        val salesRevenueEok: Double = 0.0, // 분양수입(분양형)
    )

    data class Sensitivity(val label: String, val profitMarginPct: Double, val developmentProfitEok: Double)

    data class Result(
        val baseCostEok: Double,
        val contingencyEok: Double,
        val totalProjectCostEok: Double,
        val stabilizedValueEok: Double,
        val grossDevelopmentValueEok: Double,
        val developmentProfitEok: Double,
        val profitMarginPct: Double,   // Development Margin / Profit on Cost
        val yieldOnCostPct: Double,
        val marginVerdict: String,     // GO | CONDITIONAL | NO_GO (가이드라인 임계값 코드 판정)
        val sensitivity: List<Sensitivity>,
    )

    /** Development Margin → GO/CONDITIONAL/NO_GO (devFeasibilityGuidelineText 기준: ≥15 GO, 10~15 CONDITIONAL). */
    fun marginVerdict(marginPct: Double): String = when {
        marginPct >= CreGuidelines.MIN_DEV_MARGIN_PCT -> "GO"
        marginPct >= DEV_MARGIN_NOGO_FLOOR -> "CONDITIONAL"
        else -> "NO_GO"
    }

    fun compute(input: Inputs): Result {
        val baseCost = input.landCostEok + input.constructionCostEok + input.financingCostEok + input.otherCostEok
        val contingency = baseCost * input.contingencyPct / 100.0
        val totalCost = baseCost + contingency

        val stabilizedValue = stabilizedValue(input.stabilizedNoiEok, input.exitCapPct)
        val gdv = if (input.salesRevenueEok > 0) input.salesRevenueEok else stabilizedValue

        val profit = gdv - totalCost
        val margin = if (totalCost > 0) profit / totalCost * 100.0 else 0.0
        val yoc = if (totalCost > 0) input.stabilizedNoiEok / totalCost * 100.0 else 0.0

        return Result(
            baseCostEok = r(baseCost),
            contingencyEok = r(contingency),
            totalProjectCostEok = r(totalCost),
            stabilizedValueEok = r(stabilizedValue),
            grossDevelopmentValueEok = r(gdv),
            developmentProfitEok = r(profit),
            profitMarginPct = r2(margin),
            yieldOnCostPct = r2(yoc),
            marginVerdict = marginVerdict(margin),
            sensitivity = sensitivity(input),
        )
    }

    /** 핵심 변수(공사비·수입·Exit Cap) 단변량 충격에 따른 마진 민감도. */
    private fun sensitivity(base: Inputs): List<Sensitivity> = listOf(
        "공사비 +10%" to base.copy(constructionCostEok = base.constructionCostEok * 1.10),
        "공사비 -10%" to base.copy(constructionCostEok = base.constructionCostEok * 0.90),
        "수입 +10%" to base.scaleRevenue(1.10),
        "수입 -10%" to base.scaleRevenue(0.90),
        "Exit Cap +50bps" to base.copy(exitCapPct = base.exitCapPct + 0.5),
        "Exit Cap -50bps" to base.copy(exitCapPct = maxOf(0.1, base.exitCapPct - 0.5)),
    ).map { (label, shocked) ->
        val r = computeCore(shocked)
        Sensitivity(label, r2(r.first), r(r.second))
    }

    /** 분양형이면 분양수입, 임대형이면 NOI 를 비례 조정(민감도용). */
    private fun Inputs.scaleRevenue(f: Double): Inputs =
        if (salesRevenueEok > 0) copy(salesRevenueEok = salesRevenueEok * f)
        else copy(stabilizedNoiEok = stabilizedNoiEok * f)

    /** 민감도 내부 계산 — (margin%, profitEok) 만 산출(재귀 회피). */
    private fun computeCore(input: Inputs): Pair<Double, Double> {
        val baseCost = input.landCostEok + input.constructionCostEok + input.financingCostEok + input.otherCostEok
        val totalCost = baseCost * (1 + input.contingencyPct / 100.0)
        val gdv = if (input.salesRevenueEok > 0) input.salesRevenueEok
        else stabilizedValue(input.stabilizedNoiEok, input.exitCapPct)
        val profit = gdv - totalCost
        val margin = if (totalCost > 0) profit / totalCost * 100.0 else 0.0
        return margin to profit
    }

    private fun stabilizedValue(noiEok: Double, exitCapPct: Double): Double =
        if (noiEok > 0 && exitCapPct > 0) noiEok / (exitCapPct / 100.0) else 0.0

    private fun r(v: Double): Double = (v * 10.0).roundToLong() / 10.0
    private fun r2(v: Double): Double = (v * 100.0).roundToLong() / 100.0

    private const val DEV_MARGIN_NOGO_FLOOR = 10.0 // Margin < 10% = NO-GO (가이드라인)
}
