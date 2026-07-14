package com.aixnative.social.service

import com.aixnative.social.domain.SourceArticle
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.w3c.dom.Element
import java.net.URLEncoder
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilder
import javax.xml.parsers.DocumentBuilderFactory

/**
 * 주제별 화제 소재 수집 - 구글뉴스 RSS 검색으로 최신 기사를 긁는다(키 0개).
 * 범용 주제(재테크·경제·부동산 등)를 모두 커버하도록 [com.aixnative.marketfeed.service.RssNewsCollector]
 * 의 부동산 특화 필터 대신, 주제 키워드 검색 결과를 그대로 후보로 삼는다(최종 큐레이션은 Claude 담당).
 * XML 은 XXE 방어 파서로 처리.
 */
@Component
class SocialSourceCollector(
    private val marketDataRestClient: RestClient,
    private val props: SocialProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** 한 주제에 대한 최신 소재 기사(발행 최신순, 최근 윈도 이내). */
    fun collect(topic: String): List<SourceArticle> {
        val cutoff = Instant.now().minus(props.recentHours, ChronoUnit.HOURS)
        val parsed = runCatching { parseFeed(fetch(googleNewsUrl(topic))) }
            .getOrElse { log.warn("[social] 소재 수집 실패 '{}': {}", topic, it.message); emptyList() }
        return parsed
            .filter { it.publishedAt == null || it.publishedAt.isAfter(cutoff) }
            .sortedByDescending { it.publishedAt ?: Instant.EPOCH }
            .map { SourceArticle(title = it.title, summary = it.summary, link = it.link, source = it.source) }
            .take(props.maxSourcesPerTopic)
    }

    private fun fetch(url: String): String =
        marketDataRestClient.get().uri(url).retrieve().body(String::class.java)
            ?: throw RuntimeException("빈 응답")

    private fun googleNewsUrl(query: String): String {
        val q = URLEncoder.encode(query, Charsets.UTF_8)
        return "https://news.google.com/rss/search?q=$q&hl=ko&gl=KR&ceid=KR:ko"
    }

    /** RSS 2.0 <item> 파싱 -> 내부 레코드. */
    private fun parseFeed(xml: String): List<ParsedItem> {
        val doc = secureDocumentBuilder().parse(xml.byteInputStream())
        val items = doc.getElementsByTagName("item")
        val result = ArrayList<ParsedItem>()
        for (i in 0 until items.length) {
            val item = items.item(i) as? Element ?: continue
            val title = stripHtml(text(item, "title"))
            val link = text(item, "link").trim()
            if (title.isBlank() || link.isBlank()) continue
            result += ParsedItem(
                title = title,
                summary = stripHtml(text(item, "description")).take(SUMMARY_MAX),
                link = link,
                publishedAt = parsePubDate(text(item, "pubDate")),
                source = sourceLabel(item),
            )
        }
        return result
    }

    /** 구글뉴스 item 의 <source> 태그(매체명). 없으면 "구글뉴스". */
    private fun sourceLabel(item: Element): String {
        val nodes = item.getElementsByTagName("source")
        val label = if (nodes.length > 0) nodes.item(0).textContent?.trim().orEmpty() else ""
        return label.ifBlank { "구글뉴스" }
    }

    private fun text(item: Element, tag: String): String {
        val nodes = item.getElementsByTagName(tag)
        return if (nodes.length > 0) nodes.item(0).textContent ?: "" else ""
    }

    private fun stripHtml(raw: String): String =
        raw.replace(Regex("<[^>]*>"), " ").replace(Regex("\\s+"), " ").trim()

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

    private data class ParsedItem(
        val title: String,
        val summary: String,
        val link: String,
        val publishedAt: Instant?,
        val source: String,
    )

    private companion object {
        const val SUMMARY_MAX = 400
    }
}
