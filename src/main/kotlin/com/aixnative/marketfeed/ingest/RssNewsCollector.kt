package com.aixnative.marketfeed.ingest

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.w3c.dom.Element
import java.net.URLEncoder
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilder
import javax.xml.parsers.DocumentBuilderFactory

/**
 * 공개 RSS(부동산 매체) + 구글뉴스 섹터 딜 검색을 긁어 [NewsItem] 으로 정규화한다.
 * 키 0개. 소스별 graceful — 한 소스 실패가 전체를 막지 않는다. XML 은 XXE 방어 파서로 처리.
 *
 * MASTERN AbstractRssNewsClient/DealNewsCollector 의 소스·파싱 전략 이식(키·브랜딩 제외).
 */
@Component
class RssNewsCollector(
    private val marketDataRestClient: RestClient,
    private val props: MarketFeedProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** 모든 활성 소스에서 기사 수집(중복제거 전 원본). */
    fun collect(): List<NewsItem> {
        val out = ArrayList<NewsItem>()
        // 1) RSS. 부동산 전용 피드는 loose=false(앵커 불요), 경제/금융·종합지는 loose=true(부동산 앵커 필수).
        for ((name, url, loose) in RSS_FEEDS) {
            runCatching { out += parseFeed(fetch(url), source = "RSS:$name", loose = loose, sector = null) }
                .onFailure { log.warn("[ingest] RSS '{}' 실패: {}", name, it.message) }
        }
        // 2) 구글뉴스 섹터 딜 검색(느슨한 소스 — 앵커 게이트 적용).
        if (props.googleNewsEnabled) {
            for ((sector, query) in DEAL_QUERIES) {
                runCatching {
                    out += parseFeed(fetch(googleNewsUrl(query)), source = "GOOGLE_NEWS", loose = true, sector = sector)
                }.onFailure { log.warn("[ingest] 구글뉴스 '{}' 실패: {}", query, it.message) }
            }
        }
        return out
    }

    private fun fetch(url: String): String =
        marketDataRestClient.get().uri(url).retrieve().body(String::class.java)
            ?: throw RuntimeException("빈 응답")

    private fun googleNewsUrl(query: String): String {
        val q = URLEncoder.encode(query, Charsets.UTF_8)
        return "https://news.google.com/rss/search?q=$q&hl=ko&gl=KR&ceid=KR:ko"
    }

    /** RSS 2.0 <item> 파싱 → NewsItem. (한경/매경/조선비즈/구글뉴스 모두 RSS 2.0) */
    private fun parseFeed(xml: String, source: String, loose: Boolean, sector: String?): List<NewsItem> {
        val doc = secureDocumentBuilder().parse(xml.byteInputStream())
        val items = doc.getElementsByTagName("item")
        val result = ArrayList<NewsItem>()
        for (i in 0 until items.length) {
            val item = items.item(i) as? Element ?: continue
            val title = NewsTextFilter.stripHtml(text(item, "title"))
            val link = text(item, "link").trim()
            if (title.isBlank() || link.isBlank()) continue
            result += NewsItem(
                title = title,
                summary = NewsTextFilter.stripHtml(text(item, "description")).take(SUMMARY_MAX),
                link = link,
                publishedAt = parsePubDate(text(item, "pubDate")),
                source = source,
                loose = loose,
                sectorHint = sector,
            )
        }
        return result
    }

    private fun text(item: Element, tag: String): String {
        val nodes = item.getElementsByTagName(tag)
        return if (nodes.length > 0) nodes.item(0).textContent ?: "" else ""
    }

    private fun parsePubDate(raw: String): Instant? =
        raw.trim().takeIf { it.isNotBlank() }?.let {
            runCatching { ZonedDateTime.parse(it, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant() }.getOrNull()
        }

    /** XXE 방어 파서(doctype 금지 + secure-processing + 외부엔티티 차단). */
    private fun secureDocumentBuilder(): DocumentBuilder {
        val f = DocumentBuilderFactory.newInstance()
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
        f.isXIncludeAware = false
        f.isExpandEntityReferences = false
        return f.newDocumentBuilder()
    }

    private companion object {
        const val SUMMARY_MAX = 600

        // 공개 RSS(키 불필요). Triple(매체명, URL, loose). loose=true 면 부동산 앵커 필수.
        //  - 부동산 전용 피드: loose=false (앵커 불요)
        //  - 경제/금융·종합 비즈니스 피드: loose=true (부동산 외 뉴스·외신/일본판 차단)
        val RSS_FEEDS = listOf(
            Triple("한국경제부동산", "https://www.hankyung.com/feed/realestate", false),
            Triple("매일경제부동산", "https://www.mk.co.kr/rss/50300009/", false),
            Triple("한국경제경제", "https://www.hankyung.com/feed/economy", true),
            Triple("한국경제금융", "https://www.hankyung.com/feed/finance", true),
            Triple("매일경제경제", "https://www.mk.co.kr/rss/30100041/", true),
            Triple("매일경제증권", "https://www.mk.co.kr/rss/50200011/", true),
            Triple("조선비즈", "https://biz.chosun.com/arc/outboundfeeds/rss/?outputType=xml", true),
        )

        // 섹터 → 구글뉴스 딜 검색어(매각·우선협상 등 거래 시그널). site: 로 딜 전문지 가중.
        val DEAL_QUERIES = listOf(
            "office" to "오피스 빌딩 매각 우선협상대상자",
            "office" to "CBD GBD YBD 오피스 매각",
            "office" to "프라임 오피스 매각 site:thebell.co.kr",
            "office" to "사옥 매각 우선협상 site:investchosun.com",
            "logistics" to "물류센터 매각 우선협상대상자",
            "logistics" to "물류센터 거래 site:thebell.co.kr",
            "logistics" to "콜드체인 물류 매각",
            "hotel" to "호텔 매각 우선협상대상자",
            "hotel" to "특급호텔 매각 site:thebell.co.kr",
            "retail" to "리테일 상업시설 매각",
            "retail" to "쇼핑몰 백화점 매각",
            "datacenter" to "데이터센터 매각 투자",
            "datacenter" to "데이터센터 site:thebell.co.kr",
            "reit" to "상장리츠 자산편입 유상증자",
            "reit" to "리츠 매각 site:thebell.co.kr",
            "pf" to "부동산 PF 부실 공매 매각",
            "pf" to "NPL 매각 site:marketinsight.hankyung.com",
            "office" to "부동산 자산운용 매입 우선협상대상자",
            "office" to "상업용 부동산 거래 site:dealsite.co.kr",
        )
    }
}
