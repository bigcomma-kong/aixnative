package com.aixnative.tenant

import com.aixnative.integration.dart.domain.DartFinancials
import com.aixnative.integration.dart.service.DartClient
import com.aixnative.tenant.domain.TenantCreditCalculator
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.math.BigDecimal
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TenantCreditCalculatorTest {

    private fun b(v: Long) = BigDecimal.valueOf(v)

    /** 우량: 부채비율 50%, 유동비율 250%, 영업이익률 15%, 매출 +12%. */
    private val strong = DartFinancials(
        year = 2025, fsDiv = "CFS",
        totalAssets = b(300), totalLiabilities = b(100), totalEquity = b(200),
        currentAssets = b(250), currentLiabilities = b(100), inventory = b(50),
        revenue = b(1_000), operatingIncome = b(150), netIncome = b(100), operatingCashFlow = b(120),
        priorRevenue = b(893), priorOperatingIncome = b(130),
    )

    /** 부실: 자본잠식, 영업손실, 유동비율 50%. */
    private val weak = DartFinancials(
        year = 2025, fsDiv = "OFS",
        totalAssets = b(100), totalLiabilities = b(120), totalEquity = b(-20),
        currentAssets = b(30), currentLiabilities = b(60),
        revenue = b(100), operatingIncome = b(-10), netIncome = b(-15), operatingCashFlow = b(-5),
        priorRevenue = b(130), priorOperatingIncome = b(5),
    )

    @Test
    fun `ratios are computed from statements`() {
        val r = TenantCreditCalculator.ratios(strong)
        assertEquals(50.0, r.debtRatio)
        assertEquals(250.0, r.currentRatio)
        assertEquals(200.0, r.quickRatio)
        assertEquals(15.0, r.operatingMargin)
        assertEquals(50.0, r.roe)
        assertEquals(11.98, r.revenueGrowth)
    }

    @Test
    fun `strong company rates A with sufficient capacity, weak one rates D with risks`() {
        val sr = TenantCreditCalculator.ratios(strong)
        assertEquals("A", TenantCreditCalculator.rate(sr).grade)
        assertEquals("충분", TenantCreditCalculator.paymentCapacity(strong, sr).rating)
        assertTrue(TenantCreditCalculator.risks(strong, sr).isEmpty())

        val wr = TenantCreditCalculator.ratios(weak)
        assertEquals("D", TenantCreditCalculator.rate(wr).grade)
        assertEquals("부족", TenantCreditCalculator.paymentCapacity(weak, wr).rating)
        val risks = TenantCreditCalculator.risks(weak, wr)
        assertTrue(risks.any { it.contains("자본잠식") })
        assertTrue(risks.any { it.contains("영업손실") })
    }

    @Test
    fun `no indicators means no grade instead of a fake D`() {
        val empty = DartFinancials(year = 2025, fsDiv = "OFS", totalAssets = b(1))
        assertEquals("N/A", TenantCreditCalculator.rate(TenantCreditCalculator.ratios(empty)).grade)
    }

    @Test
    fun `scenario rates come from operating income history`() {
        val history = listOf(100L, 110L, 121L).mapIndexed { i, oi ->
            DartFinancials(year = 2023 + i, fsDiv = "CFS", totalAssets = b(1), operatingIncome = b(oi))
        }
        val rates = TenantCreditCalculator.scenarioRates(history)
        assertEquals(10.0, rates.basePct) // 매년 10% 성장
        assertTrue(rates.optimisticPct >= rates.basePct && rates.pessimisticPct <= rates.basePct)

        val fallback = TenantCreditCalculator.scenarioRates(listOf(strong))
        assertEquals(0.0, fallback.basePct)
    }

    @Test
    fun `simulation finds the year rent overtakes operating income`() {
        // 영업이익 120원(정체), 월세 5원 → 연 60원, 커버리지 2.0 에서 시작. 영업이익 성장 0%, 임대료 +10%/년.
        val flat = (2021..2025).map { DartFinancials(year = it, fsDiv = "CFS", totalAssets = b(1), operatingIncome = b(120)) }
        val sim = TenantCreditCalculator.simulate(flat.last(), flat, monthlyRentWon = 5, rentGrowthPct = 10.0)

        assertEquals(2.0, sim.currentCoverage)
        assertEquals(8, sim.breakEven["base"]) // 60 x 1.1^8 = 128.6 > 120
        assertTrue(sim.assessment.contains("8년차"))
        assertEquals(10, sim.base.size)
        assertFailsWith<IllegalArgumentException> { TenantCreditCalculator.simulate(strong, flat, 0) }
    }

    @Test
    fun `corp code zip is parsed with listed flag`() {
        val xml = """<?xml version="1.0" encoding="UTF-8"?><result>
            <list><corp_code>00126380</corp_code><corp_name>삼성전자</corp_name><stock_code>005930</stock_code><modify_date>20250101</modify_date></list>
            <list><corp_code>01234567</corp_code><corp_name>비상장주식회사</corp_name><stock_code> </stock_code><modify_date>20250101</modify_date></list>
            </result>"""
        val bytes = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { z -> z.putNextEntry(ZipEntry("CORPCODE.xml")); z.write(xml.toByteArray()); z.closeEntry() }
        }.toByteArray()

        val corps = DartClient.parseCorpCodes(bytes)
        assertEquals(2, corps.size)
        assertEquals("005930", corps[0].stockCode)
        assertNull(corps[1].stockCode)
        assertFailsWith<IllegalStateException> { DartClient.parseCorpCodes("""{"status":"010"}""".toByteArray()) }
    }
}
