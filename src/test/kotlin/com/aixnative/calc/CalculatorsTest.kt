package com.aixnative.calc

import com.aixnative.calc.domain.HoldingCostCalculator
import com.aixnative.calc.domain.TaxCalculator
import com.aixnative.underwriting.domain.ProFormaCalculator
import com.aixnative.underwriting.domain.SensitivityAnalyzer
import com.aixnative.underwriting.service.ReportDocxWriter
import org.apache.poi.xwpf.usermodel.XWPFDocument
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CalculatorsTest {

    @Test
    fun `acquisition tax for commercial property is 4_6 percent`() {
        val a = TaxCalculator.acquisition(10_000_000_000)
        assertEquals(400_000_000, a.acquisitionTaxWon)
        assertEquals(40_000_000, a.eduTaxWon)
        assertEquals(20_000_000, a.ruralTaxWon)
        assertEquals(460_000_000, a.totalWon)
        assertEquals(4.6, a.effectivePct)
        assertFailsWith<IllegalArgumentException> { TaxCalculator.acquisition(0) }
    }

    @Test
    fun `progressive tax uses the cumulative deduction, not a flat top rate`() {
        // 과표 1억: 1억 x 35% - 1,544만 = 1,956만 (레거시는 3,500만으로 계산했다)
        assertEquals(19_560_000, TaxCalculator.progressiveTax(100_000_000))
        assertEquals(840_000, TaxCalculator.progressiveTax(14_000_000))
        assertEquals(0, TaxCalculator.progressiveTax(-5))
    }

    @Test
    fun `corporate tax brackets are continuous at the boundaries`() {
        assertEquals(20_000_000, TaxCalculator.corporateTax(200_000_000))
        assertEquals(20_000_000 + (1_000_000_000 - 200_000_000) / 5, TaxCalculator.corporateTax(1_000_000_000))
    }

    @Test
    fun `transfer tax applies long-term deduction and short-term flat rates`() {
        // 10년 보유: 장기보유특별공제 6% + 2%p x 7 = 20%
        val long = TaxCalculator.transferIndividual(1_000_000_000, 1_500_000_000, 0, 10.0)
        assertEquals(500_000_000, long.gainWon)
        assertEquals(20.0, long.longTermDeductionPct)
        assertEquals(100_000_000, long.longTermDeductionWon)
        assertEquals(397_500_000, long.taxBaseWon)
        assertEquals(TaxCalculator.progressiveTax(397_500_000), long.incomeTaxWon)
        assertEquals(long.incomeTaxWon + long.incomeTaxWon / 10, long.totalWon)

        val short = TaxCalculator.transferIndividual(1_000_000_000, 1_100_000_000, 0, 0.5)
        assertEquals("단기 1년 미만 50%", short.rateLabel)
        assertEquals((100_000_000 - 2_500_000) / 2, short.incomeTaxWon)

        assertEquals(0, TaxCalculator.transferIndividual(1_000_000_000, 900_000_000, 0, 5.0).totalWon)
        assertEquals(30.0, TaxCalculator.transferIndividual(1, 2_000_000_000, 0, 20.0).longTermDeductionPct)
    }

    @Test
    fun `after-tax irr is below pre-tax irr`() {
        val r = TaxCalculator.afterTaxReturn(
            purchaseWon = 10_000_000_000, equityWon = 5_000_000_000, annualNoiWon = 500_000_000,
            annualInterestWon = 200_000_000, saleWon = 12_000_000_000, holdYears = 5, corporate = true,
        )
        val pre = requireNotNull(r.irrPreTaxPct)
        val post = requireNotNull(r.irrAfterTaxPct)
        assertTrue(post < pre, "세후 $post < 세전 $pre")
        assertEquals(6.0, r.cashOnCashPreTaxPct)
        assertTrue((r.taxDragPct ?: 0.0) > 0)
    }

    @Test
    fun `public calculator inputs are bounded (no huge arrays or overflow)`() {
        assertFailsWith<IllegalArgumentException> {
            TaxCalculator.afterTaxReturn(1, 1, 0, 0, 2, holdYears = 2_000_000_000, corporate = false)
        }
        assertFailsWith<IllegalArgumentException> { TaxCalculator.acquisition(Long.MAX_VALUE) }
        assertFailsWith<IllegalArgumentException> { TaxCalculator.transferIndividual(1, 2, 0, Double.NaN) }
    }

    @Test
    fun `land tax uses separate-aggregate brackets and jongbu only above 8 billion`() {
        // 공시지가 1,000만원/㎡ x 300㎡ = 30억 → 과표 21억 → 280만 + 11억 x 0.4% = 720만
        val land = HoldingCostCalculator.landTax(10_000_000.0, 300.0)
        assertEquals(2_100_000_000, land.taxBaseWon)
        assertEquals(7_200_000, land.propertyTaxWon)
        assertEquals(2_940_000, land.urbanTaxWon)
        assertEquals(1_440_000, land.eduTaxWon)
        assertFalse(HoldingCostCalculator.jongbu(10_000_000.0, 300.0).applicable)
        assertTrue(HoldingCostCalculator.jongbu(10_000_000.0, 1_000.0).applicable) // 100억
    }

    @Test
    fun `light steel structure is not treated as reinforced concrete`() {
        assertEquals(0.80, HoldingCostCalculator.structureIndex("경량철골구조"))
        assertEquals(1.00, HoldingCostCalculator.structureIndex("철근콘크리트구조"))
        val r = HoldingCostCalculator.estimate(null, null, 1_000.0, null, "업무시설", 2016, 2026)
        assertEquals(null, r.land)
        assertTrue(r.annualTotalWon > 0)
        assertTrue(r.assumptions.any { it.contains("구조 미입력") })
    }

    @Test
    fun `sensitivity grid has 27 points around the base irr and a sorted tornado`() {
        val base = ProFormaCalculator.Inputs(askingPriceEok = 1000.0, noiEok = 50.0, ltvPct = 55.0, loanRatePct = 4.3, exitCapPct = 5.0)
        val s = SensitivityAnalyzer.analyze(base)

        assertEquals(27, s.grid.size)
        assertEquals(ProFormaCalculator.compute(base).leveredIrrPct, s.baseIrrPct)
        assertTrue(s.grid.any { it.leveredIrrPct == s.baseIrrPct })
        assertEquals(s.tornado.sortedByDescending { it.spreadPct }, s.tornado)
        assertTrue(s.p10IrrPct <= s.p50IrrPct && s.p50IrrPct <= s.p90IrrPct)
        // 불리한 쪽(Exit Cap +10%)은 IRR 을 낮춘다.
        val cap = s.tornado.first { it.variable == "Exit Cap" }
        assertTrue(cap.lowIrrPct < cap.highIrrPct)
    }

    @Test
    fun `report html becomes a readable docx`() {
        val html = """<html><body><h1>역삼 오피스</h1><p>요약 <strong>GO</strong></p>
            <ul><li>리스크 하나</li><li>리스크 둘</li></ul>
            <table class='grid'><thead><tr><th>연차</th><th>NOI</th></tr></thead><tbody><tr><td>1</td><td>50</td></tr></tbody></table>
            <script>alert(1)</script></body></html>"""
        val bytes = ReportDocxWriter.write(html)

        XWPFDocument(ByteArrayInputStream(bytes)).use { doc ->
            val text = doc.paragraphs.joinToString("\n") { it.text }
            assertTrue(text.contains("역삼 오피스"))
            assertTrue(text.contains("• 리스크 둘"))
            assertFalse(text.contains("alert"))
            assertEquals(1, doc.tables.size)
            assertEquals("NOI", doc.tables[0].getRow(0).getCell(1).text)
            assertEquals("50", doc.tables[0].getRow(1).getCell(1).text)
        }
    }
}
