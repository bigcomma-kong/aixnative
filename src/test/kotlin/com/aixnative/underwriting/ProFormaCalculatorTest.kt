package com.aixnative.underwriting

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 순수 계산 검증. */
class ProFormaCalculatorTest {

    // 매입구조 검산용 — 결정론적 산수 확인(딜의 수익성과 무관).
    private val structureCase = ProFormaCalculator.Inputs(
        askingPriceEok = 6800.0,
        noiEok = 270.0,
        ltvPct = 55.0,
        loanRatePct = 4.3,
        holdYears = 5,
        rentGrowthPct = 3.0,
        exitCapPct = 4.75,
        acqCostPct = 4.6,
        saleCostPct = 1.5,
    )

    // 수익성 검증용 — Exit Cap(5.0) < Going-in Cap(5.5)로 가치 상승이 기대되는 딜.
    private val profitableDeal = ProFormaCalculator.Inputs(
        askingPriceEok = 1000.0,
        noiEok = 55.0,
        ltvPct = 50.0,
        loanRatePct = 3.5,
        holdYears = 5,
        rentGrowthPct = 3.0,
        exitCapPct = 5.0,
        acqCostPct = 4.6,
        saleCostPct = 1.5,
    )

    @Test
    fun `매입구조 검산 - 총투자비 대출 에쿼티 연이자`() {
        val r = ProFormaCalculator.compute(structureCase)
        assertEquals(7112.8, r.totalInvestEok, 0.05) // 6800 * 1.046
        assertEquals(3740.0, r.debtEok, 0.05)        // 6800 * 0.55
        assertEquals(3372.8, r.equityEok, 0.05)      // 7112.8 - 3740
        assertEquals(160.8, r.annualInterestEok, 0.05) // 3740 * 0.043
    }

    @Test
    fun `수익성 딜 - IRR 양수 EM 1 초과 going-in cap 정확`() {
        val r = ProFormaCalculator.compute(profitableDeal)
        assertEquals(5, r.proForma.size)
        assertTrue(r.leveredIrrPct > 0, "Levered IRR 양수여야: ${r.leveredIrrPct}")
        assertTrue(r.equityMultiple > 1.0, "EM > 1: ${r.equityMultiple}")
        assertEquals(5.5, r.goingInCapPct, 0.05) // 55 / 1000
        assertTrue(r.exitCapSensitivity.isNotEmpty())
    }

    @Test
    fun `시나리오 - 하방 기준 상방 3종 산출 + 상방 우위`() {
        val s = ProFormaCalculator.scenarios(profitableDeal)
        assertEquals(listOf("하방", "기준", "상방"), s.map { it.name })
        val base = s.first { it.name == "기준" }
        val up = s.first { it.name == "상방" }
        // 상방(임대성장↑·Exit Cap↓)은 기준보다 IRR 이 높아야 한다.
        assertTrue(up.leveredIrrPct >= base.leveredIrrPct, "상방 IRR(${up.leveredIrrPct}) ≥ 기준(${base.leveredIrrPct})")
    }
}
