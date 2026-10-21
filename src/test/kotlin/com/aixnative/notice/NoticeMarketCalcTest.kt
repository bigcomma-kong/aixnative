package com.aixnative.notice

import com.aixnative.notice.domain.NoticeMarketCalc
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NoticeMarketCalcTest {

    private val mapper = ObjectMapper()

    // 33.0578㎡ = 10평. 1차 3억(3,000만원/평) → 2차 2.4억(2,400만원/평) → 3차 1.92억(1,920만원/평).
    private val extraction = mapper.readTree(
        """{"items":[{"no":"1","unit":"101호","area_m2":33.0578,"round_prices":[300000000,240000000,192000000]}]}""",
    )

    @Test
    fun `per-unit premium and first round below market are computed by code`() {
        // 시세 중위 2,500만원/평
        val c = NoticeMarketCalc.compare(extraction, medianPyeongManwon = 2500, compCount = 8)
        val u = c.units.single()

        assertEquals(25_000_000, c.marketPyeongKrw)
        assertEquals("1 101호", u.label)
        assertEquals(20.0, u.vsMarketPct) // 1차 3,000만원/평은 시세보다 20% 비싸다
        assertEquals(2, u.firstRoundBelowMarket) // 2차(2,400만원/평)부터 시세 이하
        assertEquals(3, u.roundCount)
    }

    @Test
    fun `too few comps withholds the market judgment`() {
        val c = NoticeMarketCalc.compare(extraction, medianPyeongManwon = 2500, compCount = 2)

        assertNull(c.marketPyeongKrw)
        assertNull(c.units.single().vsMarketPct)
        assertNull(c.units.single().firstRoundBelowMarket)
        assertTrue(c.note.contains("2건"))
    }

    @Test
    fun `representative fallback when there are no items`() {
        val rep = mapper.readTree("""{"target":{"name":"성수 근생"},"derived":{"pyeong_price_krw":20000000}}""")
        val c = NoticeMarketCalc.compare(rep, medianPyeongManwon = 2500, compCount = 5)

        assertEquals("성수 근생", c.units.single().label)
        assertEquals(-20.0, c.units.single().vsMarketPct)
    }

    @Test
    fun `fact block tells the model not to recompute`() {
        val block = NoticeMarketCalc.factBlock(NoticeMarketCalc.compare(extraction, 2500, 8))
        assertTrue(block.contains("다시 계산하지 말 것"))
        assertTrue(block.contains("2차부터 시세 이하"))
        assertTrue(block.contains("+20.0%"))
    }
}
