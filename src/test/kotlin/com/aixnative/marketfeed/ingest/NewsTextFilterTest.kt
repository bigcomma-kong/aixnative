package com.aixnative.marketfeed.ingest

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import com.aixnative.marketfeed.domain.NewsItem
import com.aixnative.marketfeed.service.NewsTextFilter

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
    fun `real estate context alone is not a deal`() {
        // 시황·정책·오피니언 — 부동산 컨텍스트는 있으나 거래 시그널이 없어 딜 아님(소스 무관).
        assertFalse(NewsTextFilter.isRelevant(item("강남 오피스 공실률 상승, 임대료 약세", loose = false)))
        assertFalse(NewsTextFilter.isRelevant(item("아무 제목", loose = false)))
    }

    @Test
    fun `deal signal alone without context is rejected`() {
        assertFalse(NewsTextFilter.isRelevant(item("스타트업 지분 인수 소식", loose = true)))
    }

    @Test
    fun `context plus deal signal passes from any source`() {
        assertTrue(NewsTextFilter.isRelevant(item("오피스 빌딩 매각 우선협상", loose = true)))
        assertTrue(NewsTextFilter.isRelevant(item("○○자산운용, 강남 프라임 오피스 6800억에 매각", loose = false)))
        assertTrue(NewsTextFilter.isRelevant(item("물류센터 매각 우선협상대상자 선정", loose = true)))
    }

    @Test
    fun `macro opinion column is rejected`() {
        // 사용자 지적 사례 — 축구 메타포 + 골목상권 부실대출 매크로 칼럼(딜 아님).
        assertFalse(NewsTextFilter.isRelevant(item("‘홍명보라도 왔으면’…부실대출 눈덩이처럼 불어나는 ‘골목상권’", loose = true)))
    }

    @Test
    fun `noise is filtered even with deal-like words`() {
        assertFalse(NewsTextFilter.isRelevant(item("프로야구 개막, 구장 인수 우선협상 빌딩", loose = false)))
    }

    @Test
    fun `celebrity lifestyle article is rejected`() {
        // 사용자 지적 사례 — 연예/육아 휴먼 인터레스트(부동산·거래 시그널 없음).
        assertFalse(
            NewsTextFilter.isRelevant(
                item(
                    "남보라, 출산 후 젖몸살→기미 생겼는데..‘아기 낳길 잘해’ 꿀 뚝뚝 (인생극장)[순간포착]",
                    "영상 시청 후 작성된 리뷰 기사입니다. 배우 남보라가 현실 육아를 토로하면서도 출산에 대해 후회하지 않은 선택이라고 밝혔다.",
                ),
            ),
        )
    }

    @Test
    fun `celebrity gossip with real estate keywords is still rejected`() {
        // 컨텍스트(빌딩)+거래 시그널(매입)을 우연히 갖춘 연예 가십도 투자 딜이 아니므로 차단.
        assertFalse(NewsTextFilter.isRelevant(item("배우 남보라, 강남 프라임 빌딩 100억 매입 화제")))
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

    @Test
    fun `guessLocation does not clip landmark onto city name`() {
        // '에버랜드'는 행정구역(구/시/동)이 아니므로 도시명만 남아야 한다('용인 에버랜' 잘림 방지).
        assertEquals("용인", NewsTextFilter.guessLocation(item("용인 에버랜드 인근 물류센터 매각")))
    }

    @Test
    fun `guessLocation keeps full administrative district`() {
        assertEquals("성남 분당구", NewsTextFilter.guessLocation(item("성남 분당구 오피스 거래")))
    }
}
