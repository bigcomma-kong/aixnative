package com.aixnative.tenant.domain

import com.aixnative.integration.dart.domain.DartFinancials
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sqrt

/**
 * 임차인 신용·임대료 지급능력 - **전부 코드 계산**(AI 는 이 결과를 서술만 한다).
 *
 * 사내 레거시의 가중 점수표를 그대로 옮겼다: 안정성 45%(부채·유동·당좌) / 수익성 40%(영업이익률·순이익률·ROE) /
 * 성장성 15%(매출·영업이익 성장). 누락 지표는 가중치를 재배분한다. A(80+)·B(60+)·C(40+)·D.
 * 10년 시뮬레이션은 과거 영업이익 성장률의 평균±표준편차로 낙관·기준·비관 시나리오를 만든다.
 */
object TenantCreditCalculator {

    data class Ratios(
        val debtRatio: Double? = null,
        val currentRatio: Double? = null,
        val quickRatio: Double? = null,
        val equityRatio: Double? = null,
        val operatingMargin: Double? = null,
        val netMargin: Double? = null,
        val roe: Double? = null,
        val roa: Double? = null,
        val revenueGrowth: Double? = null,
        val operatingIncomeGrowth: Double? = null,
        /** 자본총계 ≤ 0(자본잠식). 이때 부채비율·ROE 는 부호가 뒤집혀 의미가 없으므로 null 로 두고 따로 벌점한다. */
        val capitalImpaired: Boolean = false,
    )

    data class Rating(
        val grade: String,
        val score: Int,
        val stabilityScore: Int,
        val profitabilityScore: Int,
        val growthScore: Int,
        val description: String,
    )

    data class PaymentCapacity(val rating: String, val score: Int, val monthlyOperatingCashFlowWon: Long?, val description: String)

    data class ScenarioRates(val optimisticPct: Double, val basePct: Double, val pessimisticPct: Double, val rationale: String)

    data class YearRow(val year: Int, val operatingIncomeWon: Long, val annualRentWon: Long, val coverage: Double, val risk: String)

    data class RentSimulation(
        val monthlyRentWon: Long,
        val rentGrowthPct: Double,
        val currentCoverage: Double,
        val rates: ScenarioRates,
        val optimistic: List<YearRow>,
        val base: List<YearRow>,
        val pessimistic: List<YearRow>,
        /** 커버리지가 처음 1.0 미만이 되는 연차(없으면 null). */
        val breakEven: Map<String, Int?>,
        val assessment: String,
    )

    fun ratios(fs: DartFinancials): Ratios {
        // 자본잠식이면 부채비율이 음수가 돼 "부채비율 50% 미만 = 만점" 구간에 걸린다(레거시 버그). 잠식은 따로 다룬다.
        val impaired = fs.totalEquity != null && fs.totalEquity.signum() <= 0
        val equity = fs.totalEquity?.takeIf { !impaired }
        return ratiosOf(fs, equity, impaired)
    }

    private fun ratiosOf(fs: DartFinancials, equity: BigDecimal?, impaired: Boolean): Ratios = Ratios(
        debtRatio = pct(fs.totalLiabilities, equity),
        currentRatio = pct(fs.currentAssets, fs.currentLiabilities),
        quickRatio = fs.currentAssets?.let { ca -> pct(ca - (fs.inventory ?: BigDecimal.ZERO), fs.currentLiabilities) },
        equityRatio = pct(fs.totalEquity, fs.totalAssets),
        operatingMargin = pct(fs.operatingIncome, fs.revenue),
        netMargin = pct(fs.netIncome, fs.revenue),
        roe = pct(fs.netIncome, equity),
        roa = pct(fs.netIncome, fs.totalAssets),
        revenueGrowth = growth(fs.revenue, fs.priorRevenue),
        operatingIncomeGrowth = growth(fs.operatingIncome, fs.priorOperatingIncome),
        capitalImpaired = impaired,
    )

    fun rate(r: Ratios): Rating {
        // 자본잠식은 부채비율 최하 구간 점수로 반영한다.
        val debtScore = r.debtRatio?.let { score(it, DEBT, ascending = false) } ?: if (r.capitalImpaired) DEBT.second else null
        val parts = listOfNotNull(
            debtScore?.let { it to 0.20 },
            r.currentRatio?.let { score(it, CURRENT) to 0.15 },
            r.quickRatio?.let { score(it, QUICK) to 0.10 },
            r.operatingMargin?.let { score(it, OP_MARGIN) to 0.20 },
            r.netMargin?.let { score(it, NET_MARGIN) to 0.10 },
            r.roe?.let { score(it, ROE) to 0.10 },
            r.revenueGrowth?.let { score(it, REV_GROWTH) to 0.10 },
            r.operatingIncomeGrowth?.let { score(it, OPI_GROWTH) to 0.05 },
        )
        // 누락 지표가 있으면 남은 지표의 가중치 합으로 정규화(레거시는 지표 수 비율로 키웠다 - 가중치가 달라 왜곡돼 고쳤다).
        val weightSum = parts.sumOf { it.second }
        val total = if (weightSum > 0) parts.sumOf { it.first * it.second } / weightSum else 0.0
        val finalScore = total.coerceIn(0.0, 100.0).roundToInt()
        val grade = when {
            parts.isEmpty() -> "N/A"
            r.capitalImpaired -> "D" // 자본잠식 기업은 다른 지표가 좋아도 임차인 등급 상한 D
            finalScore >= 80 -> "A"
            finalScore >= 60 -> "B"
            finalScore >= 40 -> "C"
            else -> "D"
        }
        fun avg(vararg xs: Double?) = xs.filterNotNull().takeIf { it.isNotEmpty() }?.average()?.roundToInt() ?: 0
        return Rating(
            grade = grade,
            score = finalScore,
            stabilityScore = avg(debtScore, r.currentRatio?.let { score(it, CURRENT) }, r.quickRatio?.let { score(it, QUICK) }),
            profitabilityScore = avg(r.operatingMargin?.let { score(it, OP_MARGIN) }, r.netMargin?.let { score(it, NET_MARGIN) }, r.roe?.let { score(it, ROE) }),
            growthScore = avg(r.revenueGrowth?.let { score(it, REV_GROWTH) }, r.operatingIncomeGrowth?.let { score(it, OPI_GROWTH) }),
            description = GRADE_TEXT[grade] ?: "재무 지표가 부족해 등급을 산정하지 못했습니다.",
        )
    }

    fun paymentCapacity(fs: DartFinancials, r: Ratios): PaymentCapacity {
        val scores = listOfNotNull(
            fs.operatingCashFlow?.let { ocf ->
                when {
                    ocf.signum() <= 0 -> 20.0
                    fs.revenue == null || fs.revenue.signum() <= 0 -> 60.0
                    else -> band(ocf.toDouble() / fs.revenue.toDouble() * 100, listOf(10.0 to 100.0, 5.0 to 80.0, 2.0 to 60.0), 40.0)
                }
            },
            r.currentRatio?.let { band(it, listOf(200.0 to 100.0, 150.0 to 80.0, 100.0 to 60.0, 50.0 to 40.0), 20.0) },
            r.quickRatio?.let { band(it, listOf(150.0 to 100.0, 100.0 to 80.0, 70.0 to 60.0, 50.0 to 40.0), 20.0) },
            r.operatingMargin?.let { band(it, listOf(10.0 to 100.0, 5.0 to 80.0, 2.0 to 60.0, 0.0 to 40.0), 20.0) },
            r.debtRatio?.let { bandBelow(it, listOf(50.0 to 100.0, 100.0 to 80.0, 150.0 to 60.0, 200.0 to 40.0), 20.0) }
                ?: if (r.capitalImpaired) 20.0 else null,
        )
        val avg = if (scores.isEmpty()) 50.0 else scores.average()
        val (rating, text) = when {
            avg >= 80 -> "충분" to "재무구조가 안정적이고 영업현금흐름이 양호해 임대료 지급 능력이 충분합니다."
            avg >= 60 -> "양호" to "전반적으로 양호한 재무상태로 임대료 지급에 큰 문제가 없을 것으로 봅니다."
            avg >= 40 -> "보통" to "재무상태가 보통 수준입니다. 정기적인 모니터링을 권장합니다."
            else -> "부족" to "재무구조가 취약하거나 현금흐름이 불안정합니다. 추가 담보 확보를 권장합니다."
        }
        return PaymentCapacity(rating, avg.roundToInt(), fs.operatingCashFlow?.let { (it.toDouble() / 12).roundToLong() }, text)
    }

    fun risks(fs: DartFinancials, r: Ratios): List<String> = buildList {
        if ((r.debtRatio ?: 0.0) > 200) add("부채비율이 200%를 넘어 재무 레버리지가 높습니다.")
        if (r.currentRatio != null && r.currentRatio < 100) add("유동비율이 100% 미만이라 단기 지급능력에 주의가 필요합니다.")
        if (r.operatingMargin != null && r.operatingMargin < 0) add("영업손실이 나고 있어 지속가능성 검토가 필요합니다.")
        if (r.revenueGrowth != null && r.revenueGrowth < -10) add("매출이 10% 이상 줄어 사업 축소 우려가 있습니다.")
        if (fs.operatingCashFlow != null && fs.operatingCashFlow.signum() < 0) add("영업활동 현금흐름이 음수라 자금조달 압박이 있을 수 있습니다.")
        if (fs.totalEquity != null && fs.totalEquity.signum() < 0) add("완전 자본잠식 상태로 재무 위험이 매우 높습니다.")
    }

    /**
     * 과거 영업이익 성장률(최신순 재무 이력의 인접 연도 쌍) → 시나리오 성장률.
     * 낙관 = max(평균+σ, 최고×0.8), 비관 = min(평균-σ, 최저×0.8). 극단값은 잘라낸다.
     */
    fun scenarioRates(history: List<DartFinancials>): ScenarioRates {
        val sorted = history.sortedByDescending { it.year }
        val growths = sorted.zipWithNext().mapNotNull { (cur, prev) ->
            val c = cur.operatingIncome ?: return@mapNotNull null
            val p = prev.operatingIncome?.takeIf { it.signum() != 0 } ?: return@mapNotNull null
            (c - p).toDouble() / p.abs().toDouble() * 100
        }
        if (growths.isEmpty()) return ScenarioRates(5.0, 0.0, -5.0, "재무 이력이 부족해 기본값 적용(낙관 +5%, 기준 0%, 비관 -5%)")
        val mean = growths.average()
        val sd = sqrt(growths.sumOf { (it - mean).pow(2) } / growths.size)
        val hi = growths.max()
        val lo = growths.min()
        return ScenarioRates(
            optimisticPct = round1(max(mean + sd, hi * 0.8).coerceIn(-10.0, 50.0)),
            basePct = round1(mean.coerceIn(-20.0, 30.0)),
            pessimisticPct = round1(min(mean - sd, lo * 0.8).coerceIn(-30.0, 10.0)),
            rationale = "최근 %d개 구간 영업이익 성장률: 평균 %.1f%%, 표준편차 %.1f%%, 최고 %.1f%%, 최저 %.1f%%".format(growths.size, mean, sd, hi, lo),
        )
    }

    /** 영업이익 대비 연 임대료 커버리지 10년 시뮬레이션(복리). */
    fun simulate(latest: DartFinancials, history: List<DartFinancials>, monthlyRentWon: Long, rentGrowthPct: Double = 3.0, years: Int = 10): RentSimulation {
        require(monthlyRentWon > 0) { "월 임대료는 0보다 커야 합니다." }
        val rates = scenarioRates(history)
        val income = latest.operatingIncome?.toDouble() ?: 0.0
        val annualRent = monthlyRentWon * 12.0
        fun run(growthPct: Double): List<YearRow> {
            var inc = income
            var rent = annualRent
            return (1..years).map { y ->
                inc *= 1 + growthPct / 100
                rent *= 1 + rentGrowthPct / 100
                val cov = if (rent > 0) round2(inc / rent) else 0.0
                YearRow(y, inc.roundToLong(), rent.roundToLong(), cov, riskOf(cov))
            }
        }
        val opt = run(rates.optimisticPct)
        val base = run(rates.basePct)
        val pes = run(rates.pessimisticPct)
        fun breakEven(rows: List<YearRow>) = rows.firstOrNull { it.coverage < 1.0 }?.year
        val be = mapOf("optimistic" to breakEven(opt), "base" to breakEven(base), "pessimistic" to breakEven(pes))
        val assessment = when {
            be["pessimistic"] == null -> "모든 시나리오에서 ${years}년간 영업이익이 임대료를 웃돕니다. 장기 임대에 적합한 임차인으로 볼 수 있습니다."
            be["base"] == null -> "기준 시나리오까지는 ${years}년간 안정적이나 비관 시나리오에서는 ${be["pessimistic"]}년차에 영업이익이 임대료를 밑돕니다. 정기 모니터링을 권장합니다."
            be["optimistic"] == null -> "기준 시나리오에서 ${be["base"]}년차에 영업이익이 임대료를 밑돕니다. 계약 기간 단축이나 보증 강화를 검토하세요."
            else -> "낙관 시나리오에서도 ${be["optimistic"]}년차에 영업이익이 임대료를 밑돕니다. 높은 수준의 담보 확보가 필요합니다."
        }
        return RentSimulation(monthlyRentWon, rentGrowthPct, if (annualRent > 0) round2(income / annualRent) else 0.0, rates, opt, base, pes, be, assessment)
    }

    fun riskOf(coverage: Double): String = when {
        coverage >= 3.0 -> "안전"
        coverage >= 2.0 -> "양호"
        coverage >= 1.5 -> "주의"
        coverage >= 1.0 -> "경고"
        else -> "위험"
    }

    // ── 점수표(사내 레거시 기준 그대로) ──
    private val DEBT = listOf(50.0 to 100.0, 100.0 to 85.0, 150.0 to 70.0, 200.0 to 55.0, 300.0 to 40.0) to 25.0
    private val CURRENT = listOf(250.0 to 100.0, 200.0 to 90.0, 150.0 to 75.0, 100.0 to 60.0, 75.0 to 45.0) to 30.0
    private val QUICK = listOf(200.0 to 100.0, 150.0 to 85.0, 100.0 to 70.0, 70.0 to 55.0, 50.0 to 40.0) to 25.0
    private val OP_MARGIN = listOf(15.0 to 100.0, 10.0 to 85.0, 5.0 to 70.0, 2.0 to 55.0, 0.0 to 40.0, -5.0 to 25.0) to 10.0
    private val NET_MARGIN = listOf(10.0 to 100.0, 7.0 to 85.0, 3.0 to 70.0, 0.0 to 55.0, -5.0 to 35.0) to 15.0
    private val ROE = listOf(20.0 to 100.0, 15.0 to 85.0, 10.0 to 70.0, 5.0 to 55.0, 0.0 to 40.0) to 20.0
    private val REV_GROWTH = listOf(20.0 to 100.0, 10.0 to 85.0, 5.0 to 70.0, 0.0 to 55.0, -5.0 to 40.0, -10.0 to 25.0) to 10.0
    private val OPI_GROWTH = listOf(25.0 to 100.0, 15.0 to 85.0, 5.0 to 70.0, 0.0 to 55.0, -10.0 to 40.0) to 20.0

    private val GRADE_TEXT = mapOf(
        "A" to "재무 안정성이 우수하고 수익성이 양호한 우량 기업입니다. 임대료 지급 능력이 충분하며 임대 리스크가 낮습니다.",
        "B" to "전반적으로 양호한 재무상태입니다. 일부 지표는 주의가 필요하나 임대 리스크는 낮은 편입니다.",
        "C" to "보통 수준의 재무상태입니다. 보증금 보강과 정기적인 재무 모니터링을 권장합니다.",
        "D" to "재무 리스크가 높습니다. 임대차 체결 시 신중한 검토와 추가 담보 확보를 권장합니다.",
    )

    /** 높을수록 좋은 지표: 임계값 초과(>) 첫 구간 점수. 부채비율처럼 낮을수록 좋으면 ascending=false 로 미만(<) 비교. */
    private fun score(v: Double, table: Pair<List<Pair<Double, Double>>, Double>, ascending: Boolean = true): Double =
        if (ascending) band(v, table.first, table.second) else bandBelow(v, table.first, table.second)

    private fun band(v: Double, steps: List<Pair<Double, Double>>, floor: Double): Double =
        steps.firstOrNull { v > it.first }?.second ?: floor

    private fun bandBelow(v: Double, steps: List<Pair<Double, Double>>, floor: Double): Double =
        steps.firstOrNull { v < it.first }?.second ?: floor

    private fun pct(num: BigDecimal?, den: BigDecimal?): Double? {
        if (num == null || den == null || den.signum() == 0) return null
        return num.multiply(BigDecimal(100)).divide(den, 2, RoundingMode.HALF_UP).toDouble()
    }

    private fun growth(cur: BigDecimal?, prior: BigDecimal?): Double? {
        if (cur == null || prior == null || prior.signum() == 0) return null
        return (cur - prior).multiply(BigDecimal(100)).divide(prior.abs(), 2, RoundingMode.HALF_UP).toDouble()
    }

    private fun round1(v: Double) = (v * 10).roundToLong() / 10.0
    private fun round2(v: Double) = (v * 100).roundToLong() / 100.0
}
