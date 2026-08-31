package com.aixnative.contract.service

import com.aixnative.ai.domain.RunStatus
import com.aixnative.ai.service.AiJsonExtractor
import com.aixnative.ai.service.AiServiceManager
import com.aixnative.ai.service.AiServiceProperties
import com.aixnative.ai.service.AiToolRunService
import com.aixnative.billing.domain.ToolPricing
import com.aixnative.billing.service.CreditGate
import com.aixnative.billing.service.CreditService
import com.aixnative.common.Disclaimer
import com.aixnative.common.tenant.TenantContext
import com.aixnative.common.web.BadRequestException
import com.aixnative.common.web.InsufficientCreditsException
import com.aixnative.common.web.ServiceUnavailableException
import com.aixnative.contract.domain.ContractReviewRequest
import com.aixnative.contract.domain.ContractSetCompareRequest
import com.aixnative.contract.domain.ContractTools
import com.aixnative.contract.domain.MIN_CONTRACT_TEXT_LEN
import com.aixnative.contract.domain.ReviewPerspective
import com.aixnative.contract.domain.SET_COMPARE_MAX
import com.aixnative.contract.domain.SET_COMPARE_MIN
import com.aixnative.contract.web.ContractResponse
import com.aixnative.contract.web.ContractSetCompareResponse
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.security.MessageDigest

/**
 * AI 계약서 검토 - 조항별 리스크·미기재 공란·조문 정합성·협상 포인트.
 *
 * 구조는 기존 [com.aixnative.underwriting.service.UnderwritingService] 의 문서 분석 경로를 그대로 따른다
 * (크레딧 게이트 → AI 호출 → JSON 파싱 → `ai_tool_run` 기록). 다른 점 셋:
 *
 *  1. **긴 예산**: 계약 전문을 넣고 조항별 검토까지 요구하므로 일반 호출보다 훨씬 오래 걸린다
 *     ([AiServiceProperties.docBudget]). 짧은 기본 예산으로는 매번 타임아웃이다.
 *  2. **repair 재시도**: 파싱이 안 되거나 알맹이가 없으면 **짧은 예산**으로 한 번 더 요청한다.
 *     1차와 같은 예산으로 재시도하면 합이 Cloud Run 요청 상한을 넘는다.
 *  3. **원문을 request_json 에 남기지 않는다**: 계약서는 당사자명·사업자번호·거래금액의 집합이라
 *     민감도가 한 단계 높다. 재열람 수요는 결과(result_json)에 있으므로 요청 쪽은 메타 + 앞부분 발췌만 남긴다.
 */
@Service
class ContractReviewService(
    private val aiServiceManager: AiServiceManager,
    private val aiProps: AiServiceProperties,
    private val creditGate: CreditGate,
    private val creditService: CreditService,
    private val aiToolRunService: AiToolRunService,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** 계약서 1건 검토. */
    fun review(req: ContractReviewRequest): ContractResponse {
        requireAiConfigured()
        val text = req.text.trim()
        if (text.length < MIN_CONTRACT_TEXT_LEN) {
            throw BadRequestException("계약서 원문을 ${MIN_CONTRACT_TEXT_LEN}자 이상 입력하거나 파일에서 불러와 주세요.")
        }
        val perspective = ReviewPerspective.of(req.perspective)
        val docName = req.sourceFileName?.trim()?.takeIf { it.isNotBlank() } ?: req.dealName
        val prompt = ContractPrompts.review(text.take(MAX_PROMPT_CHARS), docName, perspective)

        val (parsed, raw, provider) = chargeAndCall(ContractTools.REVIEW, prompt, REVIEW_KEYS)

        val payload = linkedMapOf<String, Any?>(
            "tool" to ContractTools.REVIEW,
            "perspective" to perspective.name,
            "perspectiveLabel" to perspective.label,
            "docName" to docName,
            "analysis" to parsed,
            "analysisRaw" to if (parsed == null) raw else null,
            "provider" to provider,
            "disclaimer" to Disclaimer.TEXT,
        )
        val run = aiToolRunService.record(
            tool = ContractTools.REVIEW,
            status = RunStatus.SUCCESS,
            requestHash = sha256(prompt),
            dealName = req.dealName,
            dealId = req.dealId,
            requestJson = redactedRequestJson(req, text, perspective),
            resultJson = objectMapper.writeValueAsString(payload),
        )
        return response(run.id, run.dealId, ContractTools.REVIEW, parsed, raw, provider, perspective)
    }

    /**
     * 조항별 수정안(레드라인). 원문을 다시 받지 않고 **검토 결과 runId** 로 참조한다 -
     * `AiToolRunService.get` 이 테넌트 스코프를 강제하므로 다른 테넌트의 id 는 404 가 되고,
     * 클라이언트가 임의 JSON 을 주입할 여지도 없다.
     */
    fun revise(runId: Long, perspectiveRaw: String?): ContractResponse {
        requireAiConfigured()
        val source = aiToolRunService.get(runId)
        if (source.tool != ContractTools.REVIEW) {
            throw BadRequestException("계약서 검토 결과에만 수정안을 만들 수 있습니다.")
        }
        val reviewJson = analysisNodeOf(source.resultJson)
            ?: throw BadRequestException("검토 결과를 읽을 수 없어 수정안을 만들 수 없습니다. 검토를 다시 실행해 주세요.")
        // 관점 미지정이면 원 검토의 관점을 이어받는다(사용자가 매번 다시 고르지 않도록).
        val perspective = perspectiveRaw?.let { ReviewPerspective.of(it) }
            ?: ReviewPerspective.of(fieldOf(source.resultJson, "perspective"))

        val prompt = ContractPrompts.revise(objectMapper.writeValueAsString(reviewJson), perspective)
        val (parsed, raw, provider) = chargeAndCall(ContractTools.REVISE, prompt, REVISE_KEYS)

        val payload = linkedMapOf<String, Any?>(
            "tool" to ContractTools.REVISE,
            "sourceRunId" to runId,
            "perspective" to perspective.name,
            "perspectiveLabel" to perspective.label,
            "analysis" to parsed,
            "analysisRaw" to if (parsed == null) raw else null,
            "provider" to provider,
            "disclaimer" to Disclaimer.TEXT,
        )
        val run = aiToolRunService.record(
            tool = ContractTools.REVISE,
            status = RunStatus.SUCCESS,
            requestHash = sha256(prompt),
            dealName = source.dealName,
            dealId = source.dealId,
            requestJson = objectMapper.writeValueAsString(mapOf("sourceRunId" to runId, "perspective" to perspective.name)),
            resultJson = objectMapper.writeValueAsString(payload),
        )
        return response(run.id, run.dealId, ContractTools.REVISE, parsed, raw, provider, perspective)
    }

    /** 같은 딜에 묶인 계약 2~4건의 문서 사이 관계 심사. */
    fun compareSet(req: ContractSetCompareRequest): ContractSetCompareResponse {
        requireAiConfigured()
        val ids = req.runIds.distinct()
        if (ids.size !in SET_COMPARE_MIN..SET_COMPARE_MAX) {
            throw BadRequestException("교차검토는 검토 결과 ${SET_COMPARE_MIN}~${SET_COMPARE_MAX}건을 골라야 합니다.")
        }
        val sources = ids.map { aiToolRunService.get(it) } // 테넌트 스코프 강제(다른 테넌트 id → 404)
        sources.firstOrNull { it.tool != ContractTools.REVIEW }?.let {
            throw BadRequestException("계약서 검토 결과만 교차검토할 수 있습니다.")
        }

        val documents = sources.map { run ->
            linkedMapOf<String, Any?>(
                "docName" to (fieldOf(run.resultJson, "docName") ?: run.dealName ?: "문서 ${run.id}"),
                "review" to analysisNodeOf(run.resultJson),
            )
        }
        val prompt = ContractPrompts.compareSet(objectMapper.writeValueAsString(documents))
        val (parsed, raw, provider) = chargeAndCall(ContractTools.SET_COMPARE, prompt, SET_COMPARE_KEYS)

        val payload = linkedMapOf<String, Any?>(
            "tool" to ContractTools.SET_COMPARE,
            "sourceRunIds" to ids,
            "analysis" to parsed,
            "analysisRaw" to if (parsed == null) raw else null,
            "provider" to provider,
            "disclaimer" to Disclaimer.TEXT,
        )
        val run = aiToolRunService.record(
            tool = ContractTools.SET_COMPARE,
            status = RunStatus.SUCCESS,
            requestHash = sha256(prompt),
            dealName = sources.first().dealName,
            dealId = sources.first().dealId,
            requestJson = objectMapper.writeValueAsString(mapOf("sourceRunIds" to ids)),
            resultJson = objectMapper.writeValueAsString(payload),
        )
        val current = TenantContext.require()
        return ContractSetCompareResponse(
            runId = run.id ?: 0,
            dealId = run.dealId ?: 0,
            deal = parsed,
            analysisRaw = if (parsed == null) raw else null,
            count = ids.size,
            provider = provider,
            creditBalance = creditService.balance(current.tenantId, current.userId),
            disclaimer = Disclaimer.TEXT,
        )
    }

    /**
     * 크레딧 차감 게이트 안에서 AI 를 부르고 결과를 파싱한다.
     *
     * 1차 응답이 파싱 불가하거나 [identityKeys] 가 하나도 없으면(= 알맹이 없음) **짧은 예산으로 1회 재요청**한다.
     * 두 번 다 실패하면 파싱 결과 없이 원문(raw)을 돌려주고, 그마저 비어 있으면 예외를 던져 **무과금**으로 끝낸다
     * (CreditGate 는 블록이 예외로 끝나면 차감하지 않는다).
     */
    private fun chargeAndCall(
        tool: String,
        prompt: String,
        identityKeys: Array<String>,
    ): Triple<JsonNode?, String, String> {
        return try {
            creditGate.charge(ToolPricing.costOf(tool)) {
                val first = aiServiceManager.complete(prompt, budget = aiProps.docBudget())
                val parsed = AiJsonExtractor.parseUsable(first.text, *identityKeys)
                if (parsed != null) return@charge Triple(parsed, first.text, first.provider)

                log.info("[Contract] {} 1차 응답 파싱 실패 - 짧은 예산으로 재요청", tool)
                val retry = aiServiceManager.complete(prompt + REPAIR_HINT, budget = aiProps.repairBudget())
                val reparsed = AiJsonExtractor.parseUsable(retry.text, *identityKeys)
                val rawText = retry.text.ifBlank { first.text }
                if (reparsed == null && rawText.isBlank()) {
                    throw ServiceUnavailableException("AI 응답이 비어 있습니다. 잠시 후 다시 시도해 주세요.")
                }
                Triple(reparsed, rawText, retry.provider)
            }
        } catch (e: InsufficientCreditsException) {
            throw e
        } catch (e: ServiceUnavailableException) {
            throw e
        } catch (e: Exception) {
            log.error("[Contract] {} 분석 실패", tool, e)
            throw ServiceUnavailableException("AI 분석 호출에 실패했습니다: ${rootMessage(e)}")
        }
    }

    private fun response(
        runId: Long?,
        dealId: Long?,
        tool: String,
        parsed: JsonNode?,
        raw: String,
        provider: String,
        perspective: ReviewPerspective,
    ): ContractResponse {
        val current = TenantContext.require()
        return ContractResponse(
            runId = runId ?: 0,
            dealId = dealId ?: 0,
            tool = tool,
            perspective = perspective.name,
            perspectiveLabel = perspective.label,
            analysis = parsed,
            analysisRaw = if (parsed == null) raw else null,
            provider = provider,
            creditBalance = creditService.balance(current.tenantId, current.userId),
            disclaimer = Disclaimer.TEXT,
        )
    }

    private fun requireAiConfigured() {
        if (!aiServiceManager.hasConfiguredProvider()) {
            throw ServiceUnavailableException("AI 분석 서비스가 설정되지 않았습니다(API 키 미설정).")
        }
    }

    /**
     * 계약 원문을 통째로 저장하지 않는다. 메타 + 앞부분 발췌만 남겨 무엇을 분석했는지는 알 수 있게 하되,
     * 유출 시 노출면을 최소화한다. 동일 입력 판정은 별도의 request_hash 가 담당한다.
     */
    private fun redactedRequestJson(req: ContractReviewRequest, text: String, perspective: ReviewPerspective): String =
        objectMapper.writeValueAsString(
            linkedMapOf(
                "dealName" to req.dealName,
                "sourceFileName" to req.sourceFileName,
                "perspective" to perspective.name,
                "charCount" to text.length,
                "excerpt" to text.take(EXCERPT_CHARS),
            ),
        )

    /** result_json 안의 `analysis` 노드만 꺼낸다(수정안·교차검토의 입력). */
    private fun analysisNodeOf(resultJson: String?): JsonNode? {
        val root = runCatching { objectMapper.readTree(resultJson ?: return null) }.getOrNull() ?: return null
        return root.get("analysis")?.takeIf { !it.isNull && !it.isEmpty }
    }

    private fun fieldOf(resultJson: String?, field: String): String? {
        val root = runCatching { objectMapper.readTree(resultJson ?: return null) }.getOrNull() ?: return null
        return root.get(field)?.takeIf { it.isTextual }?.asText()
    }

    private fun sha256(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    /** 중첩 예외에서 가장 구체적인 메시지(원인 우선), 너무 길면 자른다. */
    private fun rootMessage(e: Throwable): String {
        var cur: Throwable = e
        while (cur.cause != null && cur.cause !== cur) cur = cur.cause!!
        return (cur.message ?: cur.javaClass.simpleName).take(300)
    }

    private companion object {
        /** 검토 결과가 "알맹이 있음"으로 인정되는 키(any-of). */
        val REVIEW_KEYS = arrayOf("summary", "parties", "riskAssessment", "contractType")
        val REVISE_KEYS = arrayOf("revisions", "summary", "title")
        val SET_COMPARE_KEYS = arrayOf("dealSummary", "consistency", "documents")

        /** 재요청 시 덧붙이는 한 줄 - 1차 실패 원인 대부분이 형식 이탈이라 형식만 다시 못박는다. */
        const val REPAIR_HINT =
            "\n\n[재요청] 직전 응답이 형식을 벗어났습니다. 설명·코드펜스 없이 위 스키마의 JSON 객체 하나만 출력하세요."

        /** request_json 에 남길 원문 발췌 길이. */
        const val EXCERPT_CHARS = 500

        /**
         * 프롬프트에 넣을 원문 상한.
         *
         * 추출은 200,000자까지 허용하지만(문서 보존), **AI 입력은 따로 묶어야 한다** - 상한이 없으면
         * ①provider 컨텍스트 초과로 400 즉시 실패, ②통과하더라도 호출 원가가 크레딧 단가를 넘어
         * 분석 1건마다 손해가 난다. 계약서 검토는 전문을 통째로 넣는 유일한 도구라 이 상한이 곧 원가 상한이다.
         * 초과분은 잘리며, 사용자는 업로드 단계에서 이미 "앞부분만 담겼다"는 안내를 받는다.
         */
        const val MAX_PROMPT_CHARS = 60_000
    }
}
