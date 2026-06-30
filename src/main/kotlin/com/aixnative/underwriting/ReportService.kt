package com.aixnative.underwriting

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.aixnative.ai.AiToolRunService
import com.aixnative.common.Disclaimer
import com.aixnative.common.web.NotFoundException
import org.springframework.stereotype.Service

/**
 * 투자 보고서(IM) HTML 생성. 한 딜의 분석 단계(스크리닝·시장조사·언더라이팅·투심)를 모아
 * 인쇄 친화 단일 문서로 조립한다. ImV2ReportBuilder(레거시)의 HTML 산출 패턴을 이식.
 *
 * 입력은 저장된 ai_tool_run 결과 JSON. 같은 딜명(dealName)의 최신 단계들을 합본한다.
 * 모든 사용자 텍스트는 HTML 이스케이프(마크업/인젝션 차단).
 */
@Service
class ReportService(
    private val aiToolRunService: AiToolRunService,
    private val objectMapper: ObjectMapper,
) {

    /** 주어진 run 이 속한 딜의 단계들을 모아 보고서 HTML 을 만든다. 테넌트 스코프(get 이 검증). */
    fun buildHtml(runId: Long): String {
        val anchor = aiToolRunService.get(runId) // 다른 테넌트면 여기서 NotFound
        val dealName = anchor.dealName

        // 같은 딜의 단계별 최신 결과 (listMine 은 최신순). dealName 이 같으면(둘 다 null 포함) 합본.
        val byTool = LinkedHashMap<AnalysisType, JsonNode>()
        for (run in aiToolRunService.listMine()) {
            if (run.dealName != dealName) continue
            val type = AnalysisType.fromTool(run.tool) ?: continue
            if (byTool.containsKey(type)) continue // 최신만
            val node = run.resultJson?.let { runCatching { objectMapper.readTree(it) }.getOrNull() } ?: continue
            byTool[type] = node
        }
        if (byTool.isEmpty()) throw NotFoundException("보고서를 만들 분석 결과가 없습니다.")

        // 입력/ProForma 는 아무 단계 결과에서나 동일 — anchor 우선, 없으면 첫 단계.
        val anchorResult = anchor.resultJson?.let { runCatching { objectMapper.readTree(it) }.getOrNull() }
        val proFormaNode = anchorResult?.get("proForma") ?: byTool.values.firstNotNullOfOrNull { it.get("proForma") }
        val requestNode = anchor.requestJson?.let { runCatching { objectMapper.readTree(it) }.getOrNull() }

        val sb = StringBuilder()
        sb.appendHtmlHead(dealName ?: "(이름없음)")
        sb.append("<main class='report'>")
        sb.appendCover(dealName, requestNode)
        if (proFormaNode != null) sb.appendMetrics(proFormaNode)

        // 단계 순서대로 섹션 렌더 (있는 것만).
        for (type in AnalysisType.PIPELINE) {
            val node = byTool[type] ?: continue
            val analysis = node.get("analysis")
            sb.append("<section class='step'>")
            sb.append("<h2>${esc(type.label)}</h2>")
            if (analysis != null && !analysis.isNull) {
                when (type) {
                    AnalysisType.SCREENING -> sb.appendScreening(analysis)
                    AnalysisType.MARKET_STUDY -> sb.appendMarketStudy(analysis)
                    AnalysisType.UNDERWRITING -> sb.appendUnderwriting(analysis)
                    AnalysisType.IC_MEMO -> sb.appendIcMemo(analysis)
                }
            } else {
                val raw = node.get("analysisRaw")?.asText()
                sb.append("<p class='raw'>${esc(raw ?: "(분석 본문 없음)")}</p>")
            }
            sb.append("</section>")
        }

        if (proFormaNode != null) sb.appendProFormaTable(proFormaNode)
        sb.append("<footer class='disclaimer'>${esc(Disclaimer.TEXT)}</footer>")
        sb.append("</main></body></html>")
        return sb.toString()
    }

    // ── 섹션 렌더러 ──

    private fun StringBuilder.appendCover(dealName: String?, req: JsonNode?) {
        append("<header class='cover'>")
        append("<div class='brand'>aixnative</div>")
        append("<div class='doc-type'>투자 분석 보고서 · Investment Memorandum</div>")
        append("<h1>${esc(dealName ?: "(이름없음)")}</h1>")
        if (req != null) {
            append("<table class='kv'>")
            row("자산유형", req.txt("assetType"))
            row("위치", req.txt("location"))
            row("매입가", req.num("askingPriceEok")?.let { "${it}억" })
            row("NOI", req.num("noiEok")?.let { "${it}억" })
            row("LTV", req.num("ltvPct")?.let { "${it}%" })
            row("대출금리", req.num("loanRatePct")?.let { "${it}%" })
            row("Exit Cap", req.num("exitCapPct")?.let { "${it}%" })
            row("보유기간", req.num("holdYears")?.let { "${it}년" })
            append("</table>")
        }
        append("</header>")
    }

    private fun StringBuilder.appendMetrics(pf: JsonNode) {
        append("<section class='metrics'>")
        metric("Levered IRR", pf.num("leveredIrrPct")?.let { "$it%" })
        metric("Equity Multiple", pf.num("equityMultiple")?.let { "${it}x" })
        metric("Going-in Cap", pf.num("goingInCapPct")?.let { "$it%" })
        metric("Yield on Cost", pf.num("yieldOnCostPct")?.let { "$it%" })
        metric("총투자비", pf.num("totalInvestEok")?.let { "${it}억" })
        metric("Equity", pf.num("equityEok")?.let { "${it}억" })
        append("</section>")
    }

    private fun StringBuilder.appendScreening(a: JsonNode) {
        verdict(a.txt("verdict"), a.txt("verdict_reason"))
        appendKpiTable(a.get("metrics"))
        // 핵심 근거 — 신규: key_points 불릿 / 구버전 저장분: thesis 문단 폴백.
        val keyPoints = a.get("key_points")?.takeIf { it.isArray && it.size() > 0 }?.map { it.asText() }
        if (keyPoints != null) listBlock("핵심 근거", keyPoints)
        else a.txt("thesis")?.let { append("<p>${esc(it)}</p>") }
        appendBenchmarkTable(a.get("benchmark_eval"))
        listBlock("진행 조건", a.get("conditions")?.map { it.asText() }?.filter { it.isNotBlank() })
        listBlock("Green Flags", a.get("green_flags")?.map { it.asText() })
        a.get("red_flags")?.let { rf ->
            if (rf.isArray && rf.size() > 0) {
                append("<h3>Red Flags</h3><ul class='risks'>")
                rf.forEach { append("<li><b>${esc(it.txt("flag") ?: "")}</b> <span class='tag'>${esc(it.txt("impact") ?: "")}</span> ${esc(it.txt("verify") ?: "")}</li>") }
                append("</ul>")
            }
        }
        listBlock("다음 단계", a.get("next_steps")?.map { it.asText() })
        confidenceNote(a)
    }

    private fun StringBuilder.appendMarketStudy(a: JsonNode) {
        a.txt("region")?.let { append("<p><b>권역</b> ${esc(it)} · <b>House View</b> ${esc(a.txt("house_view") ?: "-")}</p>") }
        a.txt("house_view_reason")?.let { append("<p>${esc(it)}</p>") }
        a.txt("fundamentals")?.let { append("<p>${esc(it)}</p>") }
        appendAssumptionTable(a.get("assumption_check"))
        appendCompsTable(a.get("comps"))
        a.txt("macro")?.let { append("<h3>매크로</h3><p>${esc(it)}</p>") }
        a.txt("conclusion")?.let { append("<p class='concl'>${esc(it)}</p>") }
        confidenceNote(a)
    }

    private fun StringBuilder.appendUnderwriting(a: JsonNode) {
        verdict(a.txt("recommendation"), a.txt("recommendation_reason"))
        a.txt("summary")?.let { append("<p>${esc(it)}</p>") }
        a.txt("guideline_check")?.let { append("<p class='concl'>${esc(it)}</p>") }
        listBlock("주요 동인", a.get("key_drivers")?.map { it.asText() })
        a.get("key_risks")?.let { kr ->
            if (kr.isArray && kr.size() > 0) {
                append("<h3>리스크</h3><ul class='risks'>")
                kr.forEach { append("<li>${esc(it.txt("risk") ?: "")} <span class='tag'>${esc(it.txt("impact") ?: "")}</span></li>") }
                append("</ul>")
            }
        }
    }

    private fun StringBuilder.appendIcMemo(a: JsonNode) {
        verdict(a.txt("recommendation"), a.txt("recommendation_reason"))
        a.txt("thesis")?.let { append("<p class='thesis'>${esc(it)}</p>") }
        appendExecSummary(a.get("exec_summary"))
        listBlock("투자 하이라이트", a.get("highlights")?.map { it.asText() })
        a.get("risk_matrix")?.let { rm ->
            if (rm.isArray && rm.size() > 0) {
                append("<h3>리스크 매트릭스</h3><table class='grid'><thead><tr><th>리스크</th><th>발생</th><th>영향</th><th>완화</th></tr></thead><tbody>")
                rm.forEach {
                    append("<tr><td>${esc(it.txt("risk") ?: "")}</td><td>${esc(it.txt("likelihood") ?: "")}</td><td>${esc(it.txt("impact") ?: "")}</td><td>${esc(it.txt("mitigation") ?: "")}</td></tr>")
                }
                append("</tbody></table>")
            }
        }
    }

    private fun StringBuilder.appendProFormaTable(pf: JsonNode) {
        val rows = pf.get("proForma") ?: return
        if (!rows.isArray || rows.size() == 0) return
        append("<section class='step'><h2>연차별 운영</h2>")
        append("<table class='grid'><thead><tr><th>연차</th><th>NOI</th><th>이자</th><th>Levered CF</th><th>DSCR</th><th>CoC%</th></tr></thead><tbody>")
        rows.forEach { r ->
            append("<tr><td>Y${esc(r.txt("year") ?: "")}</td><td>${esc(r.txt("noi") ?: "")}</td><td>${esc(r.txt("interest") ?: "")}</td><td>${esc(r.txt("leveredCf") ?: "")}</td><td>${esc(r.txt("dscr") ?: "")}</td><td>${esc(r.txt("cocPct") ?: "")}</td></tr>")
        }
        append("</tbody></table></section>")
    }

    // ── 단계 보조 표 렌더러 (인라인 화면과 동일 데이터) ──

    /** 스크리닝 핵심지표 9종 표. metrics 객체에 존재하는 값만. */
    private fun StringBuilder.appendKpiTable(m: JsonNode?) {
        if (m == null || m.isNull || !m.isObject) return
        val rows = KPI_FIELDS.mapNotNull { (key, label, unit) ->
            m.get(key)?.takeIf { it.isNumber || (it.isTextual && it.asText().isNotBlank()) }
                ?.let { Triple(label, it.asText(), unit) }
        }
        if (rows.isEmpty()) return
        append("<h3>핵심 지표</h3><table class='grid'><tbody>")
        rows.forEach { (label, v, unit) -> append("<tr><td>${esc(label)}</td><td>${esc(v)}${esc(unit)}</td></tr>") }
        append("</tbody></table>")
    }

    /** 벤치마크 대조표 — 신호등(rating) + 지표/값/기준. */
    private fun StringBuilder.appendBenchmarkTable(b: JsonNode?) {
        if (b == null || !b.isArray || b.size() == 0) return
        append("<h3>지표 점검</h3><table class='grid'><thead><tr><th>지표</th><th>값</th><th>기준</th><th>판정</th></tr></thead><tbody>")
        b.forEach {
            val rating = it.txt("rating") ?: ""
            append("<tr><td>${esc(it.txt("metric") ?: "")}</td><td>${esc(it.txt("value") ?: "-")}</td><td>${esc(it.txt("guideline") ?: "")}</td><td><span class='tag ${signalClass(rating)}'>${esc(rating)}</span></td></tr>")
        }
        append("</tbody></table>")
    }

    /** 시장조사 가정 검증표 — 가정/시장데이터/판정(verdict). */
    private fun StringBuilder.appendAssumptionTable(c: JsonNode?) {
        if (c == null || !c.isArray || c.size() == 0) return
        append("<h3>가정 검증</h3><table class='grid'><thead><tr><th>가정</th><th>시장 데이터</th><th>판정</th></tr></thead><tbody>")
        c.forEach {
            val v = it.txt("verdict") ?: ""
            append("<tr><td>${esc(it.txt("assumption") ?: "")}</td><td>${esc(it.txt("market") ?: "")}</td><td><span class='tag ${signalClass(v)}'>${esc(v)}</span></td></tr>")
        }
        append("</tbody></table>")
    }

    /** 시장조사 거래 사례표. */
    private fun StringBuilder.appendCompsTable(c: JsonNode?) {
        if (c == null || !c.isArray || c.size() == 0) return
        append("<h3>거래 사례</h3><table class='grid'><thead><tr><th>사례</th><th>권역</th><th>평당가(만원)</th><th>Cap(%)</th></tr></thead><tbody>")
        c.forEach {
            append("<tr><td>${esc(it.txt("name") ?: "-")}</td><td>${esc(it.txt("region") ?: "-")}</td><td>${esc(it.txt("price_per_pyeong_manwon") ?: "-")}</td><td>${esc(it.txt("cap_rate_pct") ?: "-")}</td></tr>")
        }
        append("</tbody></table>")
    }

    /** 투심 Exec Summary 요약표(자산/매입가/전략/기대수익/추천). */
    private fun StringBuilder.appendExecSummary(e: JsonNode?) {
        if (e == null || e.isNull || !e.isObject) return
        val rows = EXEC_FIELDS.mapNotNull { (key, label) -> e.txt(key)?.let { label to it } }
        if (rows.isEmpty()) return
        append("<h3>요약</h3><table class='kv'>")
        rows.forEach { (label, v) -> append("<tr><th>${esc(label)}</th><td>${esc(v)}</td></tr>") }
        append("</table>")
    }

    private fun StringBuilder.confidenceNote(a: JsonNode) {
        a.get("confidence")?.takeIf { !it.isNull }?.asText()?.takeIf { it.isNotBlank() }
            ?.let { append("<p class='conf'>신뢰도 ${esc(it)}</p>") }
    }

    /** G/Y/R · GREEN/YELLOW/RED → 신호등 클래스. */
    private fun signalClass(v: String): String = when (v.trim().uppercase().firstOrNull()) {
        'G' -> "go"
        'R' -> "no"
        else -> "cond"
    }

    // ── 작은 HTML 헬퍼 ──

    private fun StringBuilder.verdict(label: String?, reason: String?) {
        if (label.isNullOrBlank()) return
        val cls = verdictClass(label)
        append("<div class='verdict $cls'><span class='v-label'>${esc(label)}</span>")
        if (!reason.isNullOrBlank()) append("<span class='v-reason'>${esc(reason)}</span>")
        append("</div>")
    }

    private fun StringBuilder.listBlock(title: String, items: List<String>?) {
        if (items.isNullOrEmpty()) return
        append("<h3>${esc(title)}</h3><ul>")
        items.forEach { append("<li>${esc(it)}</li>") }
        append("</ul>")
    }

    private fun StringBuilder.metric(k: String, v: String?) {
        append("<div class='m'><div class='k'>${esc(k)}</div><div class='v'>${esc(v ?: "-")}</div></div>")
    }

    private fun StringBuilder.row(k: String, v: String?) {
        if (v.isNullOrBlank()) return
        append("<tr><th>${esc(k)}</th><td>${esc(v)}</td></tr>")
    }

    private fun StringBuilder.appendHtmlHead(title: String) {
        append("<!doctype html><html lang='ko'><head><meta charset='utf-8'>")
        append("<meta name='viewport' content='width=device-width, initial-scale=1'>")
        append("<title>${esc(title)} · 투자 분석 보고서</title>")
        append("<style>$CSS</style></head><body>")
    }

    private fun verdictClass(label: String): String = when (label.uppercase()) {
        "GO", "STRONG_BUY" -> "go"
        "NO_GO", "PASS" -> "no"
        else -> "cond"
    }

    /** HTML 이스케이프 (저장된 AI 텍스트의 마크업/인젝션 차단). */
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

    private fun JsonNode.txt(field: String): String? =
        get(field)?.takeIf { !it.isNull }?.asText()?.takeIf { it.isNotBlank() }

    private fun JsonNode.num(field: String): String? =
        get(field)?.takeIf { it.isNumber }?.asText()

    companion object {
        /** 스크리닝 핵심지표 — (JSON 키, 라벨, 단위). */
        private val KPI_FIELDS = listOf(
            Triple("asking_price_eok", "매입가", "억"),
            Triple("price_per_pyeong_manwon", "평당가", "만원"),
            Triple("noi_eok", "NOI", "억"),
            Triple("cap_rate_pct", "Cap Rate", "%"),
            Triple("occupancy_pct", "임대율", "%"),
            Triple("walt_yr", "WALT", "년"),
            Triple("top1_tenant_pct", "최대임차인 비중", "%"),
            Triple("loss_to_lease_pct", "Loss-to-Lease", "%"),
            Triple("opex_ratio_pct", "OPEX 비율", "%"),
        )

        /** 투심 Exec Summary — (JSON 키, 라벨). */
        private val EXEC_FIELDS = listOf(
            "asset" to "자산", "price" to "매입가", "strategy" to "전략",
            "expected_return" to "기대수익", "recommendation" to "추천",
        )

        private val CSS = """
            :root { --ink:#22252b; --soft:#5b6068; --line:#e6e3dd; --accent:#2d3aa8;
                    --go:#1f7a4d; --cond:#9a6a14; --no:#b23b2e; }
            * { box-sizing:border-box; }
            body { margin:0; font-family:'Inter',system-ui,'Malgun Gothic',sans-serif; color:var(--ink);
                   background:#f4f2ee; line-height:1.6; }
            .report { max-width:900px; margin:0 auto; padding:48px 40px; background:#fff; }
            .cover { border-bottom:2px solid var(--ink); padding-bottom:20px; margin-bottom:28px; }
            .brand { font-size:14px; letter-spacing:.04em; color:var(--accent); font-weight:700; }
            .doc-type { font-size:12px; text-transform:uppercase; letter-spacing:.1em; color:var(--soft); margin-top:4px; }
            h1 { font-size:30px; margin:14px 0 18px; letter-spacing:-.02em; }
            h2 { font-size:18px; margin:0 0 12px; padding-bottom:6px; border-bottom:1px solid var(--line); }
            h3 { font-size:13px; text-transform:uppercase; letter-spacing:.05em; color:var(--soft); margin:16px 0 6px; }
            .kv { border-collapse:collapse; font-size:14px; }
            .kv th { text-align:left; color:var(--soft); font-weight:600; padding:3px 18px 3px 0; }
            .step { margin:28px 0; page-break-inside:avoid; }
            .metrics { display:grid; grid-template-columns:repeat(3,1fr); gap:1px; background:var(--line);
                       border:1px solid var(--line); border-radius:10px; overflow:hidden; margin:20px 0; }
            .m { background:#fff; padding:14px 16px; }
            .m .k { font-size:11px; text-transform:uppercase; letter-spacing:.04em; color:var(--soft); }
            .m .v { font-size:24px; font-weight:600; letter-spacing:-.02em; font-variant-numeric:tabular-nums; }
            .verdict { display:flex; gap:12px; align-items:baseline; padding:10px 14px; border-radius:10px; margin:10px 0; }
            .verdict.go { background:#eaf6ef; } .verdict.cond { background:#faf3e2; } .verdict.no { background:#fbece9; }
            .v-label { font-weight:700; } .go .v-label{color:var(--go);} .cond .v-label{color:var(--cond);} .no .v-label{color:var(--no);}
            .v-reason { color:var(--soft); font-size:14px; }
            .thesis { font-size:16px; font-weight:500; }
            .concl { background:#f7f6f3; border-left:3px solid var(--accent); padding:10px 14px; border-radius:6px; }
            ul { margin:6px 0; padding-left:20px; } li { margin:3px 0; }
            ul.risks { list-style:none; padding-left:0; } ul.risks li { padding:4px 0; border-bottom:1px solid var(--line); }
            .tag { display:inline-block; font-size:11px; background:#eee; border-radius:4px; padding:1px 6px; color:var(--soft); }
            .tag.go { background:#eaf6ef; color:var(--go); } .tag.cond { background:#faf3e2; color:var(--cond); } .tag.no { background:#fbece9; color:var(--no); }
            .conf { font-size:12px; color:var(--soft); margin:8px 0 0; }
            table.grid { width:100%; border-collapse:collapse; font-size:13px; margin-top:8px; }
            table.grid th, table.grid td { border:1px solid var(--line); padding:6px 8px; text-align:right; }
            table.grid th:first-child, table.grid td:first-child { text-align:left; }
            table.grid thead th { background:#f7f6f3; color:var(--soft); }
            .raw { white-space:pre-wrap; font-size:13px; color:var(--soft); }
            .disclaimer { margin-top:36px; padding-top:16px; border-top:1px solid var(--line); font-size:12px; color:var(--soft); }
            @media print { body { background:#fff; } .report { padding:0; max-width:none; } }
        """.trimIndent()
    }
}
