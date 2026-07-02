package com.aixnative.common.web

import com.aixnative.marketfeed.service.MarketFeedService
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

/**
 * 크롤러 진입점 — robots.txt + 동적 sitemap.xml.
 * sitemap 은 정적 페이지 + 공개 브리핑(/insights/{slug}) 을 매 요청 시 나열(콘텐츠가 매일 갱신되므로 동적).
 * 두 경로 모두 SecurityConfig 에서 permitAll(GET): robots.txt 는 확장자 txt 규칙, sitemap.xml 은 명시 허용.
 */
@RestController
class SeoController(
    private val marketFeed: MarketFeedService,
    @Value("\${app.base-url:http://localhost:8080}") baseUrl: String,
) {
    private val base = baseUrl.trimEnd('/')

    @GetMapping("/robots.txt", produces = [MediaType.TEXT_PLAIN_VALUE])
    fun robots(): String = buildString {
        append("User-agent: *\n")
        append("Allow: /\n")
        // 인증 API·앱 콜백은 크롤 불필요.
        append("Disallow: /api/\n")
        append("Sitemap: $base/sitemap.xml\n")
    }

    @GetMapping("/sitemap.xml", produces = [MediaType.APPLICATION_XML_VALUE])
    fun sitemap(): ResponseEntity<String> {
        val briefings = marketFeed.briefingHistory()
        val xml = buildString {
            append("<?xml version='1.0' encoding='UTF-8'?>")
            append("<urlset xmlns='http://www.sitemaps.org/schemas/sitemap/0.9'>")
            url("$base/", "1.0")
            url("$base/insights", "0.9")
            briefings.forEach { b ->
                val slug = if (b.briefingDate.isNullOrBlank()) "${b.id}" else "${b.briefingDate}-${b.id}"
                val lastmod = b.briefingDate?.takeIf { it.isNotBlank() }
                url("$base/insights/${escXml(slug)}", "0.7", lastmod)
            }
            append("</urlset>")
        }
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_XML).body(xml)
    }

    private fun StringBuilder.url(loc: String, priority: String, lastmod: String? = null) {
        append("<url><loc>").append(escXml(loc)).append("</loc>")
        lastmod?.let { append("<lastmod>").append(escXml(it)).append("</lastmod>") }
        append("<priority>").append(priority).append("</priority></url>")
    }

    private fun escXml(s: String): String = buildString(s.length) {
        for (c in s) when (c) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            '\'' -> append("&apos;")
            else -> append(c)
        }
    }
}
