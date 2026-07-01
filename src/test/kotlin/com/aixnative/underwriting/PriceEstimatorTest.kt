package com.aixnative.underwriting

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import com.aixnative.underwriting.domain.PriceEstimator

/** 가격 예측 — 소득환원·거래사례·밴드·신뢰도 검증. */
class PriceEstimatorTest {

    @Test
    fun `소득환원만 - NOI div Cap, 매입 밴드 추정가 이하`() {
        val r = PriceEstimator.compute(
            PriceEstimator.Inputs(noiEok = 100.0, marketCapPct = 5.0) // 100/0.05 = 2000
        )
        assertEquals(2000.0, r.incomeValueEok!!, 0.5)
        assertNull(r.compValueEok)
        assertEquals(2000.0, r.estimateEok, 0.5)
        assertEquals(5.0, r.impliedCapPct, 0.05)
        assertTrue(r.buyHighEok <= r.estimateEok)       // 입찰 상한 = 추정가 이하
        assertTrue(r.sellHighEok >= r.estimateEok)      // 매각 상한 = 추정가 이상
        assertEquals("MEDIUM", r.confidence)            // 한 방법
    }

    @Test
    fun `거래사례만 - 중위 평당가 x 연면적`() {
        val r = PriceEstimator.compute(
            PriceEstimator.Inputs(areaPyeong = 1000.0, compPyeongManwon = 3000.0, compCount = 5)
        ) // 3000만원 * 1000평 = 30억*100 = 300억
        assertEquals(300.0, r.compValueEok!!, 0.5)
        assertNull(r.incomeValueEok)
        assertEquals(300.0, r.estimateEok, 0.5)
        assertEquals("MEDIUM", r.confidence)            // 한 방법(거래사례만)
    }

    @Test
    fun `두 방법 + 표본 충분 - HIGH, 가중평균`() {
        val r = PriceEstimator.compute(
            PriceEstimator.Inputs(
                noiEok = 100.0, marketCapPct = 5.0,        // income 2000
                areaPyeong = 1000.0, compPyeongManwon = 1000.0, compCount = 4, // comp 100
            )
        )
        // 가중: (2000*0.6 + 100*0.4) = 1240
        assertEquals(1240.0, r.estimateEok, 1.0)
        assertEquals("HIGH", r.confidence)
    }

    @Test
    fun `입력 빈약 - LOW`() {
        val r = PriceEstimator.compute(PriceEstimator.Inputs())
        assertEquals("LOW", r.confidence)
        assertEquals(0.0, r.estimateEok, 0.01)
    }
}
