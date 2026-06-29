package com.aixnative.marketfeed.ingest

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NewsTextFilterTest {

    private fun item(title: String, summary: String = "", loose: Boolean = false, sector: String? = null) =
        NewsItem(title = title, summary = summary, link = "https://x/y", publishedAt = null, source = "T", loose = loose, sectorHint = sector)

    @Test
    fun `normalizeLink strips protocol query fragment and lowercases`() {
        assertEquals(
            "news.site/a/b",
            NewsTextFilter.normalizeLink("HTTPS://News.Site/a/b/?utm=x#frag"),
        )
    }

    @Test
    fun `normalizeLink collapses duplicate of same article`() {
        val a = NewsTextFilter.normalizeLink("http://n.com/article/1?ref=rss")
        val b = NewsTextFilter.normalizeLink("https://n.com/article/1#top")
        assertEquals(a, b)
    }

    @Test
    fun `stripHtml removes tags and entities`() {
        assertEquals("A & B", NewsTextFilter.stripHtml("<p>A &amp; <b>B</b></p>"))
    }

    @Test
    fun `trusted source passes without anchor`() {
        assertTrue(NewsTextFilter.isRelevant(item("아무 제목", loose = false)))
    }

    @Test
    fun `loose source requires real estate anchor`() {
        assertFalse(NewsTextFilter.isRelevant(item("그냥 일반 기사", loose = true)))
        assertTrue(NewsTextFilter.isRelevant(item("오피스 빌딩 매각 우선협상", loose = true)))
    }

    @Test
    fun `noise is filtered even from trusted source`() {
        assertFalse(NewsTextFilter.isRelevant(item("프로야구 개막 빌딩 옆 경기장", loose = false)))
    }

    @Test
    fun `japanese article is rejected by korean gate`() {
        // 조선비즈 일본판 기사(가나 포함) — 한국어 게이트로 차단.
        assertFalse(NewsTextFilter.isKorean("韓国バイオヘルス上場業績改善も二極化進む"))
        assertFalse(NewsTextFilter.isRelevant(item("韓国バイオヘルス上場業績改善も二極化進む")))
    }

    @Test
    fun `chinese only title without hangul is rejected`() {
        assertFalse(NewsTextFilter.isKorean("韓国不動産市場分析報告"))
    }

    @Test
    fun `korean title passes gate`() {
        assertTrue(NewsTextFilter.isKorean("강남 프라임 오피스 매각"))
    }

    @Test
    fun `sector hint maps to canonical asset type`() {
        assertEquals("물류", NewsTextFilter.classifyAssetType(item("창고 거래", sector = "logistics")))
        assertEquals("호텔", NewsTextFilter.classifyAssetType(item("매각", sector = "hotel")))
    }

    @Test
    fun `keyword classification when no sector hint`() {
        assertEquals("오피스", NewsTextFilter.classifyAssetType(item("강남 프라임 사옥 매각")))
        assertNull(NewsTextFilter.classifyAssetType(item("금리 동향 일반 기사")))
    }

    @Test
    fun `guessLocation finds seoul district`() {
        assertTrue(NewsTextFilter.guessLocation(item("서울 강남구 역삼동 빌딩 매각"))!!.startsWith("서울"))
    }
}
