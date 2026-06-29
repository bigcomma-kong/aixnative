package com.aixnative.marketfeed

import com.aixnative.ai.AiServiceManager
import com.aixnative.billing.CreditGate
import com.aixnative.billing.CreditService
import com.aixnative.common.Disclaimer
import com.aixnative.common.tenant.TenantContext
import com.aixnative.common.web.BadRequestException
import com.aixnative.common.web.ServiceUnavailableException
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

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

        val ai = creditGate.charge { aiServiceManager.complete(deepReportPrompt(cards, briefing, focus)) }
        val node = extractJson(ai.text)?.let { objectMapper.readTree(it) }

        val current = TenantContext.require()
        return MarketDeepReportView(
            headline = node?.path("headline")?.asText("")?.ifBlank { null },
            summary = node?.path("summary")?.asText("")?.ifBlank { null },
            sections = node?.path("sections")?.let { parseNode(it) } ?: emptyList(),
            picks = node?.path("picks")?.let { parseNode(it) } ?: emptyList(),
            provider = ai.provider,
            creditBalance = creditService.balance(current.tenantId, current.userId),
            disclaimer = Disclaimer.TEXT,
        )
    }

    private fun deepReportPrompt(cards: List<MarketFeedItem>, briefing: MarketBriefing?, focus: String?): String {
        val deals = cards.mapIndexed { i, c ->
            "${i + 1}. ${c.title}${c.assetType?.let { " [$it]" } ?: ""}${c.summary?.let { " — ${it.take(160)}" } ?: ""}"
        }.joinToString("\n")
        val context = briefing?.let { "오늘의 브리핑 헤드라인: ${it.headline ?: ""}\n전망: ${it.outlook ?: ""}\n\n" } ?: ""
        val focusLine = focus?.takeIf { it.isNotBlank() }?.let { "분석 초점(사용자 요청): $it\n\n" } ?: ""
        return """
            당신은 상업용 부동산(CRE) 시장 전략 애널리스트입니다. 아래 최근 딜·시장 데이터를 종합해
            기관 투자자용 **심층 시장 리포트**를 작성하세요. 무료 일일 브리핑보다 한 단계 깊은 통찰
            — 섹터별 모멘텀, 거래 흐름의 함의, 매크로·금리 시사점, 실행 가능한 액션을 담으세요.
            제공된 데이터 범위에서만 분석하고 추측을 사실처럼 단정하지 마세요.

            반드시 아래 스키마의 **JSON 객체 하나만** 출력하세요(코드블록·설명 금지):
            {
              "headline": "리포트 한 줄 요약(80자 이내)",
              "summary": "핵심 요지 3~4문장",
              "sections": [{"title":"섹션 제목","body":"분석 본문(2~5문장)"}],
              "picks": [{"title":"주목 딜/테마","why":"투자 관점에서 주목할 이유"}]
            }
            sections 3~5개(예: 섹터 동향 / 거래 모멘텀 / 금리·매크로 / 리스크·기회), picks 2~4개. 모든 값 한국어.

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
        briefingRepository.findTopByOrderByGeneratedAtDesc()?.let { b ->
            MarketBriefingView(
                id = b.id ?: 0,
                briefingDate = b.briefingDate?.toString(),
                headline = b.headline,
                outlook = b.outlook,
                sections = parseList(b.sectionsJson),
                watchlist = parseList(b.watchlistJson),
                risks = parseList(b.risksJson),
                articleCount = b.articleCount,
                provider = b.aiProvider,
                generatedAt = b.generatedAt,
            )
        }

    private inline fun <reified T> parseList(json: String?): List<T> {
        if (json.isNullOrBlank()) return emptyList()
        return runCatching {
            objectMapper.readValue(json, object : TypeReference<List<T>>() {})
        }.getOrDefault(emptyList())
    }
    /** 최신 피드 N개(기본 [DEFAULT_LIMIT], 최대 [MAX_LIMIT]). */
    @Transactional(readOnly = true)
    fun latest(limit: Int = DEFAULT_LIMIT): List<MarketFeedItemView> {
        val capped = limit.coerceIn(1, MAX_LIMIT)
        return repository.findAllByOrderByPublishedAtDescIdDesc(PageRequest.of(0, capped))
            .map { it.toView() }
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
    }
}
