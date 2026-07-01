package com.aixnative.underwriting

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import com.aixnative.underwriting.domain.GuidelineEvaluator
import com.aixnative.underwriting.domain.ProFormaCalculator

/** 가이드라인 적합성 코드 판정 검증 — 임계값 대조가 결정론적으로 동작하는지. */
class GuidelineEvaluatorTest {

    private fun statusOf(checks: List<GuidelineEvaluator.Check>, metric: String) =
        checks.first { it.metric.startsWith(metric) }.status

    @Test
    fun `LTV 임계값 - 65 이하 PASS, 70 초과 FAIL`() {
        val low = ProFormaCalculator.Inputs(
            askingPriceEok = 1000.0, noiEok = 55.0, ltvPct = 50.0,
            loanRatePct = 3.5, exitCapPct = 5.0,
        )
        val high = low.copy(ltvPct = 80.0)

        val lowSummary = GuidelineEvaluator.evaluate(low, ProFormaCalculator.compute(low))
        val highSummary = GuidelineEvaluator.evaluate(high, ProFormaCalculator.compute(high))

        assertEquals(GuidelineEvaluator.Status.PASS, statusOf(lowSummary.checks, "LTV"))
        assertEquals(GuidelineEvaluator.Status.FAIL, statusOf(highSummary.checks, "LTV"))
    }

    @Test
    fun `보수성 - Going-in Cap 이 Exit Cap 보다 높으면 WARN`() {
        // Going-in 5.5%(55/1000) > Exit 5.0% → 가치상승 가정(공격적) → WARN
        val input = ProFormaCalculator.Inputs(
            askingPriceEok = 1000.0, noiEok = 55.0, ltvPct = 50.0,
            loanRatePct = 3.5, exitCapPct = 5.0,
        )
        val summary = GuidelineEvaluator.evaluate(input, ProFormaCalculator.compute(input))
        assertEquals(GuidelineEvaluator.Status.WARN, statusOf(summary.checks, "보수성"))
    }

    @Test
    fun `종합 카운트 - PASS WARN FAIL 합이 체크 수와 일치`() {
        val input = ProFormaCalculator.Inputs(
            askingPriceEok = 1000.0, noiEok = 55.0, ltvPct = 50.0,
            loanRatePct = 3.5, exitCapPct = 5.0,
        )
        val s = GuidelineEvaluator.evaluate(input, ProFormaCalculator.compute(input))
        assertEquals(s.checks.size, s.pass + s.warn + s.fail)
        assertTrue(s.checks.isNotEmpty())
    }
}
