package com.aixnative.underwriting.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.aixnative.ai.service.AiToolRunService
import com.aixnative.common.Disclaimer
import com.aixnative.common.web.NotFoundException
import org.springframework.stereotype.Service
import com.aixnative.underwriting.domain.AnalysisType

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
    fun buildHtml(runId: Long): String =
        buildFrom(aiToolRunService.get(runId), aiToolRunService.listMine())

    /** 공유 토큰으로 공개(무인증) 보고서 HTML. 토큰의 소유자 범위로 딜 단계를 모은다. */
    fun buildHtmlByToken(token: String): String {
        val anchor = aiToolRunService.getByShareToken(token)
            ?: throw NotFoundException("공유된 보고서를 찾을 수 없습니다.")
        return buildFrom(anchor, aiToolRunService.listForOwner(anchor.tenantId, anchor.ownerUserId))
    }

    private fun buildFrom(anchor: com.aixnative.ai.domain.AiToolRun, ownerRuns: List<com.aixnative.ai.domain.AiToolRun>): String {
        val dealName = anchor.dealName

        // 같은 딜의 단계별 최신 결과 (ownerRuns 는 최신순). dealName 이 같으면(둘 다 null 포함) 합본.
        val byTool = LinkedHashMap<AnalysisType, JsonNode>()
        for (run in ownerRuns) {
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

        val generatedAt = java.time.ZonedDateTime.now(java.time.ZoneId.of("Asia/Seoul"))
            .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))

        val sb = StringBuilder()
        sb.appendHtmlHead(dealName ?: "(이름없음)")
        sb.append("<main class='report'>")
        // 인쇄(PDF 저장) 툴바 — 화면 전용(인쇄 시 숨김).
        sb.append("<div class='toolbar no-print'><button onclick='window.print()'>PDF로 저장 · 인쇄</button>")
        sb.append("<span class='gen'>생성 $generatedAt KST</span></div>")
        sb.appendCover(dealName, requestNode, generatedAt)
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
        sb.append("<footer class='disclaimer'>")
        sb.append("<p class='src'><b>데이터 출처</b> — 한국은행 ECOS(매크로) · 국토교통부 RTMS(실거래) · 한국부동산원 R-ONE(공실·임대·수익률) · V-World(공시지가). ")
        sb.append("수치(IRR·EM·DSCR·민감도)는 결정론적 코드 계산이며, AI는 확정 수치를 근거로 서술·심사만 합니다.</p>")
        sb.append("<p>${esc(Disclaimer.TEXT)}</p>")
        sb.append("</footer>")
        sb.append("</main></body></html>")
        return sb.toString()
    }

    // ── 섹션 렌더러 ──

    private fun StringBuilder.appendCover(dealName: String?, req: JsonNode?, generatedAt: String) {
        append("<header class='cover'>")
        append("<div class='brand'>AixNative</div>")
        append("<div class='doc-type'>투자 분석 보고서 · Investment Memorandum</div>")
        append("<h1>${esc(dealName ?: "(이름없음)")}</h1>")
        append("<div class='gen-line'>생성일시 ${esc(generatedAt)} (KST) · aixnative AI 딜 언더라이팅</div>")
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
        (a.txt("investment_thesis") ?: a.txt("thesis"))?.let { append("<h3>투자 논리</h3><p>${esc(it)}</p>") }
        // 추가 핵심 근거 — key_points 불릿(있으면).
        a.get("key_points")?.takeIf { it.isArray && it.size() > 0 }?.map { it.asText() }
            ?.let { listBlock("핵심 근거", it) }
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
        a.txt("house_view_reason")?.let { append("<h3>하우스뷰 근거</h3><p>${esc(it)}</p>") }
        // fundamentals — 신규: 불릿 배열 / 구버전: 문자열.
        a.get("fundamentals")?.let { f ->
            if (f.isArray && f.size() > 0) listBlock("권역 현황", f.map { it.asText() }.filter { it.isNotBlank() })
            else f.takeIf { it.isTextual && it.asText().isNotBlank() }?.let { append("<h3>권역 현황</h3><p>${esc(it.asText())}</p>") }
        }
        appendAssumptionTable(a.get("assumption_check"))
        appendCompsTable(a.get("comps"))
        a.txt("macro")?.let { append("<h3>매크로</h3><p>${esc(it)}</p>") }
        a.txt("conclusion")?.let { append("<p class='concl'>${esc(it)}</p>") }
        confidenceNote(a)
    }

    private fun StringBuilder.appendUnderwriting(a: JsonNode) {
        verdict(a.txt("recommendation"), a.txt("recommendation_reason"))
        // 신규 스캔형 스키마(thesis·strengths·downside) 우선, 구버전(summary·guideline_check) 폴백.
        a.txt("thesis")?.let { append("<p class='thesis'>${esc(it)}</p>") }
        a.txt("summary")?.let { append("<p>${esc(it)}</p>") }
        listBlock("강점", a.get("strengths")?.map { it.asText() })
        listBlock("주요 동인", a.get("key_drivers")?.map { it.asText() })
        a.get("key_risks")?.let { kr ->
            if (kr.isArray && kr.size() > 0) {
                append("<h3>리스크</h3><ul class='risks'>")
                kr.forEach { append("<li>${esc(it.txt("risk") ?: "")} <span class='tag'>${esc(it.txt("impact") ?: "")}</span></li>") }
                append("</ul>")
            }
        }
        a.txt("guideline_check")?.let { append("<p class='concl'>${esc(it)}</p>") }
        a.txt("downside")?.let { append("<p class='concl'>⚠ 하방 · ${esc(it)}</p>") }
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
        a.txt("lp_alignment")?.let { append("<h3>LP 정합성</h3><p>${esc(it)}</p>") }
        confidenceNote(a)
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
        // 깔끔한 한글 웹폰트(Pretendard) - 로드 실패 시 시스템 폰트로 우아하게 폴백.
        append("<link rel='stylesheet' href='https://cdn.jsdelivr.net/gh/orioncactus/pretendard@v1.3.9/dist/web/variable/pretendardvariable.css'>")
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
            :root {
              --ink:oklch(24% 0.012 262); --soft:oklch(50% 0.012 262); --faint:oklch(62% 0.01 262);
              --line:oklch(90% 0.006 262); --line-2:oklch(94% 0.005 262);
              --paper:oklch(99.2% 0 0); --sunken:oklch(97% 0.005 262);
              --accent:oklch(51% 0.19 266); --accent-press:oklch(45% 0.19 266);
              --accent-2:oklch(58% 0.19 300); --accent-tint:oklch(95.5% 0.03 266);
              --go:oklch(50% 0.13 155); --go-tint:oklch(95.5% 0.04 155);
              --cond:oklch(56% 0.12 78); --cond-tint:oklch(95% 0.05 85);
              --no:oklch(54% 0.19 25); --no-tint:oklch(95% 0.04 25);
              --grad:linear-gradient(92deg, var(--accent), var(--accent-2));
              --shadow:0 1px 2px oklch(0% 0 0 / 0.04), 0 12px 32px -18px oklch(0% 0 0 / 0.22);
            }
            * { box-sizing:border-box; }
            body { margin:0; font-family:'Pretendard Variable',Pretendard,-apple-system,'Apple SD Gothic Neo','Malgun Gothic',system-ui,sans-serif;
                   color:var(--ink); background:oklch(96% 0.006 262); line-height:1.68; font-size:15px; letter-spacing:-0.003em;
                   -webkit-font-smoothing:antialiased; text-rendering:optimizeLegibility; }
            .report { max-width:920px; margin:24px auto; padding:0 0 44px; background:var(--paper);
                      border-radius:16px; box-shadow:var(--shadow); overflow:hidden; }
            .report > *:not(.cover):not(.metrics) { margin-left:44px; margin-right:44px; }

            /* ── Cover ── 상단 그라디언트 배너 */
            .cover { position:relative; padding:38px 44px 24px; margin-bottom:26px;
                     background:linear-gradient(168deg, var(--accent-tint), var(--paper) 78%);
                     border-bottom:1px solid var(--line); }
            .cover::before { content:''; position:absolute; top:0; left:0; right:0; height:5px; background:var(--grad); }
            .brand { font-size:13px; letter-spacing:.02em; font-weight:800; color:var(--accent);
                     background:var(--grad); -webkit-background-clip:text; background-clip:text; -webkit-text-fill-color:transparent; }
            .doc-type { font-size:11px; text-transform:uppercase; letter-spacing:.14em; color:var(--soft); margin-top:6px; font-weight:600; }
            h1 { font-size:32px; margin:12px 0 14px; letter-spacing:-.025em; line-height:1.15; font-weight:750; }
            .gen-line { font-size:12px; color:var(--faint); margin-top:6px; }
            .cover .kv { border-collapse:separate; border-spacing:0; font-size:13.5px; margin-top:16px;
                         display:grid; grid-template-columns:repeat(auto-fit, minmax(140px, 1fr)); gap:1px; }
            .cover .kv tbody { display:contents; }
            .cover .kv tr { display:flex; flex-direction:column; gap:2px; padding:8px 12px;
                            background:var(--paper); border:1px solid var(--line); border-radius:9px; }
            .cover .kv th { text-align:left; color:var(--faint); font-weight:600; font-size:10.5px;
                            text-transform:uppercase; letter-spacing:.05em; padding:0; }
            .cover .kv td { font-size:15px; font-weight:650; font-variant-numeric:tabular-nums; }

            /* ── Section headings ── */
            h2 { font-size:19px; margin:0 0 14px; letter-spacing:-.01em; position:relative; padding-left:14px; }
            h2::before { content:''; position:absolute; left:0; top:2px; bottom:2px; width:4px; border-radius:3px; background:var(--grad); }
            h3 { font-size:11.5px; text-transform:uppercase; letter-spacing:.07em; color:var(--faint); font-weight:700; margin:18px 0 7px; }
            .step { margin:26px 44px; page-break-inside:avoid; }

            /* ── Metric cards ── */
            .metrics { display:grid; grid-template-columns:repeat(3,1fr); gap:12px; margin:22px 44px 26px; }
            .m { position:relative; background:linear-gradient(165deg, var(--accent-tint), var(--paper) 72%);
                 border:1px solid var(--line); border-radius:13px; padding:15px 16px 16px; overflow:hidden; }
            .m::before { content:''; position:absolute; top:0; left:0; right:0; height:3px; background:var(--grad); opacity:.85; }
            .m .k { font-size:10.5px; text-transform:uppercase; letter-spacing:.05em; color:var(--soft); font-weight:600; }
            .m .v { font-size:27px; font-weight:700; letter-spacing:-.03em; font-variant-numeric:tabular-nums;
                    color:var(--accent-press); margin-top:3px; line-height:1.1; }

            /* ── Verdict pill ── */
            .verdict { display:flex; gap:12px; align-items:baseline; padding:12px 16px; border-radius:12px; margin:12px 0;
                       border:1px solid var(--line); }
            .verdict.go { background:var(--go-tint); border-color:oklch(88% 0.07 155); }
            .verdict.cond { background:var(--cond-tint); border-color:oklch(88% 0.07 85); }
            .verdict.no { background:var(--no-tint); border-color:oklch(88% 0.07 25); }
            .v-label { font-weight:800; font-size:15px; letter-spacing:.01em; }
            .go .v-label{color:var(--go);} .cond .v-label{color:var(--cond);} .no .v-label{color:var(--no);}
            .v-reason { color:var(--soft); font-size:14px; }

            .thesis { font-size:16px; font-weight:550; line-height:1.55; margin:12px 0; }
            .concl { background:var(--sunken); border-left:3px solid var(--accent); padding:11px 15px;
                     border-radius:0 8px 8px 0; margin:12px 0; font-size:14px; }
            p { margin:8px 0; }
            ul { margin:8px 0; padding-left:20px; } li { margin:4px 0; }
            ul li::marker { color:var(--accent); }
            ul.risks { list-style:none; padding-left:0; }
            ul.risks li { padding:8px 12px; margin:5px 0; border:1px solid var(--line); border-left:3px solid var(--no);
                          border-radius:0 8px 8px 0; background:var(--no-tint); }

            /* ── Signal tags ── */
            .tag { display:inline-block; font-size:11px; font-weight:700; background:var(--sunken);
                   border-radius:999px; padding:2px 9px; color:var(--soft); letter-spacing:.02em; }
            .tag.go { background:var(--go-tint); color:var(--go); }
            .tag.cond { background:var(--cond-tint); color:var(--cond); }
            .tag.no { background:var(--no-tint); color:var(--no); }
            .conf { font-size:12px; color:var(--faint); margin:10px 0 0; }

            /* ── Tables ── */
            table.grid { width:100%; border-collapse:separate; border-spacing:0; font-size:13px; margin-top:10px;
                         border:1px solid var(--line); border-radius:11px; overflow:hidden; }
            table.grid th, table.grid td { padding:8px 11px; text-align:right; border-bottom:1px solid var(--line-2); }
            table.grid th:first-child, table.grid td:first-child { text-align:left; }
            table.grid thead th { background:var(--sunken); color:var(--soft); font-size:11px;
                                  text-transform:uppercase; letter-spacing:.04em; }
            table.grid tbody tr:last-child td { border-bottom:none; }
            table.grid tbody tr:nth-child(even) td { background:oklch(98.5% 0.004 262); }
            .kv { border-collapse:collapse; font-size:14px; }
            .kv th { text-align:left; color:var(--soft); font-weight:600; padding:4px 18px 4px 0; }

            .raw { white-space:pre-wrap; font-size:13.5px; color:var(--soft); background:var(--sunken);
                   padding:12px 14px; border-radius:9px; }
            .disclaimer { margin:36px 44px 0; padding-top:16px; border-top:1px solid var(--line); font-size:12px; color:var(--faint); }
            .disclaimer .src { margin:0 0 8px; line-height:1.6; }

            /* ── Toolbar (screen only) ── */
            .toolbar { display:flex; align-items:center; justify-content:space-between; gap:12px; margin:20px 44px 4px; }
            .toolbar button { font-size:13px; font-weight:700; color:oklch(99% 0 0); background:var(--grad);
                              border:none; border-radius:9px; padding:9px 18px; cursor:pointer;
                              box-shadow:0 6px 16px -8px var(--accent); }
            .toolbar button:hover { filter:brightness(1.06); }
            .toolbar .gen { font-size:12px; color:var(--faint); }

            @media print {
              body { background:#fff; }
              .report { margin:0; max-width:none; border-radius:0; box-shadow:none; }
              .m, .metrics .m::before, .cover { -webkit-print-color-adjust:exact; print-color-adjust:exact; }
              .no-print { display:none !important; }
            }
        """.trimIndent()
    }
}
