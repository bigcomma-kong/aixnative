package com.aixnative.marketfeed.domain
import com.aixnative.marketfeed.web.BriefingRisk
import com.aixnative.marketfeed.web.BriefingSection
import com.aixnative.marketfeed.web.BriefingWatch

/**
 * 마켓 브리핑 뉴스레터 HTML 빌더 (인라인 CSS — 이메일 클라이언트 호환).
 * 디자인: 680px 흰 라운드 카드 on 연회색, 딥 네이비-인디고 헤더(aixnative 브랜드), 좌액센트 섹션 박스.
 * 이미 저장돼 있으나 메일에 누락되던 sections/watchlist/risks 를 모두 렌더한다(콘텐츠 충실화).
 * 모든 동적 텍스트는 [esc] 로 이스케이프(주입·깨짐 차단). 외부 브랜딩 차용 없음(전부 aixnative).
 */
object NewsletterEmail {

    // aixnative 팔레트
    private const val INK = "#1d2240"
    private const val ACCENT = "#3b3bdc"
    private const val PAGE = "#f1f4fa"
    private const val TEXT = "#2b2f38"
    private const val MUTED = "#5a6080"
    private const val LINE = "#e7e9f2"
    private const val TINT = "#eef0fb"
    private const val BOX = "#f7f8fc"

    fun render(
        dateLabel: String,
        greetingName: String,
        headline: String?,
        outlook: String?,
        sections: List<BriefingSection>,
        watchlist: List<BriefingWatch>,
        risks: List<BriefingRisk>,
        articleCount: Int?,
        topCards: List<MarketFeedItem>,
        appUrl: String,
        unsubUrl: String,
    ): String = buildString {
        append("<!doctype html><html lang=\"ko\"><head><meta charset=\"utf-8\">")
        append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">")
        append("<meta name=\"color-scheme\" content=\"light only\"><title>AixNative 시장 브리핑</title>")
        append(
            "<style>@media only screen and (max-width:600px){" +
                ".nl-wrap{margin:0!important;border-radius:0!important}" +
                ".nl-pad{padding-left:20px!important;padding-right:20px!important}}</style>",
        )
        append("</head>")
        append("<body style=\"margin:0;padding:0;background:$PAGE;\">")
        // 외곽 센터 래퍼
        append("<div style=\"background:$PAGE;padding:24px 12px;\">")
        append(
            "<div class=\"nl-wrap\" style=\"max-width:680px;margin:0 auto;background:#ffffff;border-radius:14px;" +
                "overflow:hidden;box-shadow:0 8px 30px rgba(29,34,64,0.10);" +
                "font-family:'Apple SD Gothic Neo','Malgun Gothic','Segoe UI',sans-serif;color:$TEXT;\">",
        )

        appendHeader(dateLabel, greetingName, articleCount)
        appendHeadline(headline)
        appendOutlook(outlook)
        appendSections(sections)
        appendWatchlist(watchlist)
        appendRisks(risks)
        appendDeals(topCards)
        appendCta(appUrl)
        appendFooter(unsubUrl)

        append("</div></div></body></html>")
    }

    private fun StringBuilder.appendHeader(dateLabel: String, greetingName: String, articleCount: Int?) {
        val badge = articleCount?.let { "기사 ${it}건 종합" } ?: "데일리 브리핑"
        append(
            "<div class=\"nl-pad\" style=\"background:$INK;" +
                "background:linear-gradient(135deg,$INK 0%,#2a2f6b 55%,$ACCENT 120%);padding:28px 32px;\">",
        )
        append("<div style=\"display:table;width:100%;\">")
        append(
            "<div style=\"display:table-cell;vertical-align:middle;color:#ffffff;font-size:13px;font-weight:800;" +
                "letter-spacing:1px;\">Aix<span style=\"color:#c7c9f5;\">Native</span></div>",
        )
        append(
            "<div style=\"display:table-cell;vertical-align:middle;text-align:right;\">" +
                "<span style=\"font-size:11px;color:#cdd2f3;background:rgba(255,255,255,0.12);" +
                "padding:4px 11px;border-radius:12px;letter-spacing:0.5px;\">${esc(badge)}</span></div>",
        )
        append("</div>")
        append(
            "<div style=\"color:#ffffff;font-size:24px;font-weight:800;margin-top:12px;letter-spacing:-0.5px;\">" +
                "📊 오늘의 시장 브리핑</div>",
        )
        append(
            "<div style=\"color:#cdd2f3;font-size:13px;margin-top:7px;\">" +
                "${esc(dateLabel)} &nbsp;&middot;&nbsp; ${esc(greetingName)}님께 보내드리는 인사이트</div>",
        )
        append("</div>")
    }

    private fun StringBuilder.appendHeadline(headline: String?) {
        if (headline.isNullOrBlank()) return
        append(
            "<div class=\"nl-pad\" style=\"margin:24px 28px 0;padding:18px 20px;background:$TINT;" +
                "border-left:4px solid $ACCENT;border-radius:0 10px 10px 0;\">",
        )
        append(
            "<span style=\"display:inline-block;font-size:12px;color:$ACCENT;font-weight:700;" +
                "background:rgba(59,59,220,0.10);padding:3px 10px;border-radius:10px;\">⚡ 오늘의 핵심</span>",
        )
        append(
            "<div style=\"font-size:17px;color:$INK;font-weight:700;line-height:1.6;margin-top:10px;\">" +
                "${esc(headline)}</div>",
        )
        append("</div>")
    }

    private fun StringBuilder.appendOutlook(outlook: String?) {
        if (outlook.isNullOrBlank()) return
        append(
            "<div class=\"nl-pad\" style=\"margin:16px 28px 0;font-size:14px;line-height:1.7;color:$TEXT;\">" +
                "${esc(outlook)}</div>",
        )
    }

    private fun StringBuilder.sectionTitle(text: String) {
        append(
            "<div class=\"nl-pad\" style=\"font-size:15px;font-weight:800;color:$INK;margin:30px 28px 14px;" +
                "padding-bottom:9px;border-bottom:2px solid $LINE;letter-spacing:-0.3px;\">${esc(text)}</div>",
        )
    }

    private fun StringBuilder.appendSections(sections: List<BriefingSection>) {
        val items = sections.filter { !it.topic.isNullOrBlank() || !it.summary.isNullOrBlank() }
        if (items.isEmpty()) return
        sectionTitle("📈 분야별 마켓 동향")
        append("<div class=\"nl-pad\" style=\"padding:0 28px;\">")
        items.forEach { s ->
            append(
                "<div style=\"margin-bottom:14px;padding:14px 16px;background:$BOX;border-left:4px solid $ACCENT;" +
                    "border-radius:0 8px 8px 0;\">",
            )
            s.topic?.takeIf { it.isNotBlank() }?.let {
                append("<div style=\"font-weight:700;color:$INK;font-size:14px;margin-bottom:6px;\">${esc(it)}</div>")
            }
            s.summary?.takeIf { it.isNotBlank() }?.let {
                append("<div style=\"color:$TEXT;font-size:13px;line-height:1.7;\">${esc(it)}</div>")
            }
            s.impact?.takeIf { it.isNotBlank() }?.let {
                append("<div style=\"font-size:12px;color:$ACCENT;margin-top:7px;\">📊 ${esc(it)}</div>")
            }
            append("</div>")
        }
        append("</div>")
    }

    private fun StringBuilder.appendWatchlist(watchlist: List<BriefingWatch>) {
        val items = watchlist.filter { !it.item.isNullOrBlank() }
        if (items.isEmpty()) return
        sectionTitle("✅ 이번 주 체크포인트")
        append("<div class=\"nl-pad\" style=\"padding:0 28px;\">")
        append(
            "<div style=\"padding:14px 16px;background:$TINT;border:1px solid #d7dbf5;border-radius:8px;\">",
        )
        items.forEach { w ->
            append("<div style=\"margin-bottom:8px;font-size:13px;line-height:1.6;\">")
            append("<span style=\"color:$ACCENT;font-weight:700;\">&#9656;</span> ")
            append("<span style=\"color:$INK;font-weight:600;\">${esc(w.item)}</span>")
            w.why?.takeIf { it.isNotBlank() }?.let {
                append("<span style=\"color:$MUTED;\"> — ${esc(it)}</span>")
            }
            append("</div>")
        }
        append("</div></div>")
    }

    private fun StringBuilder.appendRisks(risks: List<BriefingRisk>) {
        val items = risks.filter { !it.signal.isNullOrBlank() }
        if (items.isEmpty()) return
        sectionTitle("⚠️ 리스크 모니터")
        append("<div class=\"nl-pad\" style=\"padding:0 28px;\">")
        items.forEach { r ->
            val (bg, fg, border) = severity(r.severity)
            append(
                "<div style=\"margin-bottom:10px;padding:12px 15px;background:$bg;border:1px solid $border;" +
                    "border-radius:8px;\">",
            )
            append("<div style=\"font-size:13px;font-weight:700;color:$fg;\">")
            r.severity?.takeIf { it.isNotBlank() }?.let { append("[${esc(sevLabel(it))}] ") }
            append("${esc(r.signal)}</div>")
            r.mitigation?.takeIf { it.isNotBlank() }?.let {
                append("<div style=\"font-size:12px;color:$TEXT;margin-top:5px;line-height:1.6;\">대응: ${esc(it)}</div>")
            }
            append("</div>")
        }
        append("</div>")
    }

    private fun StringBuilder.appendDeals(topCards: List<MarketFeedItem>) {
        if (topCards.isEmpty()) return
        sectionTitle("🏢 오늘의 딜")
        append("<div class=\"nl-pad\" style=\"padding:0 28px;\">")
        topCards.forEach { c ->
            append(
                "<div style=\"margin-bottom:10px;padding:13px 15px;background:#ffffff;border:1px solid $LINE;" +
                    "border-radius:8px;\">",
            )
            append("<div style=\"font-size:13px;font-weight:700;color:$INK;line-height:1.5;\">")
            c.assetType?.takeIf { it.isNotBlank() }?.let {
                append(
                    "<span style=\"display:inline-block;font-size:11px;font-weight:700;color:$ACCENT;" +
                        "background:rgba(59,59,220,0.10);padding:2px 8px;border-radius:999px;margin-right:6px;\">" +
                        "${esc(it)}</span>",
                )
            }
            append("${esc(c.title)}</div>")
            c.summary?.takeIf { it.isNotBlank() }?.let {
                append("<div style=\"font-size:12px;color:$MUTED;margin-top:5px;line-height:1.6;\">${esc(it)}</div>")
            }
            c.location?.takeIf { it.isNotBlank() }?.let {
                append("<div style=\"font-size:11px;color:#8a90a8;margin-top:4px;\">📍 ${esc(it)}</div>")
            }
            append("</div>")
        }
        append("</div>")
    }

    private fun StringBuilder.appendCta(appUrl: String) {
        append("<div class=\"nl-pad\" style=\"text-align:center;padding:26px 28px 8px;\">")
        append(
            "<a href=\"${esc(appUrl)}\" style=\"display:inline-block;padding:13px 30px;background:$ACCENT;" +
                "color:#ffffff;font-size:14px;font-weight:700;border-radius:999px;text-decoration:none;" +
                "box-shadow:0 4px 14px rgba(59,59,220,0.28);\">전체 보기 &middot; AI 분석 &rarr;</a>",
        )
        append(
            "<div style=\"font-size:12px;color:$MUTED;margin-top:10px;\">" +
                "ProForma 지표는 무료 &middot; AI 심층 분석은 1클릭</div>",
        )
        append("</div>")
    }

    private fun StringBuilder.appendFooter(unsubUrl: String) {
        append("<div class=\"nl-pad\" style=\"background:$INK;color:#cdd2f3;padding:22px 32px;margin-top:20px;\">")
        append(
            "<div style=\"color:#ffffff;font-weight:800;font-size:13px;letter-spacing:1px;\">" +
                "Aix<span style=\"color:#c7c9f5;\">Native</span></div>",
        )
        append(
            "<div style=\"font-size:12px;color:#a6abd6;margin:6px 0;\">AI 부동산 딜 언더라이팅 &middot; 시장 인텔리전스</div>",
        )
        append("<hr style=\"border:none;border-top:1px solid #2e3566;margin:13px 0;\">")
        append(
            "<div style=\"font-size:12px;line-height:1.7;color:#a6abd6;\">" +
                "본 메일은 시장 브리핑 구독자에게 자동 발송됩니다.<br>" +
                "더 이상 받지 않으시려면 " +
                "<a href=\"${esc(unsubUrl)}\" style=\"color:#9aa0cf;text-decoration:underline;\">[구독 해지]</a>" +
                " 를 눌러 주세요.</div>",
        )
        append(
            "<div style=\"font-size:11px;color:#7e84b5;margin-top:9px;\">" +
                "본 메일은 투자자문이 아니며, 투자 판단과 책임은 수신자 본인에게 있습니다. " +
                "&copy; AixNative</div>",
        )
        append("</div>")
    }

    /** severity → (배경, 글자, 테두리). 알 수 없으면 중립. */
    private fun severity(s: String?): Triple<String, String, String> = when (s?.uppercase()?.trim()) {
        "HIGH", "높음" -> Triple("#fbece9", "#c0392b", "#f0c7c0")
        "MEDIUM", "중간" -> Triple("#fff4e5", "#b9770a", "#f3dcae")
        "LOW", "낮음" -> Triple("#fffbe6", "#8a7400", "#ece3a8")
        else -> Triple("#f3f4f8", "#5a6080", "#e1e4ee")
    }

    private fun sevLabel(s: String): String = when (s.uppercase().trim()) {
        "HIGH" -> "높음"; "MEDIUM" -> "중간"; "LOW" -> "낮음"; else -> s
    }

    /** HTML 이스케이프 — 동적 텍스트 주입·깨짐 차단. */
    private fun esc(s: String?): String {
        if (s.isNullOrEmpty()) return ""
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("\"", "&quot;").replace("'", "&#39;")
    }
}
