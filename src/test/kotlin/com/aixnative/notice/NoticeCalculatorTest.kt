package com.aixnative.notice

import com.aixnative.notice.domain.NoticeCalculator
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 평단가·수익률은 **AI 가 아니라 코드가** 만든다. 틀린 평단가는 응찰 판단을 그대로 망치므로
 * 경계값(0·null·음수)에서 0 을 돌려주지 않고 null 을 돌려주는지까지 단언한다
 * ("0원/평"으로 표시되면 사용자가 값이 있는 줄로 오해한다).
 */
class NoticeCalculatorTest {

    @Test
    fun `평단가는 기준가를 전용평으로 나눈 값`() {
        // 84.5㎡ = 25.56평, 5억 ÷ 25.56 ≈ 19,562,000원/평
        val result = NoticeCalculator.pyeongPriceKrw(500_000_000, 84.5)
        assertTrue(result != null)
        assertTrue(abs(result - 19_562_000) < 50_000, "실제=$result")
    }

    @Test
    fun `가격이나 면적이 없으면 평단가를 만들지 않는다`() {
        assertNull(NoticeCalculator.pyeongPriceKrw(null, 84.5))
        assertNull(NoticeCalculator.pyeongPriceKrw(500_000_000, null))
        assertNull(NoticeCalculator.pyeongPriceKrw(0, 84.5))
        assertNull(NoticeCalculator.pyeongPriceKrw(500_000_000, 0.0))
        assertNull(NoticeCalculator.pyeongPriceKrw(-1, 84.5))
    }

    @Test
    fun `기준가는 1차 최저입찰가 우선, 없으면 감정가`() {
        assertEquals(400_000_000, NoticeCalculator.basePriceKrw(listOf(400_000_000, 320_000_000), 500_000_000))
        assertEquals(500_000_000, NoticeCalculator.basePriceKrw(emptyList(), 500_000_000))
        assertEquals(500_000_000, NoticeCalculator.basePriceKrw(null, 500_000_000))
        assertNull(NoticeCalculator.basePriceKrw(null, null))
    }

    @Test
    fun `저감표 첫 항목이 0이면 다음 유효값을 쓴다`() {
        assertEquals(320_000_000, NoticeCalculator.basePriceKrw(listOf(0, 320_000_000), 500_000_000))
    }

    @Test
    fun `총수익률은 연 임대료를 매입가로 나눈 백분율`() {
        // 월 300만 x 12 = 3,600만, ÷ 5억 = 7.2%
        assertEquals(7.2, NoticeCalculator.grossYieldPct(3_000_000, 500_000_000))
    }

    @Test
    fun `임대료를 모르면 수익률을 만들지 않는다`() {
        assertNull(NoticeCalculator.grossYieldPct(null, 500_000_000))
        assertNull(NoticeCalculator.grossYieldPct(0, 500_000_000))
        assertNull(NoticeCalculator.grossYieldPct(3_000_000, null))
        assertNull(NoticeCalculator.grossYieldPct(3_000_000, 0))
    }
}
