package com.aixnative.calc.domain

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToLong

/**
 * 상업용 부동산 세금 간이 계산기 - **코드 계산**(AI 산수 금지 원칙). 원 단위.
 *
 * 범위(결과에 함께 표기): 비주거(상가·오피스·토지) 유상 취득·양도. 주택은 다주택 중과·비과세 요건이 복잡해 제외한다.
 * 사내 레거시 계산기는 양도세에 누진공제를 빼먹어 과세표준 전체에 최고구간 세율을 곱했다(1억 과표에 3,500만원 -
 * 실제 약 1,956만원). 여기서는 누진공제·장기보유특별공제를 반영해 다시 짰다.
 *
 * 세율표는 연도마다 바뀔 수 있다 - 아래 상수가 기준이고, 화면에 "간이 계산·세무 전문가 확인 필요"를 붙인다.
 */
object TaxCalculator {

    const val BASIS = "2026년 귀속 기준 간이 계산"

    enum class AcquisitionType(val label: String, val acqPct: Double, val eduPct: Double, val ruralPct: Double) {
        /** 상가·오피스·토지 등 비주거 유상 취득: 취득세 4% + 지방교육세 0.4% + 농어촌특별세 0.2%. */
        COMMERCIAL("비주거(상가·오피스·토지)", 4.0, 0.4, 0.2),
        /** 농지 유상 취득: 3% + 0.2% + 0.2%. */
        FARMLAND("농지", 3.0, 0.2, 0.2),
        /** 신축(원시취득): 2.8% + 0.16% + 0.2%. */
        NEW_BUILD("신축(원시취득)", 2.8, 0.16, 0.2),
    }

    data class Acquisition(
        val type: String,
        val priceWon: Long,
        val acquisitionTaxWon: Long,
        val eduTaxWon: Long,
        val ruralTaxWon: Long,
        val totalWon: Long,
        val effectivePct: Double,
    )

    fun acquisition(priceWon: Long, type: AcquisitionType = AcquisitionType.COMMERCIAL): Acquisition {
        require(priceWon > 0) { "취득가액은 0보다 커야 합니다." }
        requireWon(priceWon)
        val acq = won(priceWon * type.acqPct / 100)
        val edu = won(priceWon * type.eduPct / 100)
        val rural = won(priceWon * type.ruralPct / 100)
        val total = acq + edu + rural
        return Acquisition(type.label, priceWon, acq, edu, rural, total, r2(total * 100.0 / priceWon))
    }

    /** 종합소득·양도소득 기본세율(누진) - (과세표준 상한, 세율, 누진공제). */
    private val PROGRESSIVE = listOf(
        Triple(14_000_000L, 0.06, 0L),
        Triple(50_000_000L, 0.15, 1_260_000L),
        Triple(88_000_000L, 0.24, 5_760_000L),
        Triple(150_000_000L, 0.35, 15_440_000L),
        Triple(300_000_000L, 0.38, 19_940_000L),
        Triple(500_000_000L, 0.40, 25_940_000L),
        Triple(1_000_000_000L, 0.42, 35_940_000L),
        Triple(Long.MAX_VALUE, 0.45, 65_940_000L),
    )

    /** 법인세(각 사업연도 소득) - (과세표준 상한, 세율, 누진공제). 2026 사업연도부터 구간별 1%p 인상분 반영. */
    private val CORPORATE = listOf(
        Triple(200_000_000L, 0.10, 0L),
        Triple(20_000_000_000L, 0.20, 20_000_000L),
        Triple(300_000_000_000L, 0.22, 420_000_000L),
        Triple(Long.MAX_VALUE, 0.25, 9_420_000_000L),
    )

    fun progressiveTax(base: Long): Long = bracketTax(base, PROGRESSIVE)
    fun corporateTax(base: Long): Long = bracketTax(base, CORPORATE)

    private fun bracketTax(base: Long, table: List<Triple<Long, Double, Long>>): Long {
        if (base <= 0) return 0
        val (_, rate, deduction) = table.first { base <= it.first }
        return max(0L, won(base * rate) - deduction)
    }

    data class Transfer(
        val gainWon: Long,
        val longTermDeductionPct: Double,
        val longTermDeductionWon: Long,
        val basicDeductionWon: Long,
        val taxBaseWon: Long,
        val rateLabel: String,
        val incomeTaxWon: Long,
        val localTaxWon: Long,
        val totalWon: Long,
        val effectivePctOfGain: Double,
        val notes: List<String>,
    )

    /**
     * 개인 양도소득세(비주거 건물·토지, 등기, 사업용 가정).
     * 2년 미만 단기 보유는 단일세율(1년 미만 50%, 1~2년 40%), 3년 이상은 장기보유특별공제(3년 6% + 연 2%p, 최대 30%).
     */
    fun transferIndividual(acquisitionWon: Long, saleWon: Long, expensesWon: Long, holdYears: Double): Transfer {
        require(acquisitionWon > 0 && saleWon > 0) { "취득가액과 양도가액은 0보다 커야 합니다." }
        require(holdYears.isFinite() && holdYears in 0.0..MAX_HOLD_YEARS.toDouble()) { "보유기간은 0~${MAX_HOLD_YEARS}년으로 입력해 주세요." }
        requireWon(acquisitionWon, saleWon, expensesWon)
        val gain = saleWon - acquisitionWon - max(0L, expensesWon)
        val notes = mutableListOf("비사업용 토지 중과(+10%p)·미등기 양도는 반영하지 않았습니다.")
        if (gain <= 0) return Transfer(gain, 0.0, 0, 0, 0, "양도차익 없음", 0, 0, 0, 0.0, notes)
        val ltPct = if (holdYears >= 3) min(30.0, 6.0 + 2.0 * (floor(holdYears) - 3)) else 0.0
        val lt = won(gain * ltPct / 100)
        val basic = min(BASIC_DEDUCTION, gain - lt)
        val base = max(0L, gain - lt - basic)
        val (tax, label) = when {
            holdYears < 1 -> won(base * 0.50) to "단기 1년 미만 50%"
            holdYears < 2 -> won(base * 0.40) to "단기 1~2년 40%"
            else -> progressiveTax(base) to "기본세율(6~45% 누진)"
        }
        val local = won(tax * 0.1)
        return Transfer(gain, ltPct, lt, basic, base, label, tax, local, tax + local, r2((tax + local) * 100.0 / gain), notes)
    }

    data class AfterTaxReturn(
        val entity: String,
        val annualPreTaxIncomeWon: Long,
        val annualTaxWon: Long,
        val annualAfterTaxIncomeWon: Long,
        val cashOnCashPreTaxPct: Double,
        val cashOnCashAfterTaxPct: Double,
        val exitTaxWon: Long,
        val totalTaxWon: Long,
        val irrPreTaxPct: Double?,
        val irrAfterTaxPct: Double?,
        /** 세금이 깎는 IRR(%p). */
        val taxDragPct: Double?,
        val notes: List<String>,
    )

    /**
     * 세후 수익률 - 운영 소득(NOI - 이자)에 매년 세금, 매각 시 양도 과세를 적용한 자기자본 IRR.
     * 개인은 다른 소득이 없다고 가정한 종합소득세율, 법인은 법인세율(지방소득세 10% 포함). 감가상각·필요경비는 반영하지 않는다(보수적).
     */
    fun afterTaxReturn(
        purchaseWon: Long,
        equityWon: Long,
        annualNoiWon: Long,
        annualInterestWon: Long,
        saleWon: Long,
        holdYears: Int,
        corporate: Boolean,
        acquisitionCostWon: Long = 0,
    ): AfterTaxReturn {
        require(purchaseWon > 0 && equityWon > 0 && saleWon > 0 && holdYears in 1..MAX_HOLD_YEARS) { "매입가·자기자본·매각가·보유기간(1~${MAX_HOLD_YEARS}년)을 확인해 주세요." }
        requireWon(purchaseWon, equityWon, annualNoiWon, annualInterestWon, saleWon, acquisitionCostWon)
        val preTax = annualNoiWon - annualInterestWon
        val annualTax = if (corporate) won(corporateTax(max(0L, preTax)) * 1.1) else won(progressiveTax(max(0L, preTax)) * 1.1)
        val afterTax = preTax - annualTax
        val gain = saleWon - purchaseWon - acquisitionCostWon
        val exitTax = when {
            gain <= 0 -> 0L
            corporate -> won(corporateTax(gain) * 1.1)
            else -> transferIndividual(purchaseWon, saleWon, acquisitionCostWon, holdYears.toDouble()).totalWon
        }
        val loan = purchaseWon + acquisitionCostWon - equityWon
        val pre = DoubleArray(holdYears + 1) { if (it == 0) -equityWon.toDouble() else preTax.toDouble() }
        val post = DoubleArray(holdYears + 1) { if (it == 0) -equityWon.toDouble() else afterTax.toDouble() }
        pre[holdYears] += (saleWon - loan).toDouble()
        post[holdYears] += (saleWon - exitTax - loan).toDouble()
        val irrPre = irr(pre)
        val irrPost = irr(post)
        return AfterTaxReturn(
            entity = if (corporate) "법인" else "개인",
            annualPreTaxIncomeWon = preTax,
            annualTaxWon = annualTax,
            annualAfterTaxIncomeWon = afterTax,
            cashOnCashPreTaxPct = r2(preTax * 100.0 / equityWon),
            cashOnCashAfterTaxPct = r2(afterTax * 100.0 / equityWon),
            exitTaxWon = exitTax,
            totalTaxWon = annualTax * holdYears + exitTax,
            irrPreTaxPct = irrPre?.let { r2(it * 100) },
            irrAfterTaxPct = irrPost?.let { r2(it * 100) },
            taxDragPct = if (irrPre != null && irrPost != null) r2((irrPre - irrPost) * 100) else null,
            notes = listOf(
                if (corporate) "법인세 10~25% 누진 + 지방소득세 10%. 토지 등 양도소득 추가과세는 제외." else "다른 소득이 없다고 가정한 종합소득세 + 지방소득세 10%.",
                "감가상각·필요경비 미반영(보수적). 실제 세액은 세무 전문가 확인이 필요합니다.",
            ),
        )
    }

    /** 이분법 IRR. 부호가 바뀌는 해가 없으면 null. */
    fun irr(cf: DoubleArray): Double? {
        fun npv(r: Double) = cf.indices.sumOf { cf[it] / (1 + r).pow(it) }
        var lo = -0.99
        var hi = 1.0
        if (npv(lo) * npv(hi) > 0) return null
        repeat(200) {
            val mid = (lo + hi) / 2
            if (npv(lo) * npv(mid) <= 0) hi = mid else lo = mid
        }
        return (lo + hi) / 2
    }

    /** 공개(무인증) 계산기 입력 상한 - 배열 크기·오버플로를 막는다(보유 50년, 금액 절대값 100조원). */
    const val MAX_HOLD_YEARS = 50
    private const val MAX_ABS_WON = 100_000_000_000_000L

    private fun requireWon(vararg values: Long) =
        require(values.all { it in -MAX_ABS_WON..MAX_ABS_WON }) { "금액이 너무 큽니다(100조원 이하로 입력해 주세요)." }

    private const val BASIC_DEDUCTION = 2_500_000L
    private fun won(v: Double): Long = v.roundToLong()
    private fun floor(v: Double) = kotlin.math.floor(v)
    private fun r2(v: Double) = (v * 100).roundToLong() / 100.0
}
