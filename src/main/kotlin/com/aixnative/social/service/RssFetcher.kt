package com.aixnative.social.service

import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.w3c.dom.Element
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilder
import javax.xml.parsers.DocumentBuilderFactory

/** RSS 2.0 item 1건(표준 필드). */
data class RssItem(
    val title: String,
    val link: String,
    val description: String,
    val source: String,
    val publishedAt: Instant?,
)

/**
 * 직접 RSS fetch·파싱 공용 유틸([GoogleTrendSource]·[NewsRssSource] 공유).
 * marketfeed/RssNewsCollector 의 fetch/parseFeed/XXE-safe 파서 패턴 이식.
 * marketDataRestClient(짧은 타임아웃 + Mozilla UA)를 재사용해 Cloud Run 에서도 안정 동작.
 */
@Component
class RssFetcher(private val marketDataRestClient: RestClient) {

    /** @return 표준 파싱된 item 목록. 실패/빈응답 시 예외(호출부에서 graceful 처리). */
    fun fetch(url: String, source: String): List<RssItem> {
        val xml = marketDataRestClient.get().uri(url).retrieve().body(String::class.java)
            ?: throw RuntimeException("빈 응답")
        val doc = secureDocumentBuilder().parse(xml.byteInputStream())
        val nodes = doc.getElementsByTagName("item")
        val out = ArrayList<RssItem>()
        for (i in 0 until nodes.length) {
            val item = nodes.item(i) as? Element ?: continue
            val title = stripHtml(text(item, "title"))
            if (title.isBlank()) continue
            out += RssItem(
                title = title,
                link = text(item, "link").trim(),
                description = stripHtml(text(item, "description")).take(400),
                source = source,
                publishedAt = parsePubDate(text(item, "pubDate")),
            )
        }
        return out
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

    private fun secureDocumentBuilder(): DocumentBuilder {
        val f = DocumentBuilderFactory.newInstance()
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
        f.isXIncludeAware = false
        f.isExpandEntityReferences = false
        return f.newDocumentBuilder()
    }
}
