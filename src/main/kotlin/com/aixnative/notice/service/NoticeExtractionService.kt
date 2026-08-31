package com.aixnative.notice.service

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
import com.aixnative.notice.domain.MIN_NOTICE_TEXT_LEN
import com.aixnative.notice.domain.NOTICE_COMPARE_MAX
import com.aixnative.notice.domain.NOTICE_COMPARE_MIN
import com.aixnative.notice.domain.NoticeCalculator
import com.aixnative.notice.domain.NoticeCompareRequest
import com.aixnative.notice.domain.NoticeExtractRequest
import com.aixnative.notice.domain.NoticeTools
import com.aixnative.notice.web.NoticeCompareResponse
import com.aixnative.notice.web.NoticeExtractResponse
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.security.MessageDigest

/**
 * 공매·매각·입찰 공고 분석 - 정형 추출 + 2~4건 비교.
 *
 * [ContractReviewService][com.aixnative.contract.service.ContractReviewService] 와 같은 규약을 따른다
 * (긴 예산 → 파싱 실패 시 짧은 예산 재요청 → `ai_tool_run` 기록, 비교는 원문 대신 runId 참조).
 *
 * 다른 점은 **추출 직후 코드가 파생값을 덮어쓴다**는 것이다([enrichDerived]).
 * AI 는 공고문의 숫자를 옮기기만 하고, 평단가·수익률은 [NoticeCalculator] 가 계산한다.
 */
@Service
class NoticeExtractionService(
    private val aiServiceManager: AiServiceManager,
    private val aiProps: AiServiceProperties,
    private val creditGate: CreditGate,
    private val creditService: CreditService,
    private val aiToolRunService: AiToolRunService,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** 공고문 1건 → 정형 JSON + 코드 산출 파생값. */
    fun extract(req: NoticeExtractRequest): NoticeExtractResponse {
        requireAiConfigured()
        val text = req.text.trim()
        if (text.length < MIN_NOTICE_TEXT_LEN) {
            throw BadRequestException("공고문 원문을 ${MIN_NOTICE_TEXT_LEN}자 이상 입력하거나 파일에서 불러와 주세요.")
        }
        val docName = req.sourceFileName?.trim()?.takeIf { it.isNotBlank() } ?: req.dealName
        val prompt = NoticePrompts.extract(text.take(MAX_PROMPT_CHARS), docName)

        val (parsed, raw, provider) = chargeAndCall(NoticeTools.EXTRACT, prompt, EXTRACT_KEYS)
        val enriched = parsed?.let { enrichDerived(it, req.monthlyRentKrw) }

        val payload = linkedMapOf<String, Any?>(
            "tool" to NoticeTools.EXTRACT,
            "docName" to docName,
            "extraction" to enriched,
            "analysisRaw" to if (enriched == null) raw else null,
            "provider" to provider,
            "disclaimer" to Disclaimer.TEXT,
        )
        val run = aiToolRunService.record(
            tool = NoticeTools.EXTRACT,
            status = RunStatus.SUCCESS,
            requestHash = sha256(prompt),
            dealName = req.dealName,
            dealId = req.dealId,
            requestJson = redactedRequestJson(req, text),
            resultJson = objectMapper.writeValueAsString(payload),
        )
        val current = TenantContext.require()
        return NoticeExtractResponse(
            runId = run.id ?: 0,
            dealId = run.dealId ?: 0,
            docName = docName,
            extraction = enriched,
            analysisRaw = if (enriched == null) raw else null,
            provider = provider,
            creditBalance = creditService.balance(current.tenantId, current.userId),
            disclaimer = Disclaimer.TEXT,
        )
    }

    /** 추출 결과 2~4건 비교(마크다운). 원문이 아니라 runId 참조 - 테넌트 격리가 자동 보장된다. */
    fun compare(req: NoticeCompareRequest): NoticeCompareResponse {
        requireAiConfigured()
        val ids = req.runIds.distinct()
        if (ids.size !in NOTICE_COMPARE_MIN..NOTICE_COMPARE_MAX) {
            throw BadRequestException("비교는 공고 ${NOTICE_COMPARE_MIN}~${NOTICE_COMPARE_MAX}건을 골라야 합니다.")
        }
        val sources = ids.map { aiToolRunService.get(it) }
        sources.firstOrNull { it.tool != NoticeTools.EXTRACT }?.let {
            throw BadRequestException("공고 추출 결과만 비교할 수 있습니다.")
        }

        val notices = sources.map { run ->
            linkedMapOf<String, Any?>(
                "docName" to (fieldOf(run.resultJson, "docName") ?: run.dealName ?: "공고 ${run.id}"),
                "extraction" to nodeOf(run.resultJson, "extraction"),
            )
        }
        val prompt = NoticePrompts.compare(objectMapper.writeValueAsString(notices))

        // 비교는 마크다운 산출물이라 JSON 파싱·재요청 경로를 타지 않는다.
        val ai = try {
            creditGate.charge(ToolPricing.costOf(NoticeTools.COMPARE)) {
                aiServiceManager.complete(prompt, budget = aiProps.docBudget())
            }
        } catch (e: InsufficientCreditsException) {
            throw e
        } catch (e: Exception) {
            log.error("[Notice] 비교 실패", e)
            throw ServiceUnavailableException("AI 분석 호출에 실패했습니다: ${rootMessage(e)}")
        }

        val payload = linkedMapOf<String, Any?>(
            "tool" to NoticeTools.COMPARE,
            "sourceRunIds" to ids,
            "markdown" to ai.text,
            "provider" to ai.provider,
            "disclaimer" to Disclaimer.TEXT,
        )
        val run = aiToolRunService.record(
            tool = NoticeTools.COMPARE,
            status = RunStatus.SUCCESS,
            requestHash = sha256(prompt),
            dealName = sources.first().dealName,
            dealId = sources.first().dealId,
            requestJson = objectMapper.writeValueAsString(mapOf("sourceRunIds" to ids)),
            resultJson = objectMapper.writeValueAsString(payload),
        )
        val current = TenantContext.require()
        return NoticeCompareResponse(
            runId = run.id ?: 0,
            dealId = run.dealId ?: 0,
            markdown = ai.text,
            count = ids.size,
            provider = ai.provider,
            creditBalance = creditService.balance(current.tenantId, current.userId),
            disclaimer = Disclaimer.TEXT,
        )
    }

    /**
     * 코드가 파생값을 계산해 추출 결과에 덧붙인다 - 물건별 평단가, 그리고 (월 임대료를 알면) 총수익률.
     * AI 응답을 신뢰하지 않고 여기서 만든 값만 화면에 나간다.
     */
    private fun enrichDerived(extraction: JsonNode, monthlyRentKrw: Long?): JsonNode {
        if (extraction !is ObjectNode) return extraction
        var totalBase = 0L

        (extraction.get("items") as? com.fasterxml.jackson.databind.node.ArrayNode)?.forEach { item ->
            if (item !is ObjectNode) return@forEach
            val rounds = (item.get("round_prices") as? com.fasterxml.jackson.databind.node.ArrayNode)
                ?.mapNotNull { it.takeIf { n -> n.isNumber }?.asLong() }
            val base = NoticeCalculator.basePriceKrw(rounds, longOf(item, "appraisal_krw"))
            val pyeongPrice = NoticeCalculator.pyeongPriceKrw(base, doubleOf(item, "area_m2"))
            if (pyeongPrice != null) item.put("pyeong_price_krw", pyeongPrice) else item.putNull("pyeong_price_krw")
            if (base != null) totalBase += base
        }

        // 대표(target/price) 기준 평단가 - 물건이 1건이거나 대표값만 있는 공고를 위해.
        val price = extraction.get("price") as? ObjectNode
        val target = extraction.get("target") as? ObjectNode
        val repBase = NoticeCalculator.basePriceKrw(null, longOf(price, "min_bid_krw"))
            ?: longOf(price, "appraisal_krw")
        val repPyeong = NoticeCalculator.pyeongPriceKrw(repBase, doubleOf(target, "area_m2"))

        val yieldBase = if (totalBase > 0) totalBase else repBase
        val derived = extraction.putObject("derived")
        if (repPyeong != null) derived.put("pyeong_price_krw", repPyeong) else derived.putNull("pyeong_price_krw")
        if (yieldBase != null && yieldBase > 0) derived.put("base_price_krw", yieldBase) else derived.putNull("base_price_krw")
        val yieldPct = NoticeCalculator.grossYieldPct(monthlyRentKrw, yieldBase)
        if (yieldPct != null) derived.put("gross_yield_pct", yieldPct) else derived.putNull("gross_yield_pct")
        derived.put("note", "평단가·수익률은 공고 수치로 코드가 계산한 값입니다(AI 산출 아님).")
        return extraction
    }

    private fun longOf(node: JsonNode?, field: String): Long? =
        node?.get(field)?.takeIf { it.isNumber }?.asLong()?.takeIf { it > 0 }

    private fun doubleOf(node: JsonNode?, field: String): Double? =
        node?.get(field)?.takeIf { it.isNumber }?.asDouble()?.takeIf { it > 0 }

    /** 계약서 검토와 동일한 규약 - 1차 실패 시 짧은 예산으로 1회 재요청. */
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

                log.info("[Notice] {} 1차 응답 파싱 실패 - 짧은 예산으로 재요청", tool)
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
            log.error("[Notice] {} 분석 실패", tool, e)
            throw ServiceUnavailableException("AI 분석 호출에 실패했습니다: ${rootMessage(e)}")
        }
    }

    private fun requireAiConfigured() {
        if (!aiServiceManager.hasConfiguredProvider()) {
            throw ServiceUnavailableException("AI 분석 서비스가 설정되지 않았습니다(API 키 미설정).")
        }
    }

    /** 공고 원문도 통째로 남기지 않는다(계약서와 동일 방침). */
    private fun redactedRequestJson(req: NoticeExtractRequest, text: String): String =
        objectMapper.writeValueAsString(
            linkedMapOf(
                "dealName" to req.dealName,
                "sourceFileName" to req.sourceFileName,
                "monthlyRentKrw" to req.monthlyRentKrw,
                "charCount" to text.length,
                "excerpt" to text.take(EXCERPT_CHARS),
            ),
        )

    private fun nodeOf(resultJson: String?, field: String): JsonNode? {
        val root = runCatching { objectMapper.readTree(resultJson ?: return null) }.getOrNull() ?: return null
        return root.get(field)?.takeIf { !it.isNull && !it.isEmpty }
    }

    private fun fieldOf(resultJson: String?, field: String): String? {
        val root = runCatching { objectMapper.readTree(resultJson ?: return null) }.getOrNull() ?: return null
        return root.get(field)?.takeIf { it.isTextual }?.asText()
    }

    private fun sha256(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun rootMessage(e: Throwable): String {
        var cur: Throwable = e
        while (cur.cause != null && cur.cause !== cur) cur = cur.cause!!
        return (cur.message ?: cur.javaClass.simpleName).take(300)
    }

    private companion object {
        val EXTRACT_KEYS = arrayOf("target", "schedule", "summary", "notice_type")
        const val REPAIR_HINT =
            "\n\n[재요청] 직전 응답이 형식을 벗어났습니다. 설명·코드펜스 없이 위 스키마의 JSON 객체 하나만 출력하세요."
        const val EXCERPT_CHARS = 500

        /**
         * 프롬프트에 넣을 원문 상한 - 계약서 검토와 동일 취지(컨텍스트 초과 방지 + 호출 원가 상한).
         * 공고문은 계약서보다 짧은 것이 보통이라 실제로 걸릴 일은 드물지만, 저감표가 긴 대량 개별매각
         * 공고에서는 걸린다.
         */
        const val MAX_PROMPT_CHARS = 60_000
    }
}
