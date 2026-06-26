package com.aixnative.underwriting

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.aixnative.ai.AiServiceManager
import com.aixnative.ai.AiToolRunService
import com.aixnative.ai.RunStatus
import com.aixnative.billing.CreditGate
import com.aixnative.billing.CreditService
import com.aixnative.common.Disclaimer
import com.aixnative.common.tenant.TenantContext
import com.aixnative.common.web.InsufficientCreditsException
import com.aixnative.common.web.ServiceUnavailableException
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.security.MessageDigest

/**
 * hero — AI 딜 언더라이팅. 흐름:
 *   입력 → ProForma 결정론적 계산(순수) → <FACTS> → [크레딧 1 차감] → AI 내러티브 → 이력 저장.
 * ProForma 단독 계산은 무료(크레딧 미소비). AI 분석만 1 크레딧.
 */
@Service
class UnderwritingService(
    private val aiServiceManager: AiServiceManager,
    private val creditGate: CreditGate,
    private val creditService: CreditService,
    private val aiToolRunService: AiToolRunService,
    private val objectMapper: ObjectMapper,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /** 무료: ProForma 지표만 계산 (AI·크레딧 미사용). */
    fun proForma(req: UnderwriteRequest): ProFormaResponse {
        val inputs = req.toInputs()
        return ProFormaResponse(
            proForma = ProFormaCalculator.compute(inputs),
            scenarios = ProFormaCalculator.scenarios(inputs),
            disclaimer = Disclaimer.TEXT,
        )
    }

    /** 과금: 기본 = 언더라이팅 내러티브. (하위호환 진입점) */
    fun analyze(req: UnderwriteRequest): UnderwriteResponse = analyze(AnalysisType.UNDERWRITING, req)

    /**
     * 과금: ProForma 계산 + 선택한 분석 단계의 AI 호출. 성공 시에만 1 크레딧 차감.
     * 단계별로 동일한 코드 확정 수치(ProForma)를 컨텍스트로 쓰되 프롬프트만 달라진다.
     */
    fun analyze(type: AnalysisType, req: UnderwriteRequest): UnderwriteResponse {
        // AI 미설정이면 크레딧을 건드리지 않고 즉시 503 (로컬에서 키 없이 호출 시).
        if (!aiServiceManager.hasConfiguredProvider()) {
            throw ServiceUnavailableException("AI 분석 서비스가 설정되지 않았습니다(API 키 미설정).")
        }

        val inputs = req.toInputs()
        val result = ProFormaCalculator.compute(inputs)
        val scenarios = ProFormaCalculator.scenarios(inputs)
        val facts = FactsFormatter.toFacts(inputs, result, scenarios)
        val assetFacts = FactsFormatter.toAssetFacts(req, result)

        val prompt = when (type) {
            AnalysisType.UNDERWRITING ->
                UnderwritingPrompts.underwritingNarrative(facts, req.dealName, CreGuidelines.underwritingGuidelineText(req.assetType))
            AnalysisType.SCREENING ->
                UnderwritingPrompts.dealScreening(assetFacts, req.dealName, CreGuidelines.screeningGuidelineText(req.assetType))
            AnalysisType.MARKET_STUDY ->
                UnderwritingPrompts.marketStudy(assetFacts, req.dealName)
            AnalysisType.IC_MEMO ->
                UnderwritingPrompts.icMemo(facts + "\n\n" + assetFacts, req.dealName)
        }

        // 크레딧 게이트: 잔액 확인 → AI 호출 → 성공 시에만 1 크레딧 차감.
        // AI 호출 실패(키/모델/rate limit/타임아웃)는 generic 500 대신 503 + 사유로 변환(크레딧 미차감).
        val ai = try {
            creditGate.charge { aiServiceManager.complete(prompt) }
        } catch (e: InsufficientCreditsException) {
            throw e // 402 페이월은 그대로
        } catch (e: Exception) {
            log.error("[Underwriting] AI 분석 실패 (type={})", type, e)
            throw ServiceUnavailableException("AI 분석 호출에 실패했습니다: ${rootMessage(e)}")
        }

        val parsed = tryParseJson(ai.text)

        // 딜 입력 + 결과(JSON)를 저장해 조회 API(/runs)·보고서에서 재현 가능하게 한다.
        val resultPayload = linkedMapOf<String, Any?>(
            "analysisType" to type.name,
            "proForma" to result,
            "scenarios" to scenarios,
            "analysis" to parsed,
            "analysisRaw" to if (parsed == null) ai.text else null,
            "provider" to ai.provider,
            "disclaimer" to Disclaimer.TEXT,
        )
        val run = aiToolRunService.record(
            tool = type.tool,
            status = RunStatus.SUCCESS,
            requestHash = sha256(prompt),
            dealName = req.dealName,
            requestJson = objectMapper.writeValueAsString(req),
            resultJson = objectMapper.writeValueAsString(resultPayload),
        )
        val current = TenantContext.require()
        val balance = creditService.balance(current.tenantId, current.userId)

        return UnderwriteResponse(
            runId = run.id!!,
            analysisType = type.name,
            proForma = result,
            scenarios = scenarios,
            analysis = parsed,
            analysisRaw = if (parsed == null) ai.text else null,
            provider = ai.provider,
            creditBalance = balance,
            disclaimer = Disclaimer.TEXT,
        )
    }

    /** 내 분석 이력 목록 (테넌트 스코프, 최신순). */
    fun listRuns(): List<RunSummary> = aiToolRunService.listMine().map { r ->
        RunSummary(
            id = requireNotNull(r.id),
            dealName = r.dealName,
            tool = r.tool,
            status = r.status.name,
            createdAt = r.createdAt,
        )
    }

    /** 분석 이력 상세 — 저장된 입력/결과 JSON 을 그대로 반환. 테넌트 스코프(IDOR 차단). */
    fun getRun(id: Long): RunDetail {
        val r = aiToolRunService.get(id)
        return RunDetail(
            id = requireNotNull(r.id),
            dealName = r.dealName,
            tool = r.tool,
            status = r.status.name,
            createdAt = r.createdAt,
            request = r.requestJson?.let { objectMapper.readTree(it) },
            result = r.resultJson?.let { objectMapper.readTree(it) },
        )
    }

    /** 모델이 코드펜스/잡설을 섞어도 첫 JSON 객체를 추출해 파싱. 실패하면 null (원문은 호출부가 보존). */
    private fun tryParseJson(text: String): JsonNode? {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return try {
            objectMapper.readTree(text.substring(start, end + 1))
        } catch (e: Exception) {
            log.warn("AI 응답 JSON 파싱 실패: {}", e.message)
            null
        }
    }

    /** 중첩 예외에서 가장 구체적인 메시지를 추출(원인 우선), 너무 길면 자른다. */
    private fun rootMessage(e: Throwable): String {
        var cur: Throwable? = e
        var msg = e.message
        while (cur?.cause != null && cur.cause !== cur) {
            cur = cur.cause
            cur?.message?.takeIf { it.isNotBlank() }?.let { msg = it }
        }
        return (msg ?: e.javaClass.simpleName).take(300)
    }

    private fun sha256(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
