package com.aixnative.underwriting

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToLong

/**
 * 5년 Pro Forma 결정론적 계산 (cre-underwriting-kr 한국화).
 * 단위: 억원(KRW). 모든 수치는 코드로 계산 — AI 미사용(환각 차단, 투자수치 신뢰).
 *
 * 검산: 매가 6800·취득 4.6% → 총투자비 7112.8, LTV 55% → 대출 3740·Equity 3372.8,
 *       금리 4.3% → 연이자 160.8.
 */
object ProFormaCalculator {

    /** 입력 가정 (단위: 억원, %, 년) */
    data class Inputs(
        val askingPriceEok: Double,
        val noiEok: Double,
        val ltvPct: Double,
        val loanRatePct: Double,
        val holdYears: Int = 5,
        val rentGrowthPct: Double = 3.0,
        val exitCapPct: Double,
        // 취득부대비용률 = 취득세 + 등기 (가이드라인 기본). 매각비용률 = 가이드라인 하한.
        val acqCostPct: Double = CreGuidelines.ACQ_TAX_PCT + CreGuidelines.REGISTRATION_PCT,
        val saleCostPct: Double = CreGuidelines.SALE_COST_MIN_PCT,
        val capexPerYearEok: Double = 0.0,
    )

    data class YearRow(
        val year: Int, val noi: Double, val interest: Double, val capex: Double,
        val leveredCf: Double, val dscr: Double, val cocPct: Double,
    )

    data class Sensitivity(val exitCapPct: Double, val saleValueEok: Double, val leveredIrrPct: Double, val em: Double)

    /** 시나리오(하방/기준/상방) — 임대성장률·Exit Cap·금리 변동 동시 적용한 케이스 요약 */
    data class Scenario(
        val name: String, val rentGrowthPct: Double, val exitCapPct: Double,
        val leveredIrrPct: Double, val equityMultiple: Double, val minDscr: Double, val exitValueEok: Double,
    )

    data class Result(
        val totalInvestEok: Double, val equityEok: Double, val debtEok: Double, val annualInterestEok: Double,
        val proForma: List<YearRow>,
        val exitCapPct: Double, val exitNoiEok: Double, val exitValueEok: Double,
        val netSaleEok: Double, val exitEquityEok: Double,
        val leveredIrrPct: Double, val equityMultiple: Double, val unleveredIrrPct: Double,
        val goingInCapPct: Double, val yieldOnCostPct: Double,
        val exitCapSensitivity: List<Sensitivity>,
    )

    fun compute(input: Inputs): Result {
        val total = input.askingPriceEok * (1 + input.acqCostPct / 100.0)
        val debt = input.askingPriceEok * input.ltvPct / 100.0   // 대출은 매입가 기준(관행)
        val equity = total - debt
        val interest = debt * input.loanRatePct / 100.0          // 일시상환 → 매년 동일

        val n = max(1, input.holdYears)
        val rows = ArrayList<YearRow>()
        val equityCf = DoubleArray(n + 1)
        val unleveredCf = DoubleArray(n + 1)
        equityCf[0] = -equity
        unleveredCf[0] = -total

        var noi = input.noiEok
        for (y in 1..n) {
            if (y > 1) noi *= (1 + input.rentGrowthPct / 100.0)
            val capex = input.capexPerYearEok
            val leveredCf = noi - interest - capex
            val dscr = if (interest > 0) noi / interest else 0.0
            val coc = if (equity > 0) leveredCf / equity * 100.0 else 0.0
            rows.add(YearRow(y, r(noi), r(interest), r(capex), r(leveredCf), r2(dscr), r2(coc)))
            equityCf[y] = leveredCf
            unleveredCf[y] = noi - capex
        }

        // Exit — 보유 마지막 해 forward NOI 기준
        val exitNoi = noi * (1 + input.rentGrowthPct / 100.0)
        val exitValue = if (input.exitCapPct > 0) exitNoi / (input.exitCapPct / 100.0) else 0.0
        val netSale = exitValue * (1 - input.saleCostPct / 100.0)
        val exitEquity = netSale - debt
        equityCf[n] += exitEquity
        unleveredCf[n] += netSale

        val irr = irr(equityCf) * 100.0
        val unlevIrr = irr(unleveredCf) * 100.0

        var totalReturn = exitEquity
        for (row in rows) totalReturn += row.leveredCf
        val em = if (equity > 0) totalReturn / equity else 0.0
        val goingInCap = if (input.askingPriceEok > 0) input.noiEok / input.askingPriceEok * 100.0 else 0.0
        val yoc = if (total > 0) input.noiEok / total * 100.0 else 0.0

        // 민감도 — Exit Cap 변동
        val sens = ArrayList<Sensitivity>()
        val base = input.exitCapPct
        for (cap in doubleArrayOf(base - 0.25, base, base + 0.25, base + 0.5)) {
            if (cap <= 0) continue
            val ev = exitNoi / (cap / 100.0)
            val ns = ev * (1 - input.saleCostPct / 100.0)
            val ee = ns - debt
            val cf = equityCf.copyOf()
            cf[n] = rows[n - 1].leveredCf + ee
            val sIrr = irr(cf) * 100.0
            var sRet = ee
            for (row in rows) sRet += row.leveredCf
            val sEm = if (equity > 0) sRet / equity else 0.0
            sens.add(Sensitivity(r2(cap), r(ns), r2(sIrr), r2(sEm)))
        }

        return Result(
            r(total), r(equity), r(debt), r(interest), rows,
            r2(input.exitCapPct), r(exitNoi), r(exitValue), r(netSale), r(exitEquity),
            r2(irr), r2(em), r2(unlevIrr), r2(goingInCap), r2(yoc), sens,
        )
    }

    /**
     * 시나리오 분석 — 기준 가정 대비 하방/기준/상방 케이스를 동일 엔진으로 재계산.
     * 두 핵심 IRR 동인(임대성장률·Exit Cap) + 하방의 금리 상승을 동시 적용 (결정론, AI 미사용).
     */
    fun scenarios(base: Inputs): List<Scenario> = listOf(
        scenario("하방", base, -1.5, +0.5, +0.5),
        scenario("기준", base, 0.0, 0.0, 0.0),
        scenario("상방", base, +1.0, -0.25, 0.0),
    )

    private fun scenario(name: String, b: Inputs, dRent: Double, dExit: Double, dRate: Double): Scenario {
        val rent = b.rentGrowthPct + dRent
        val exit = max(0.1, b.exitCapPct + dExit)
        val rate = max(0.0, b.loanRatePct + dRate)
        val result = compute(b.copy(loanRatePct = rate, rentGrowthPct = rent, exitCapPct = exit))
        val minDscr = result.proForma.map { it.dscr }.filter { it > 0 }.minOrNull() ?: 0.0
        return Scenario(name, r2(rent), r2(exit), result.leveredIrrPct, result.equityMultiple, r2(minDscr), result.exitValueEok)
    }

    /** IRR — 이분법 (연간 현금흐름, 연 단위) */
    private fun irr(cf: DoubleArray): Double {
        val lo0 = -0.9
        var hi = 1.0
        var fLo = npv(cf, lo0)
        var fHi = npv(cf, hi)
        var guard = 0
        while (fLo * fHi > 0 && hi < 10 && guard++ < 100) {
            hi += 0.5
            fHi = npv(cf, hi)
        }
        if (fLo * fHi > 0) return 0.0 // 수렴 실패 → 0 (서비스에서 신뢰도 표기)
        var lo = lo0
        for (i in 0 until 200) {
            val mid = (lo + hi) / 2.0
            val fMid = npv(cf, mid)
            if (abs(fMid) < 1e-7) return mid
            if (fLo * fMid < 0) hi = mid else { lo = mid; fLo = fMid }
        }
        return (lo + hi) / 2.0
    }

    private fun npv(cf: DoubleArray, rate: Double): Double {
        var npv = 0.0
        for (t in cf.indices) npv += cf[t] / (1 + rate).pow(t.toDouble())
        return npv
    }

    private fun r(v: Double): Double = (v * 10.0).roundToLong() / 10.0
    private fun r2(v: Double): Double = (v * 100.0).roundToLong() / 100.0
}
