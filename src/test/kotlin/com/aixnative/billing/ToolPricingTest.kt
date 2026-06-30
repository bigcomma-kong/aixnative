package com.aixnative.billing

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class ToolPricingTest {

    @Test
    fun `tiered costs match the value-based schedule`() {
        // 1 — light / on-ramp
        assertEquals(1, ToolPricing.costOf("UNDERWRITING_GUIDE"))
        assertEquals(1, ToolPricing.costOf("SCREENING"))
        // 2 — standard
        assertEquals(2, ToolPricing.costOf("MARKET_STUDY"))
        assertEquals(2, ToolPricing.costOf("COUNTERPARTY_DD"))
        assertEquals(2, ToolPricing.costOf("PRICE_FORECAST"))
        // 3 — core
        assertEquals(3, ToolPricing.costOf("UNDERWRITING"))
        assertEquals(3, ToolPricing.costOf("DEV_FEASIBILITY"))
        // 5 — premium
        assertEquals(5, ToolPricing.costOf("IC_MEMO"))
        assertEquals(5, ToolPricing.costOf("BOV"))
        assertEquals(5, ToolPricing.costOf("MARKET_RESEARCH_DEEP"))
        assertEquals(5, ToolPricing.costOf("MARKET_DEEP_REPORT"))
    }

    @Test
    fun `unknown tool falls back to default cost (never free)`() {
        assertEquals(ToolPricing.DEFAULT_COST, ToolPricing.costOf("SOMETHING_NEW"))
        assertEquals(2, ToolPricing.DEFAULT_COST)
    }

    @Test
    fun `all returns the full price table`() {
        val all = ToolPricing.all()
        assertEquals(15, all.size)
        assertEquals(5, all["BOV"])
    }
}
