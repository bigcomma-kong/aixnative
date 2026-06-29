package com.aixnative.underwriting

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 개발 타당성 계산 + 마진 판정(GO/CONDITIONAL/NO_GO) 검증. */
class DevFeasibilityCalculatorTest {

    @Test
    fun `총사업비 = 기초 x (1 + 우발비율)`() {
        val r = DevFeasibilityCalculator.compute(
            DevFeasibilityCalculator.Inputs(
                landCostEok = 300.0, constructionCostEok = 500.0,
                financingCostEok = 100.0, otherCostEok = 100.0, contingencyPct = 5.0,
                salesRevenueEok = 1200.0,
            )
        )
        assertEquals(1000.0, r.baseCostEok, 0.05)        // 300+500+100+100
        assertEquals(1050.0, r.totalProjectCostEok, 0.05) // 1000 * 1.05
        assertEquals(150.0, r.developmentProfitEok, 0.05) // 1200 - 1050
    }

    @Test
    fun `마진 판정 - 15 이상 GO, 10~15 CONDITIONAL, 10 미만 NO_GO`() {
        assertEquals("GO", DevFeasibilityCalculator.marginVerdict(15.0))
        assertEquals("GO", DevFeasibilityCalculator.marginVerdict(22.5))
        assertEquals("CONDITIONAL", DevFeasibilityCalculator.marginVerdict(12.0))
        assertEquals("NO_GO", DevFeasibilityCalculator.marginVerdict(9.9))
    }

    @Test
    fun `임대형 - 분양수입 없으면 Stabilized Value 로 GDV 산정`() {
        val r = DevFeasibilityCalculator.compute(
            DevFeasibilityCalculator.Inputs(
                landCostEok = 300.0, constructionCostEok = 500.0,
                stabilizedNoiEok = 60.0, exitCapPct = 5.0, // → 1200
            )
        )
        assertEquals(1200.0, r.stabilizedValueEok, 0.05) // 60 / 0.05
        assertEquals(1200.0, r.grossDevelopmentValueEok, 0.05)
        assertTrue(r.yieldOnCostPct > 0)
    }
}
