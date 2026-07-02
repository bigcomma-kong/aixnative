package com.aixnative.marketfeed.web

import com.aixnative.common.Disclaimer
import com.aixnative.marketfeed.service.MarketFeedService
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.CacheControl
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Duration

/**
 * 공개(무인증) 시장 인텔리전스 — SEO 유입용 서버렌더 HTML.
 *
 * 매일 무료(Mistral)로 생성되는 마켓 브리핑을 로그인 없이 크롤 가능한 정적-유사 HTML 로 노출한다.
 * SPA(JS 렌더)는 크롤 불가하므로 검색엔진·SNS 미리보기를 위해 여기서 완결형 HTML(제목·메타·OG·JSON-LD)을 만든다.
 * 유료 심층 리포트(Claude)는 계속 로그인·과금 — 여기선 무료 브리핑 요약/전망까지만 노출(카니벌라이제이션 방지).
 *
 * 경로는 SPA·정적자산과 겹치지 않는 insights 하위 (SecurityConfig 에서 permitAll).
 */
@RestController
class PublicInsightsController(
    private val marketFeed: MarketFeedService,
    private val objectMapper: ObjectMapper,
    @Value("\${app.base-url:http://localhost:8080}") baseUrl: String,
) {
    private val base = baseUrl.trimEnd('/')

    /** 인사이트 허브 — 최근 브리핑 목록(크롤 인덱스 + 사람 진입점). */
    @GetMapping("/insights", produces = [MediaType.TEXT_HTML_VALUE])
    fun hub(): ResponseEntity<String> {
        val items = marketFeed.briefingHistory()
        val html = buildString {
            appendHead(
                title = "부동산 시장 브리핑 — aixnative",
                description = "AI가 매일 정리하는 상업용 부동산 시장 동향·전망·리스크. 오피스·물류·리테일 딜과 금리·거래 모멘텀을 한눈에.",
                url = "$base/insights",
                type = "website",
            )
            append("<body>")
            appendBrandHeader()
            append("<main class='wrap'>")
            append("<h1>부동산 시장 브리핑</h1>")
            append("<p class='lede'>AI가 매일 상업용 부동산 뉴스를 종합해 시장 동향·전망·리스크를 정리합니다. 무료로 열람하세요.</p>")
            if (items.isEmpty()) {
                append("<p class='muted'>아직 발행된 브리핑이 없습니다. 곧 업데이트됩니다.</p>")
            } else {
                append("<ul class='cards'>")
                items.forEach { b ->
                    val slug = slugOf(b.briefingDate, b.id)
                    append("<li class='card'><a href='$base/insights/${esc(slug)}'>")
                    append("<span class='date'>${esc(b.briefingDate ?: "")}</span>")
                    append("<span class='h'>${esc(b.headline ?: "시장 브리핑")}</span>")
                    b.articleCount?.let { append("<span class='meta'>기사 ${it}건 종합</span>") }
                    append("</a></li>")
                }
                append("</ul>")
            }
            appendCta()
            append("</main>")
            appendFooter()
            append("</body></html>")
        }
        return htmlResponse(html)
    }

    /** 브리핑 단건 — 서버렌더 아티클(메타·OG·JSON-LD 포함). slug = `{date}-{id}`. */
    @GetMapping("/insights/{slug}", produces = [MediaType.TEXT_HTML_VALUE])
    fun article(@PathVariable slug: String): ResponseEntity<String> {
        val id = slug.substringAfterLast('-').toLongOrNull()
            ?: return notFound()
        val b = runCatching { marketFeed.briefingById(id) }.getOrNull()
            ?: return notFound()

        val title = b.headline ?: "부동산 시장 브리핑 ${b.briefingDate ?: ""}".trim()
        val desc = (b.outlook ?: b.headline ?: "AI 부동산 시장 브리핑").collapse().take(155)
        val url = "$base/insights/${esc(slugOf(b.briefingDate, b.id))}"

        val html = buildString {
            appendHead(title = "$title — aixnative", description = desc, url = url, type = "article")
            appendJsonLd(title, desc, url, b.briefingDate)
            append("<body>")
            appendBrandHeader()
            append("<main class='wrap article'>")
            append("<a class='back' href='$base/insights'>← 시장 브리핑 목록</a>")
            b.briefingDate?.let { append("<div class='date'>${esc(it)}</div>") }
            append("<h1>${esc(title)}</h1>")
            b.outlook?.let { append("<p class='lede'>${esc(it)}</p>") }

            if (b.sections.isNotEmpty()) {
                append("<section><h2>주요 동향</h2>")
                b.sections.forEach { s ->
                    append("<div class='sec'>")
                    s.topic?.let { append("<h3>${esc(it)}</h3>") }
                    s.summary?.let { append("<p>${esc(it)}</p>") }
                    s.impact?.let { append("<p class='impact'><b>함의:</b> ${esc(it)}</p>") }
                    append("</div>")
                }
                append("</section>")
            }
            if (b.watchlist.isNotEmpty()) {
                append("<section><h2>워치리스트</h2><ul>")
                b.watchlist.forEach { w ->
                    append("<li><b>${esc(w.item ?: "")}</b>${w.why?.let { " — ${esc(it)}" } ?: ""}</li>")
                }
                append("</ul></section>")
            }
            if (b.risks.isNotEmpty()) {
                append("<section><h2>리스크</h2><ul>")
                b.risks.forEach { r ->
                    val sev = r.severity?.let { " [${esc(it)}]" } ?: ""
                    val mit = r.mitigation?.let { " → ${esc(it)}" } ?: ""
                    append("<li><b>${esc(r.signal ?: "")}</b>$sev$mit</li>")
                }
                append("</ul></section>")
            }
            appendCta()
            append("<p class='disclaimer'>${esc(Disclaimer.TEXT)}</p>")
            append("</main>")
            appendFooter()
            append("</body></html>")
        }
        return htmlResponse(html)
    }

    // ── HTML helpers ──────────────────────────────────────────────────────────

    private fun StringBuilder.appendHead(title: String, description: String, url: String, type: String) {
        append("<!doctype html><html lang='ko'><head><meta charset='utf-8'>")
        append("<meta name='viewport' content='width=device-width, initial-scale=1'>")
        append("<title>${esc(title)}</title>")
        append("<meta name='description' content='${esc(description)}'>")
        append("<link rel='canonical' href='${esc(url)}'>")
        append("<meta property='og:site_name' content='aixnative'>")
        append("<meta property='og:type' content='${esc(type)}'>")
        append("<meta property='og:title' content='${esc(title)}'>")
        append("<meta property='og:description' content='${esc(description)}'>")
        append("<meta property='og:url' content='${esc(url)}'>")
        append("<meta property='og:image' content='$base/og.png'>")
        append("<meta name='twitter:card' content='summary_large_image'>")
        append("<meta name='twitter:title' content='${esc(title)}'>")
        append("<meta name='twitter:description' content='${esc(description)}'>")
        append("<meta name='twitter:image' content='$base/og.png'>")
        append("<style>$CSS</style></head>")
    }

    /** 검색 리치결과용 Article 구조화 데이터. ObjectMapper 로 안전 직렬화. */
    private fun StringBuilder.appendJsonLd(title: String, desc: String, url: String, date: String?) {
        val ld = linkedMapOf<String, Any>(
            "@context" to "https://schema.org",
            "@type" to "Article",
            "headline" to title,
            "description" to desc,
            "url" to url,
            "image" to "$base/og.png",
            "author" to mapOf("@type" to "Organization", "name" to "aixnative"),
            "publisher" to mapOf(
                "@type" to "Organization",
                "name" to "aixnative",
                "logo" to mapOf("@type" to "ImageObject", "url" to "$base/og.png"),
            ),
        )
        date?.let { ld["datePublished"] = it }
        append("<script type='application/ld+json'>")
        append(objectMapper.writeValueAsString(ld))
        append("</script>")
    }

    private fun StringBuilder.appendBrandHeader() {
        append("<header class='top'><a class='brand' href='$base/'>aixnative</a>")
        append("<a class='cta-sm' href='$base/'>무료로 시작 →</a></header>")
    }

    private fun StringBuilder.appendCta() {
        append("<div class='cta'><div class='cta-h'>내 딜을 1분 만에 AI로 심사하세요</div>")
        append("<p>매입가·NOI·자본구조만 넣으면 IRR·DSCR·리스크까지. 가입 시 무료 크레딧 제공.</p>")
        append("<a class='cta-btn' href='$base/'>무료로 시작하기 →</a></div>")
    }

    private fun StringBuilder.appendFooter() {
        append("<footer class='foot'>© aixnative · AI 부동산 딜 언더라이팅</footer>")
    }

    private fun htmlResponse(html: String): ResponseEntity<String> =
        ResponseEntity.ok()
            .contentType(MediaType.TEXT_HTML)
            .cacheControl(CacheControl.maxAge(Duration.ofMinutes(30)).cachePublic())
            .body(html)

    private fun notFound(): ResponseEntity<String> =
        ResponseEntity.status(404).contentType(MediaType.TEXT_HTML).body(
            "<!doctype html><html lang='ko'><head><meta charset='utf-8'><title>없는 페이지</title></head>" +
                "<body style='font-family:sans-serif;text-align:center;padding:4rem'>" +
                "<h1>페이지를 찾을 수 없습니다</h1><p><a href='$base/insights'>시장 브리핑 목록으로</a></p></body></html>",
        )

    /** SEO 슬러그: `{date}-{id}` (id 는 뒤에서 파싱). date 없으면 id 만. */
    private fun slugOf(date: String?, id: Long): String =
        if (date.isNullOrBlank()) id.toString() else "$date-$id"

    private fun String.collapse(): String = trim().replace(Regex("\\s+"), " ")

    private fun esc(s: String): String = buildString(s.length) {
        for (c in s) when (c) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            '\'' -> append("&#39;")
            else -> append(c)
        }
    }

    private companion object {
        val CSS = """
            :root{--ink:#1e2233;--soft:#565b73;--line:#e6e8f0;--accent:#3f46d6;--bg:#fafbff}
            *{box-sizing:border-box}
            body{margin:0;font-family:'Pretendard',system-ui,-apple-system,'Apple SD Gothic Neo',sans-serif;
              color:var(--ink);background:var(--bg);line-height:1.6}
            a{color:inherit}
            .top{display:flex;align-items:center;justify-content:space-between;padding:1rem 1.25rem;
              border-bottom:1px solid var(--line);background:#fff;position:sticky;top:0}
            .brand{font-weight:800;letter-spacing:-.02em;text-decoration:none;font-size:1.1rem}
            .cta-sm{font-size:.85rem;font-weight:700;color:var(--accent);text-decoration:none}
            .wrap{max-width:760px;margin:0 auto;padding:2rem 1.25rem 3rem}
            h1{font-size:clamp(1.6rem,1.2rem+2vw,2.4rem);letter-spacing:-.02em;margin:.2rem 0 .6rem}
            h2{font-size:1.25rem;margin:2rem 0 .6rem;letter-spacing:-.01em}
            h3{font-size:1.02rem;margin:1.1rem 0 .3rem}
            .lede{font-size:1.08rem;color:var(--soft)}
            .muted{color:var(--soft)}
            .date{font-size:.82rem;color:var(--soft);font-weight:600}
            .back{display:inline-block;margin-bottom:1rem;font-size:.85rem;color:var(--accent);text-decoration:none}
            .cards{list-style:none;padding:0;margin:1.5rem 0;display:grid;gap:.75rem}
            .card a{display:grid;gap:.25rem;padding:1rem 1.1rem;background:#fff;border:1px solid var(--line);
              border-radius:14px;text-decoration:none;transition:border-color .15s,transform .15s}
            .card a:hover{border-color:var(--accent);transform:translateY(-2px)}
            .card .h{font-weight:700;font-size:1.05rem}
            .card .meta{font-size:.78rem;color:var(--soft)}
            .sec{padding:.4rem 0;border-bottom:1px solid var(--line)}
            .impact{color:var(--soft);font-size:.95rem}
            section ul{padding-left:1.2rem;display:grid;gap:.35rem}
            .cta{margin:2.5rem 0 1rem;padding:1.6rem;border-radius:18px;color:#fff;
              background:linear-gradient(140deg,#3f46d6,#7c4ddb)}
            .cta-h{font-size:1.2rem;font-weight:800;letter-spacing:-.01em}
            .cta p{margin:.4rem 0 1rem;opacity:.92}
            .cta-btn{display:inline-block;background:#fff;color:var(--accent);font-weight:800;
              padding:.7rem 1.3rem;border-radius:999px;text-decoration:none}
            .disclaimer{margin-top:1.5rem;font-size:.76rem;color:var(--soft)}
            .foot{border-top:1px solid var(--line);padding:1.5rem 1.25rem;text-align:center;
              font-size:.8rem;color:var(--soft);background:#fff}
        """.trimIndent()
    }
}
