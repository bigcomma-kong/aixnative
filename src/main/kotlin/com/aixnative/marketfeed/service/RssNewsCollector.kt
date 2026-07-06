package com.aixnative.marketfeed.service

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
import com.aixnative.marketfeed.domain.NewsItem

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

    /** collect() 진단 결과 — 원본 아이템 + 구글뉴스 스로틀 가시화 카운트(조용한 축소 방지). */
    data class CollectionResult(
        val items: List<NewsItem>,
        val googleQueriesTotal: Int = 0,
        val googleQueriesThin: Int = 0,
        val notes: List<String> = emptyList(),
    ) {
        companion object { val EMPTY = CollectionResult(emptyList()) }
    }

    /**
     * 모든 활성 소스에서 기사 수집(중복제거 전 원본) + 수집 진단.
     * 구글뉴스는 한 IP에서 몰아치면 429/빈응답으로 자주 막히므로 쿼리 간 간격 + 빈응답 1회 재시도로 완화하고,
     * 빈응답/실패 쿼리 수를 [CollectionResult] 로 노출해 "소리 없는 축소"를 상위에서 볼 수 있게 한다.
     */
    fun collect(): CollectionResult {
        val out = ArrayList<NewsItem>()
        val notes = ArrayList<String>()

        // 1) RSS. 부동산 전용 피드는 loose=false(앵커 불요), 경제/금융·종합지는 loose=true(부동산 앵커 필수).
        for ((name, url, loose) in RSS_FEEDS) {
            val got = runCatching { parseFeed(fetch(url), source = "RSS:$name", loose = loose, sector = null) }
                .getOrElse { notes += "RSS '$name' 실패: ${it.message}"; log.warn("[ingest] RSS '{}' 실패: {}", name, it.message); emptyList() }
            out += got
            log.debug("[ingest] RSS '{}' {}건", name, got.size)
        }

        // 2) 구글뉴스 섹터 딜 검색(느슨한 소스 — 앵커 게이트 적용). 스로틀 완화: 간격 + 빈응답 재시도.
        var googleTotal = 0
        var googleThin = 0
        if (props.googleNewsEnabled) {
            for ((idx, sq) in DEAL_QUERIES.withIndex()) {
                googleTotal++
                val got = fetchGoogleNews(query = sq.second, source = "GOOGLE_NEWS", loose = true, sector = sq.first, notes = notes)
                if (got.isEmpty()) googleThin++ else out += got
                if (idx < DEAL_QUERIES.lastIndex) sleepQuiet(GOOGLE_QUERY_SPACING_MS)
            }
            if (googleThin > 0) log.warn("[ingest] 구글뉴스 빈응답/실패 {}/{} — 스로틀 의심", googleThin, googleTotal)
        }

        return CollectionResult(items = out, googleQueriesTotal = googleTotal, googleQueriesThin = googleThin, notes = notes)
    }

    /** 구글뉴스 1개 쿼리 — 빈응답/오류면 백오프 후 재시도. 최종 실패 시 빈 리스트 + 진단 노트. 딜·헤드라인 공용. */
    private fun fetchGoogleNews(query: String, source: String, loose: Boolean, sector: String?, notes: MutableList<String>): List<NewsItem> {
        var lastError: String? = null
        for (attempt in 0..GOOGLE_RETRIES) {
            val items = runCatching { parseFeed(fetch(googleNewsUrl(query)), source = source, loose = loose, sector = sector) }
                .getOrElse { lastError = it.message; emptyList() }
            if (items.isNotEmpty()) return items
            if (attempt < GOOGLE_RETRIES) sleepQuiet(GOOGLE_RETRY_BACKOFF_MS)
        }
        notes += "구글뉴스 '$query' 빈응답/실패${lastError?.let { " ($it)" } ?: ""}"
        return emptyList()
    }

    /** 인터럽트 안전 슬립(스로틀 완화용 짧은 대기). */
    private fun sleepQuiet(ms: Long) {
        runCatching { Thread.sleep(ms) }.onFailure { Thread.currentThread().interrupt() }
    }

    /**
     * 업계 헤드라인 소스(SPI·딜사이트·코어비트)를 구글뉴스 site: 검색으로 수집.
     * 딜 카드 파이프라인과 무관 — 매체명을 source('HEADLINE:<매체>')에 실어 제목만 뽑는 용도.
     * CRE 전문 매체라 앵커 게이트를 걸지 않는다(loose=false). 정제는 HeadlineTextCleaner 담당.
     */
    fun collectHeadlines(): List<NewsItem> {
        val out = ArrayList<NewsItem>()
        val notes = ArrayList<String>()
        // 헤드라인 쿼리는 딜 수집 뒤 마지막에 몰려 스로틀당하기 쉽다 → 딜과 동일한 재시도+백오프+간격 경로 사용.
        for ((idx, hs) in HEADLINE_SOURCES.withIndex()) {
            out += fetchGoogleNews(query = hs.second, source = "HEADLINE:${hs.first}", loose = false, sector = null, notes = notes)
            if (idx < HEADLINE_SOURCES.lastIndex) sleepQuiet(GOOGLE_QUERY_SPACING_MS)
        }
        if (notes.isNotEmpty()) log.warn("[headline] 구글뉴스 빈응답/실패: {}", notes)
        return out
    }

    /**
     * CRE 전문 매체의 **자체 RSS를 직접** 수집(구글뉴스 미경유 — Cloud Run IP 가 구글뉴스 site: 쿼리를
     * 빈값으로 막는 문제 회피). 딜 관련성 게이트 없이 '헤드라인 카드'(제목+원문링크)로 시장 피드에 실린다.
     * 현재 공개 RSS가 있는 건 SPI(영문)뿐 — Dealsite=로그인 뒤, Corebeat=RSS 없음(SPA).
     */
    fun collectOutletFeeds(): List<NewsItem> {
        val out = ArrayList<NewsItem>()
        for ((name, url) in OUTLET_FEEDS) {
            val got = runCatching { parseFeed(fetch(url), source = "RSS:$name", loose = false, sector = null) }
                .getOrElse { log.warn("[outlet] '{}' 실패: {}", name, it.message); emptyList() }
            out += got
            log.debug("[outlet] '{}' {}건", name, got.size)
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

        // 구글뉴스 스로틀 완화 — 쿼리 간 간격 + 빈응답/오류 재시도. 스케줄 백그라운드라 소폭 지연 허용.
        // 빈응답은 빠르게 돌아오므로 재시도 비용이 작다(타임아웃 아님). 마지막에 몰리는 헤드라인 회복 위해 2회.
        const val GOOGLE_RETRIES = 2
        const val GOOGLE_RETRY_BACKOFF_MS = 700L
        // 쿼리 간 간격 — 너무 촘촘하면(200ms) 22쿼리가 ~4초에 몰려 구글뉴스가 IP를 레이트리밋한다.
        // 700ms 로 벌려 버스트를 눌러 스로틀 확률을 낮춘다(백그라운드/관리자 수동이라 지연 허용).
        const val GOOGLE_QUERY_SPACING_MS = 700L

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

        // 업계 헤드라인 매체 → 구글뉴스 "키워드+site:" 검색어. Pair(매체 라벨, 쿼리).
        // ⚠ 순수 site:(키워드 없음) 쿼리는 Cloud Run 송신 IP에서 구글뉴스가 빈값으로 막는다(딜 키워드 쿼리는 통과).
        //   → CRE 전문 매체라 광범위 부동산 키워드 그룹을 붙여 사실상 전체 최신 기사를 끌어온다(작동하는 딜 쿼리와 동일 형식).
        val HEADLINE_CRE_TERMS = "(부동산 OR 오피스 OR 물류 OR 리테일 OR 호텔 OR 리츠 OR 매각 OR 투자 OR 임대 OR 빌딩 OR 자산운용 OR 개발)"
        val HEADLINE_SOURCES = listOf(
            "SPI" to "site:seoulpi.io $HEADLINE_CRE_TERMS",
            "코어비트" to "site:corebeat.co.kr $HEADLINE_CRE_TERMS",
            "딜사이트" to "site:dealsite.co.kr $HEADLINE_CRE_TERMS",
        )

        // CRE 전문 매체의 자체 RSS(직접 수집 — 구글뉴스 미경유). Pair(매체 라벨, 피드 URL).
        // SPI 는 영문판(en.seoulpi.co.kr)만 공개 RSS 존재. 한글 seoulpi.io 는 SPA 로 피드 없음.
        // Dealsite(로그인 뒤)·Corebeat(RSS 없음)는 공개 피드가 없어 현재 미포함 — 피드 생기면 여기 추가.
        val OUTLET_FEEDS = listOf(
            "SPI" to "https://en.seoulpi.co.kr/feed",
        )
    }
}
