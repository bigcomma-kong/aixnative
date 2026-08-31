package com.aixnative.billing

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import com.aixnative.billing.domain.ToolPricing

class ToolPricingTest {

    @Test
    fun `tiered costs match the value-based schedule`() {
        // 1 - light / on-ramp
        assertEquals(1, ToolPricing.costOf("UNDERWRITING_GUIDE"))
        assertEquals(1, ToolPricing.costOf("SCREENING"))
        assertEquals(1, ToolPricing.costOf("LEASE_EXTRACT"))
        // 2 - standard
        assertEquals(2, ToolPricing.costOf("MARKET_STUDY"))
        assertEquals(2, ToolPricing.costOf("COUNTERPARTY_DD"))
        assertEquals(2, ToolPricing.costOf("PRICE_FORECAST"))
        assertEquals(2, ToolPricing.costOf("NOTICE_EXTRACT"))
        assertEquals(2, ToolPricing.costOf("NOTICE_COMPARE"))
        assertEquals(2, ToolPricing.costOf("CONTRACT_REVISE"))
        // 3 - core
        assertEquals(3, ToolPricing.costOf("UNDERWRITING"))
        assertEquals(3, ToolPricing.costOf("DEV_FEASIBILITY"))
        assertEquals(3, ToolPricing.costOf("CONTRACT_REVIEW"))
        assertEquals(3, ToolPricing.costOf("CONTRACT_SET_COMPARE"))
        // 5 - premium
        assertEquals(5, ToolPricing.costOf("IC_MEMO"))
        assertEquals(5, ToolPricing.costOf("BOV"))
        assertEquals(5, ToolPricing.costOf("MARKET_RESEARCH_DEEP"))
        assertEquals(5, ToolPricing.costOf("MARKET_DEEP_REPORT"))
        assertEquals(5, ToolPricing.costOf("PM_AM_REPORT"))
    }

    @Test
    fun `unknown tool falls back to default cost (never free)`() {
        assertEquals(ToolPricing.DEFAULT_COST, ToolPricing.costOf("SOMETHING_NEW"))
        assertEquals(2, ToolPricing.DEFAULT_COST)
    }

    /**
     * 크기 단언은 "새 도구를 추가하면서 단가 등록을 잊는 것"을 잡는 장치다.
     * 미등록 키는 DEFAULT_COST 로 조용히 폴백해 버려서, 이 단언이 없으면 값싸게 팔리는 것을 아무도 모른다.
     * 도구를 추가할 때는 이 숫자도 함께 올린다.
     */
    @Test
    fun `all returns the full price table`() {
        val all = ToolPricing.all()
        assertEquals(23, all.size)
        assertEquals(5, all["BOV"])
        assertEquals(3, all["CONTRACT_REVIEW"])
        assertEquals(2, all["NOTICE_EXTRACT"])
    }
}
