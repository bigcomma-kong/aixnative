package com.aixnative.followup.service

import com.aixnative.ai.domain.AiToolRun
import com.aixnative.ai.domain.RunStatus
import com.aixnative.ai.service.AiJsonExtractor
import com.aixnative.ai.service.AiServiceManager
import com.aixnative.ai.service.AiToolRunService
import com.aixnative.billing.domain.ToolPricing
import com.aixnative.billing.service.CreditGate
import com.aixnative.billing.service.CreditService
import com.aixnative.common.tenant.TenantContext
import com.aixnative.common.web.BadRequestException
import com.aixnative.common.web.InsufficientCreditsException
import com.aixnative.common.web.ServiceUnavailableException
import com.aixnative.followup.domain.FollowUpRole
import com.aixnative.followup.domain.FollowUpTurn
import com.aixnative.followup.repository.FollowUpTurnRepository
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * 분석 결과 후속 논의 - 질문하기·검토 포인트 제안·반박 검토.
 *
 * 근거는 저장된 분석 결과(+같은 딜의 다른 단계 결과, 이전 논의)로 한정하고 새 데이터는 조회하지 않는다.
 * 원본 분석(ai_tool_run)은 건드리지 않고 ai_run_followup 에만 쌓는다.
 * 과금은 [CreditGate] - AI 호출(재요청 포함)이 성공했을 때만 차감한다.
 */
@Service
class FollowUpService(
    private val aiServiceManager: AiServiceManager,
    private val aiToolRunService: AiToolRunService,
    private val repository: FollowUpTurnRepository,
    private val creditGate: CreditGate,
    private val creditService: CreditService,
    private val objectMapper: ObjectMapper,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /** 결과 1건의 스레드(무료). 다른 테넌트의 run 이면 404. */
    fun thread(runId: Long): FollowUpThread {
        aiToolRunService.get(runId) // 테넌트 스코프 강제
        return FollowUpThread(runId, turns(runId), currentBalance())
    }

    /** 사용자 질문 → AI 답변. USER·AI 턴을 1개씩 쌓는다. */
    fun ask(runId: Long, question: String): FollowUpThread {
        val q = question.trim()
        if (q.isEmpty()) throw BadRequestException("질문을 입력해 주세요.")
        if (q.length > MAX_QUESTION_CHARS) throw BadRequestException("질문은 ${MAX_QUESTION_CHARS}자 이내로 입력해 주세요.")

        val run = requireAnalyzableRun(runId)
        val prior = turns(runId)
        val context = contextFor(run, prior)
        val prompt = if (isContractTool(run.tool)) {
            FollowUpPrompts.contractAsk(context, q, perspectiveLabelOf(run.resultJson))
        } else {
            FollowUpPrompts.ask(context, q)
        }
        val (provider, answer) = charged(ASK_TOOL) { completeMarkdown(prompt, "답변") }

        save(run, prior.size, FollowUpRole.USER, q, null)
        save(run, prior.size + 1, FollowUpRole.AI, answer, provider)
        return FollowUpThread(runId, turns(runId), currentBalance())
    }

    /** AI 가 먼저 검토 포인트를 질문 형태로 제안. SUGGEST 턴 1개를 쌓는다. */
    fun suggest(runId: Long): FollowUpThread {
        val run = requireAnalyzableRun(runId)
        val prior = turns(runId)
        val prompt = FollowUpPrompts.suggest(contextFor(run, prior))
        val (provider, body) = charged(SUGGEST_TOOL) { completeMarkdown(prompt, "제안") }

        save(run, prior.size, FollowUpRole.SUGGEST, body, provider)
        return FollowUpThread(runId, turns(runId), currentBalance())
    }

    /** 독립 반박 검토. 판정 JSON 을 CRITIQUE 턴으로 쌓는다. */
    fun critique(runId: Long): FollowUpThread {
        val run = requireAnalyzableRun(runId)
        val prior = turns(runId)
        val label = run.dealName?.takeIf { it.isNotBlank() } ?: "분석"
        val prompt = FollowUpPrompts.critique(label, contextFor(run, prior), contract = isContractTool(run.tool))
        val (provider, json) = charged(CRITIQUE_TOOL) { completeCritique(prompt) }

        save(run, prior.size, FollowUpRole.CRITIQUE, json, provider)
        return FollowUpThread(runId, turns(runId), currentBalance())
    }

    private fun requireAnalyzableRun(runId: Long): AiToolRun {
        val run = aiToolRunService.get(runId)
        if (run.status != RunStatus.SUCCESS || run.resultJson.isNullOrBlank()) {
            throw BadRequestException("완료된 분석 결과에만 후속 논의를 할 수 있습니다.")
        }
        if (!aiServiceManager.hasConfiguredProvider()) {
            throw ServiceUnavailableException("AI 분석 서비스가 설정되지 않았습니다(API 키 미설정).")
        }
        return run
    }

    /** 계약서 계열 결과는 투자 분석과 근거·문체가 달라 법무 실사 프롬프트로 답한다. */
    private fun isContractTool(tool: String): Boolean = tool.startsWith("CONTRACT_") || tool in CONTRACT_LIKE_TOOLS

    private fun perspectiveLabelOf(resultJson: String?): String? =
        resultJson?.let { runCatching { objectMapper.readTree(it).path("perspectiveLabel").asText(null) }.getOrNull() }

    private fun contextFor(run: AiToolRun, prior: List<FollowUpTurn>): String {
        val siblings = run.dealId
            ?.let { aiToolRunService.latestSuccessResultsByTool(it) }
            ?.mapValues { (_, json) -> compact(json) }
            .orEmpty()
        return FollowUpContext.build(run.tool, compact(requireNotNull(run.resultJson)), siblings, prior)
    }

    /**
     * 결과 JSON 을 프롬프트용으로 다듬는다 - 면책·제공자 같은 표시용 필드를 빼고 분석 본문을 앞에 둔다.
     * 상한에서 잘릴 때 핵심(analysis)이 먼저 살아남게 하려는 것. 파싱 실패 시 원문 그대로.
     */
    internal fun compact(json: String): String {
        val node = runCatching { objectMapper.readTree(json) }.getOrNull() as? ObjectNode ?: return json
        val out = objectMapper.createObjectNode()
        node.get("analysis")?.takeIf { !it.isNull }?.let { out.set<ObjectNode>("analysis", it) }
        node.fields().forEach { (k, v) ->
            if (k !in DROP_KEYS && k != "analysis" && !v.isNull) out.set<ObjectNode>(k, v)
        }
        return objectMapper.writeValueAsString(out)
    }

    /** 크레딧 게이트 + AI 실패를 503 으로 변환(크레딧 미차감). 402 는 그대로. */
    private fun <T> charged(tool: String, block: () -> T): T = try {
        creditGate.charge(ToolPricing.costOf(tool), block)
    } catch (e: InsufficientCreditsException) {
        throw e
    } catch (e: Exception) {
        log.error("[FollowUp] AI 호출 실패 (tool={})", tool, e)
        throw ServiceUnavailableException(e.message?.take(300) ?: "AI 응답 생성에 실패했습니다. 잠시 후 다시 시도해 주세요.")
    }

    /** 마크다운 응답(markdown 필드) 추출. 본문을 못 찾으면 1회만 재요청한다. */
    private fun completeMarkdown(prompt: String, label: String): Pair<String, String> {
        repeat(MAX_ATTEMPTS) { attempt ->
            val ai = aiServiceManager.complete(prompt)
            val body = markdownBody(ai.text)
            if (body.isNotBlank()) return ai.provider to body.trim()
            log.warn("[FollowUp] {} 본문 추출 실패 - 시도 {}/{}", label, attempt + 1, MAX_ATTEMPTS)
        }
        throw IllegalStateException("AI ${label}을 해석하지 못했습니다. 잠시 후 다시 시도해 주세요.")
    }

    /** 반박 검토 JSON 추출. verdict·issues 중 하나도 없으면 1회만 재요청한다. */
    private fun completeCritique(prompt: String): Pair<String, String> {
        repeat(MAX_ATTEMPTS) { attempt ->
            val ai = aiServiceManager.complete(prompt)
            val node = AiJsonExtractor.parseUsable(ai.text, "verdict", "issues")
            if (node != null) return ai.provider to objectMapper.writeValueAsString(node)
            log.warn("[FollowUp] 반박 검토 파싱 실패 - 시도 {}/{}", attempt + 1, MAX_ATTEMPTS)
        }
        throw IllegalStateException("반박 검토 결과를 해석하지 못했습니다. 잠시 후 다시 시도해 주세요.")
    }

    /**
     * 응답에서 마크다운 본문을 꺼낸다. 프롬프트는 markdown 필드를 강제하지만 모델이 다른 키를 쓰는 경우를 흡수하고,
     * JSON 이 아예 없으면(순수 마크다운) 텍스트 자체를 본문으로 쓴다.
     */
    internal fun markdownBody(text: String): String {
        val node = AiJsonExtractor.parse(text)
        if (node != null && node.isObject) {
            return BODY_KEYS.firstNotNullOfOrNull { k -> node.get(k)?.takeIf { it.isTextual && it.asText().isNotBlank() }?.asText() }
                .orEmpty()
        }
        return text.takeIf { !it.trimStart().startsWith("{") }.orEmpty()
    }

    private fun turns(runId: Long): List<FollowUpTurn> {
        val current = TenantContext.require()
        return repository.findByTenantIdAndOwnerUserIdAndRunIdOrderByTurnSeqAscIdAsc(current.tenantId, current.userId, runId)
    }

    private fun save(run: AiToolRun, seq: Int, role: FollowUpRole, content: String, provider: String?) {
        val current = TenantContext.require()
        val turn = FollowUpTurn(
            runId = requireNotNull(run.id),
            dealId = run.dealId,
            turnSeq = seq,
            role = role,
            content = content,
            provider = provider,
        )
        turn.tenantId = current.tenantId
        turn.ownerUserId = current.userId
        repository.save(turn)
    }

    private fun currentBalance(): Int {
        val current = TenantContext.require()
        return creditService.balance(current.tenantId, current.userId)
    }

    companion object {
        /** 과금 키([ToolPricing]). */
        const val ASK_TOOL = "FOLLOWUP_ASK"
        const val SUGGEST_TOOL = "FOLLOWUP_SUGGEST"
        const val CRITIQUE_TOOL = "CRITIQUE"

        const val MAX_QUESTION_CHARS = 2000
        private const val MAX_ATTEMPTS = 2
        private val BODY_KEYS = listOf("markdown", "answer", "result", "text", "content", "response")
        private val DROP_KEYS = setOf("disclaimer", "provider", "analysisRaw")
        private val CONTRACT_LIKE_TOOLS = setOf("LOI_REVIEW", "LEASE_REVIEW")
    }
}

/** 스레드 + 현재 잔액(과금 후 헤더 갱신용). */
data class FollowUpThread(
    val runId: Long,
    val turns: List<FollowUpTurn>,
    val creditBalance: Int,
)
