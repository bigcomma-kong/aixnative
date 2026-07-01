package com.aixnative.marketfeed.service

import com.aixnative.ai.service.AiServiceManager
import com.aixnative.ai.service.AiToolRunService
import com.aixnative.ai.domain.RunStatus
import com.aixnative.billing.service.CreditGate
import com.aixnative.billing.service.CreditService
import com.aixnative.billing.domain.ToolPricing
import com.aixnative.common.Disclaimer
import com.aixnative.common.tenant.TenantContext
import com.aixnative.common.web.BadRequestException
import com.aixnative.common.web.NotFoundException
import com.aixnative.common.web.ServiceUnavailableException
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import com.aixnative.marketfeed.domain.MarketBriefing
import com.aixnative.marketfeed.domain.MarketFeedItem
import com.aixnative.marketfeed.repository.MarketBriefingRepository
import com.aixnative.marketfeed.repository.MarketFeedRepository
import com.aixnative.marketfeed.web.BriefingHistoryItem
import com.aixnative.marketfeed.web.DeepReportHistoryItem
import com.aixnative.marketfeed.web.MarketBriefingView
import com.aixnative.marketfeed.web.MarketDeepReportView
import com.aixnative.marketfeed.web.MarketFeedCreateRequest
import com.aixnative.marketfeed.web.MarketFeedItemView
import com.aixnative.marketfeed.web.MarketFeedPage
import com.aixnative.marketfeed.web.toView

/**
 * 시장 인텔리전스 피드. 글로벌 콘텐츠라 테넌트 스코프가 없다.
 * 읽기는 누구나(인증), 쓰기는 ADMIN 전용(컨트롤러에서 SecurityConfig 로 게이트).
 * 심층 리포트는 Claude 호출 + 크레딧 1 차감(무료 브리핑과 구분되는 수익 액션).
 */
@Service
class MarketFeedService(
    private val repository: MarketFeedRepository,
    private val briefingRepository: MarketBriefingRepository,
    private val objectMapper: ObjectMapper,
    private val aiServiceManager: AiServiceManager,
    private val creditGate: CreditGate,
    private val creditService: CreditService,
    private val aiToolRunService: AiToolRunService,
) {

    /**
     * AI 심층 시장 리포트 — 최근 딜 풀 + 브리핑을 Claude 가 종합. 성공 시 크레딧 1 차감.
     * 무료 브리핑(Mistral)과 달리 깊이·맞춤(focus)을 제공하는 온디맨드 과금 액션.
     */
    @Transactional
    fun deepReport(focus: String?): MarketDeepReportView {
        if (!aiServiceManager.hasConfiguredProvider()) {
            throw ServiceUnavailableException("AI 분석 서비스가 설정되지 않았습니다(API 키 미설정).")
        }
        val cards = repository.findAllByOrderByPublishedAtDescIdDesc(PageRequest.of(0, DEEP_CARD_SAMPLE))
        if (cards.isEmpty()) throw BadRequestException("분석할 시장 데이터가 아직 없습니다. 잠시 후 다시 시도해 주세요.")
        val briefing = briefingRepository.findTopByOrderByGeneratedAtDesc()

        val ai = creditGate.charge(ToolPricing.costOf(DEEP_REPORT_TOOL)) { aiServiceManager.complete(deepReportPrompt(cards, briefing, focus)) }
        val node = extractJson(ai.text)?.let { objectMapper.readTree(it) }

        val current = TenantContext.require()
        val view = MarketDeepReportView(
            headline = node?.path("headline")?.asText("")?.ifBlank { null },
            summary = node?.path("summary")?.asText("")?.ifBlank { null },
            marketTempScore = node?.path("marketTempScore")?.takeIf { it.isNumber }?.asInt(),
            marketTempLabel = node?.path("marketTempLabel")?.asText("")?.ifBlank { null },
            sectors = node?.path("sectors")?.let { parseNode(it) } ?: emptyList(),
            scenarios = node?.path("scenarios")?.let { parseNode(it) } ?: emptyList(),
            sections = node?.path("sections")?.let { parseNode(it) } ?: emptyList(),
            picks = node?.path("picks")?.let { parseNode(it) } ?: emptyList(),
            contrarian = node?.path("contrarian")?.asText("")?.ifBlank { null },
            provider = ai.provider,
            creditBalance = creditService.balance(current.tenantId, current.userId),
            disclaimer = Disclaimer.TEXT,
        )
        // 이력 보존 — 닫거나 새로고침해도 '지난 심층 리포트'에서 다시 볼 수 있도록 저장.
        aiToolRunService.record(
            tool = DEEP_REPORT_TOOL,
            status = RunStatus.SUCCESS,
            dealName = view.headline?.take(200) ?: "심층 시장 분석",
            requestJson = focus?.takeIf { it.isNotBlank() }?.let { """{"focus":${objectMapper.writeValueAsString(it)}}""" },
            resultJson = objectMapper.writeValueAsString(view),
        )
        return view
    }

    /** 현재 사용자의 지난 심층 리포트 목록(최신순). */
    @Transactional(readOnly = true)
    fun deepReportHistory(): List<DeepReportHistoryItem> =
        aiToolRunService.listMine()
            .filter { it.tool == DEEP_REPORT_TOOL && it.status == RunStatus.SUCCESS }
            .map { DeepReportHistoryItem(id = it.id ?: 0, headline = it.dealName, generatedAt = it.createdAt) }

    /** 저장된 심층 리포트 단건 재조회(테넌트 스코프). creditBalance 는 현재 잔액으로 갱신. */
    @Transactional(readOnly = true)
    fun deepReportById(id: Long): MarketDeepReportView {
        val run = aiToolRunService.get(id)
        if (run.tool != DEEP_REPORT_TOOL) throw NotFoundException("심층 리포트를 찾을 수 없습니다.")
        val json = run.resultJson ?: throw NotFoundException("심층 리포트 내용이 비어 있습니다.")
        val saved = objectMapper.readValue(json, MarketDeepReportView::class.java)
        val current = TenantContext.require()
        return saved.copy(creditBalance = creditService.balance(current.tenantId, current.userId))
    }

    private fun deepReportPrompt(cards: List<MarketFeedItem>, briefing: MarketBriefing?, focus: String?): String {
        val deals = cards.mapIndexed { i, c ->
            "${i + 1}. ${c.title}${c.assetType?.let { " [$it]" } ?: ""}${c.summary?.let { " — ${it.take(160)}" } ?: ""}"
        }.joinToString("\n")
        val context = briefing?.let { "오늘의 브리핑 헤드라인: ${it.headline ?: ""}\n전망: ${it.outlook ?: ""}\n\n" } ?: ""
        val focusLine = focus?.takeIf { it.isNotBlank() }?.let { "분석 초점(사용자 요청): $it\n\n" } ?: ""
        return """
            당신은 기관 LP·GP 를 자문하는 상업용 부동산(CRE) 시장 전략 책임자입니다. 아래 최근 딜·시장
            데이터를 종합해 **유료 심층 시장 리포트**를 작성하세요. 이 리포트는 무료 일일 브리핑(단순 요약)
            과 명확히 차별화되어야 합니다 — 즉, 단순 뉴스 요약이 아니라 (1) 섹터별 정량 스탠스, (2) 시나리오
            분기, (3) 컨트래리안 관점, (4) 확신도·리스크가 명시된 실행 픽까지 담은 **하우스 뷰**여야 합니다.
            제공된 데이터 범위에서 근거를 들어 분석하되, 데이터에 없는 수치를 지어내지 마세요.

            반드시 아래 스키마의 **JSON 객체 하나만** 출력하세요(코드블록·설명 금지):
            {
              "headline": "하우스 뷰 한 줄(80자 이내)",
              "summary": "핵심 논지 4~5문장(왜 지금 이 포지션인가)",
              "marketTempScore": 0~100 정수(시장 과열도; 거래·심리 종합),
              "marketTempLabel": "침체 | 둔화 | 중립 | 회복 | 과열 중 하나",
              "sectors": [{"name":"오피스/물류/리테일/호텔/주거 등","stance":"비중확대|중립|비중축소","score":0~100,"note":"한 줄 근거"}],
              "scenarios": [{"name":"기본","narrative":"전개와 함의 2~3문장"},{"name":"낙관","narrative":"..."},{"name":"비관","narrative":"..."}],
              "sections": [{"title":"섹션 제목","body":"분석 본문 2~3문장(함의 중심)","bullets":["핵심 포인트 한 줄(2~4개, 가독성)"]}],
              "picks": [{"title":"주목 딜/테마","why":"투자 논리","conviction":"높음|중간|낮음","risk":"핵심 리스크 한 줄"}],
              "contrarian": "시장 컨센서스가 놓치고 있는 점 2~3문장"
            }
            sectors 는 데이터에 등장하는 자산군 4~5개, scenarios 는 기본/낙관/비관 3개 필수, sections 4~6개
            (예: 자본시장·금리 / 거래 모멘텀 / 섹터 로테이션 / 정책·규제 / 리스크·촉매), picks 3~4개. 모든 값 한국어.

            $focusLine$context<딜·시장 데이터>
            $deals
            </딜·시장 데이터>
        """.trimIndent()
    }

    private inline fun <reified T> parseNode(node: com.fasterxml.jackson.databind.JsonNode): List<T> =
        if (node.isArray) runCatching { objectMapper.convertValue(node, object : TypeReference<List<T>>() {}) }.getOrDefault(emptyList())
        else emptyList()

    /** 코드펜스/잡텍스트 제거 후 첫 '{' ~ 마지막 '}' 추출. */
    private fun extractJson(raw: String): String? {
        val s = raw.indexOf('{'); val e = raw.lastIndexOf('}')
        return if (s in 0 until e) raw.substring(s, e + 1) else null
    }

    /** 최신 마켓 브리핑 1건(없으면 null). sections/watchlist/risks JSON 을 파싱해 반환. */
    @Transactional(readOnly = true)
    fun latestBriefing(): MarketBriefingView? =
        briefingRepository.findTopByOrderByGeneratedAtDesc()?.let { it.toView() }

    /** 지난 브리핑 아카이브 목록(최신순, 최대 30건). 본문은 /briefing/{id} 로 조회. */
    @Transactional(readOnly = true)
    fun briefingHistory(): List<BriefingHistoryItem> =
        briefingRepository.findTop30ByOrderByGeneratedAtDesc().map { b ->
            BriefingHistoryItem(
                id = b.id ?: 0,
                briefingDate = b.briefingDate?.toString(),
                headline = b.headline,
                articleCount = b.articleCount,
                generatedAt = b.generatedAt,
            )
        }

    /** 저장된 브리핑 단건 재조회(없으면 404). */
    @Transactional(readOnly = true)
    fun briefingById(id: Long): MarketBriefingView =
        briefingRepository.findById(id).orElseThrow { NotFoundException("브리핑을 찾을 수 없습니다.") }.toView()

    private fun MarketBriefing.toView(): MarketBriefingView =
        MarketBriefingView(
            id = id ?: 0,
            briefingDate = briefingDate?.toString(),
            headline = headline,
            outlook = outlook,
            sections = parseList(sectionsJson),
            watchlist = parseList(watchlistJson),
            risks = parseList(risksJson),
            articleCount = articleCount,
            provider = aiProvider,
            generatedAt = generatedAt,
        )

    private inline fun <reified T> parseList(json: String?): List<T> {
        if (json.isNullOrBlank()) return emptyList()
        return runCatching {
            objectMapper.readValue(json, object : TypeReference<List<T>>() {})
        }.getOrDefault(emptyList())
    }
    /**
     * 피드 N개(최신순). [page] 0-기반 — 과거 딜 더 보기(아카이브)용 페이지네이션.
     * 응답은 [items] + [hasMore](다음 페이지 존재 여부)로 감싸 무한/더보기 UI 를 지원.
     */
    @Transactional(readOnly = true)
    fun latest(limit: Int = DEFAULT_LIMIT, page: Int = 0): MarketFeedPage {
        val capped = limit.coerceIn(1, MAX_LIMIT)
        val safePage = page.coerceAtLeast(0)
        val slice = repository.findAllByOrderByPublishedAtDescIdDesc(PageRequest.of(safePage, capped))
        val hasMore = (safePage + 1).toLong() * capped < repository.count()
        return MarketFeedPage(
            items = slice.map { it.toView() },
            page = safePage,
            hasMore = hasMore,
        )
    }

    @Transactional
    fun create(req: MarketFeedCreateRequest): MarketFeedItemView {
        val item = MarketFeedItem(
            title = req.title.trim(),
            summary = req.summary?.trim()?.ifBlank { null },
            assetType = req.assetType?.trim()?.ifBlank { null },
            location = req.location?.trim()?.ifBlank { null },
            sourceText = req.sourceText?.trim()?.ifBlank { null },
            sourceUrl = req.sourceUrl?.trim()?.ifBlank { null },
            publishedAt = req.publishedAt ?: Instant.now(),
            origin = "ADMIN",
        )
        return repository.save(item).toView()
    }

    @Transactional
    fun delete(id: Long) {
        repository.deleteById(id)
    }

    private companion object {
        const val DEFAULT_LIMIT = 30
        const val MAX_LIMIT = 100
        const val DEEP_CARD_SAMPLE = 40
        const val DEEP_REPORT_TOOL = "MARKET_DEEP_REPORT"
    }
}
