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

/** RSS 2.0 item 1건(표준 필드 + 대표 이미지 + 구글 트렌드 확장). */
data class RssItem(
    val title: String,
    val link: String,
    val description: String,
    val source: String,
    val publishedAt: Instant?,
    /** 대표 이미지(media:content/thumbnail·enclosure·ht:picture 등에서 추출). 없으면 null. */
    val imageUrl: String? = null,
    /** 구글 트렌드 전용 - 대표 관련 기사 URL(ht:news_item_url 첫 건). 스토리 딥페치 소스. */
    val newsUrl: String? = null,
    /** 구글 트렌드 전용 - 대표 관련 기사 제목(ht:news_item_title 첫 건). */
    val newsTitle: String? = null,
    /** 구글 트렌드 전용 - 근사 검색량(ht:approx_traffic, 예 "20000+"). 없으면 null. */
    val approxTraffic: String? = null,
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
                imageUrl = extractImage(item),
                newsUrl = text(item, "ht:news_item_url").trim().takeIf { it.startsWith("http") },
                newsTitle = stripHtml(text(item, "ht:news_item_title")).ifBlank { null },
                approxTraffic = text(item, "ht:approx_traffic").trim().ifBlank { null },
            )
        }
        return out
    }

    private fun text(item: Element, tag: String): String {
        val nodes = item.getElementsByTagName(tag)
        return if (nodes.length > 0) nodes.item(0).textContent ?: "" else ""
    }

    /**
     * item 대표 이미지 추출 - 흔한 확장 태그를 순서대로 시도.
     * media:content/thumbnail(url 속성), enclosure(type=image, url), ht:picture(트렌드, 텍스트),
     * 마지막으로 description 내 첫 <img src>. http(s) 만 채택.
     */
    private fun extractImage(item: Element): String? {
        // media:content, media:thumbnail (url 속성)
        for (tag in listOf("media:content", "media:thumbnail", "thumbnail", "content")) {
            val nodes = item.getElementsByTagName(tag)
            for (i in 0 until nodes.length) {
                val el = nodes.item(i) as? Element ?: continue
                val url = el.getAttribute("url").trim()
                if (url.startsWith("http")) return url
            }
        }
        // enclosure type=image
        val enc = item.getElementsByTagName("enclosure")
        for (i in 0 until enc.length) {
            val el = enc.item(i) as? Element ?: continue
            val type = el.getAttribute("type")
            val url = el.getAttribute("url").trim()
            if (url.startsWith("http") && (type.isBlank() || type.startsWith("image"))) return url
        }
        // ht:picture (구글 트렌드) - 텍스트 값
        val pic = item.getElementsByTagName("ht:picture")
        if (pic.length > 0) {
            val url = pic.item(0).textContent?.trim().orEmpty()
            if (url.startsWith("http")) return url
        }
        // description 내 첫 <img src="...">
        val desc = text(item, "description")
        Regex("<img[^>]+src=[\"']([^\"']+)[\"']").find(desc)?.groupValues?.get(1)?.let {
            if (it.startsWith("http")) return it
        }
        return null
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
