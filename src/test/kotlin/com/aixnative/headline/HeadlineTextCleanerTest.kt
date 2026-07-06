package com.aixnative.headline

import com.aixnative.headline.service.HeadlineTextCleaner
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HeadlineTextCleanerTest {

    @Test
    fun `outletOf strips HEADLINE prefix`() {
        assertEquals("코어비트", HeadlineTextCleaner.outletOf("HEADLINE:코어비트"))
        assertEquals("SPI", HeadlineTextCleaner.outletOf("HEADLINE:SPI"))
    }

    @Test
    fun `clean strips trailing outlet suffix`() {
        assertEquals(
            "ABL생명, 여의도 본사 사옥 매각 추진",
            HeadlineTextCleaner.clean("ABL생명, 여의도 본사 사옥 매각 추진 - 코어비트", "HEADLINE:코어비트"),
        )
    }

    @Test
    fun `clean drops homepage entry equal to outlet`() {
        assertNull(HeadlineTextCleaner.clean("코어비트 - 코어비트", "HEADLINE:코어비트"))
    }

    @Test
    fun `clean drops site name and section pages starting with outlet`() {
        assertNull(HeadlineTextCleaner.clean("SPI - 상업용 부동산 콘텐츠 & 데이터 애널리틱스", "HEADLINE:SPI"))
        assertNull(HeadlineTextCleaner.clean("SPI PRO - 프로들의 상업용 부동산 아티클", "HEADLINE:SPI"))
    }

    @Test
    fun `clean drops non-korean navigation titles`() {
        assertNull(HeadlineTextCleaner.clean("Home - Seoul Property Insight - SPI", "HEADLINE:SPI"))
    }

    @Test
    fun `clean drops too short titles`() {
        assertNull(HeadlineTextCleaner.clean("매각 - 딜사이트", "HEADLINE:딜사이트"))
    }

    @Test
    fun `clean keeps a normal korean article title from dealsite`() {
        assertEquals(
            "한강에셋, 원엑스 1.64조 규모 PF 완료",
            HeadlineTextCleaner.clean("한강에셋, 원엑스 1.64조 규모 PF 완료 - 딜사이트", "HEADLINE:딜사이트"),
        )
    }
}
