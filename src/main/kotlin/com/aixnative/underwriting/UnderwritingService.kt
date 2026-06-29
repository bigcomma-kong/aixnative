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
import com.aixnative.common.web.BadRequestException
import com.aixnative.common.web.InsufficientCreditsException
import com.aixnative.common.web.ServiceUnavailableException
import com.aixnative.integration.bizhealth.BizHealthClient
import com.aixnative.integration.bizhealth.BizHealthFacts
import com.aixnative.integration.marketdata.MarketDataService
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
    private val marketDataService: MarketDataService,
    private val bizHealthClient: BizHealthClient,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /** 무료: ProForma 지표만 계산 (AI·크레딧 미사용). */
    fun proForma(req: UnderwriteRequest): ProFormaResponse {
        val inputs = req.toInputs()
        val result = ProFormaCalculator.compute(inputs)
        return ProFormaResponse(
            proForma = result,
            scenarios = ProFormaCalculator.scenarios(inputs),
            guidelineChecks = GuidelineEvaluator.evaluate(inputs, result),
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
        val guidelineChecks = GuidelineEvaluator.evaluate(inputs, result)
        val facts = FactsFormatter.toFacts(inputs, result, scenarios)
        val assetFacts = FactsFormatter.toAssetFacts(req, result)

        val prompt = when (type) {
            AnalysisType.UNDERWRITING ->
                UnderwritingPrompts.underwritingNarrative(facts, req.dealName, CreGuidelines.underwritingGuidelineText(req.assetType))
            AnalysisType.SCREENING ->
                UnderwritingPrompts.dealScreening(
                    assetFacts + marketDataService.marketFacts(req.location, req.assetType),
                    req.dealName, CreGuidelines.screeningGuidelineText(req.assetType),
                )
            AnalysisType.MARKET_STUDY ->
                UnderwritingPrompts.marketStudy(
                    assetFacts + marketDataService.marketFacts(req.location, req.assetType),
                    req.dealName,
                )
            AnalysisType.IC_MEMO ->
                UnderwritingPrompts.icMemo(buildIcMemoFacts(facts, assetFacts, req.dealName), req.dealName)
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
            "guidelineChecks" to guidelineChecks,
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
            guidelineChecks = guidelineChecks,
            analysis = parsed,
            analysisRaw = if (parsed == null) ai.text else null,
            provider = ai.provider,
            creditBalance = balance,
            disclaimer = Disclaimer.TEXT,
        )
    }

    /**
     * 과금: 문서/텍스트 기반 분석(매입 추가 단계 + 신규 트랙 B~E). ProForma 없이 자유 텍스트를 근거로
     * 단계별 프롬프트를 호출한다. 성공 시에만 1 크레딧 차감(AI 호출 실패는 503·미차감).
     */
    fun analyzeDoc(type: DocAnalysisType, req: DocAnalyzeRequest): DocAnalyzeResponse {
        if (!aiServiceManager.hasConfiguredProvider()) {
            throw ServiceUnavailableException("AI 분석 서비스가 설정되지 않았습니다(API 키 미설정).")
        }

        val assetType = req.assetType
        // BOV·DEV 단계는 구조화 입력으로 코드가 수치를 확정하고(calc) <DATA> 에 주입한다(정밀화).
        // 나머지 단계는 자유 텍스트(documentText) 필수.
        var calc: Any? = null
        val prompt = when (type) {
            DocAnalysisType.BOV -> {
                val input = req.bov ?: throw BadRequestException("매각 BOV 는 평가 입력(noi·시장 Cap 등)이 필요합니다.")
                val used = BovValuator.Inputs(
                    noiEok = input.noiEok,
                    marketCapPct = input.marketCapPct,
                    discountRatePct = input.discountRatePct ?: CreGuidelines.bovDefaultDiscountPct(assetType),
                    exitCapPct = input.exitCapPct ?: CreGuidelines.bovDefaultExitCapPct(assetType),
                    holdYears = input.holdYears,
                    rentGrowthPct = input.rentGrowthPct,
                    salesCompValueEok = input.salesCompValueEok,
                )
                val r = BovValuator.compute(used)
                calc = r
                UnderwritingPrompts.bovNarrative(
                    DocCalcFacts.bovFacts(input, used, r, req.documentText) +
                        marketDataService.marketFacts(req.location, assetType),
                    req.dealName, CreGuidelines.bovGuidelineText(assetType),
                )
            }
            DocAnalysisType.DEV_FEASIBILITY -> {
                val input = req.dev ?: throw BadRequestException("개발 타당성은 사업비·수입 입력이 필요합니다.")
                val hasGdv = input.salesRevenueEok > 0 || (input.stabilizedNoiEok > 0 && input.exitCapPct > 0)
                if (!hasGdv) {
                    throw BadRequestException("자산가치 산정 불가: 분양수입 또는 (안정화 NOI + Exit Cap) 중 하나는 입력해야 합니다.")
                }
                val devIn = DevFeasibilityCalculator.Inputs(
                    landCostEok = input.landCostEok,
                    constructionCostEok = input.constructionCostEok,
                    financingCostEok = input.financingCostEok,
                    otherCostEok = input.otherCostEok,
                    contingencyPct = input.contingencyPct,
                    stabilizedNoiEok = input.stabilizedNoiEok,
                    exitCapPct = input.exitCapPct,
                    salesRevenueEok = input.salesRevenueEok,
                )
                val r = DevFeasibilityCalculator.compute(devIn)
                calc = r
                UnderwritingPrompts.devFeasibility(
                    DocCalcFacts.devFacts(input, r, req.documentText) +
                        marketDataService.marketFacts(req.location, assetType) +
                        marketDataService.landComparablesFactLine(req.location) +
                        marketDataService.landValuationFactLine(req.parcelAddress),
                    req.dealName, CreGuidelines.devFeasibilityGuidelineText(assetType),
                )
            }
            DocAnalysisType.COUNTERPARTY_DD -> {
                val bizNo = req.bizNo?.replace("[^0-9]".toRegex(), "")?.takeIf { it.length == 10 }
                if (bizNo == null && req.counterpartyName.isNullOrBlank()) {
                    throw BadRequestException("거래상대방 실사는 사업자등록번호(10자리) 또는 상호가 필요합니다.")
                }
                val r = bizHealthClient.check(req.bizNo, req.counterpartyName)
                calc = r
                UnderwritingPrompts.counterpartyDd(BizHealthFacts.summary(r), req.dealName)
            }
            DocAnalysisType.PRICE_FORECAST -> {
                val input = req.forecast ?: throw BadRequestException("가격 예측은 NOI 또는 연면적 입력이 필요합니다.")
                val hasIncome = (input.noiEok ?: 0.0) > 0
                val hasArea = (input.areaPyeong ?: 0.0) > 0
                if (!hasIncome && !hasArea) {
                    throw BadRequestException("가격 예측은 NOI(소득환원) 또는 연면적(거래사례) 중 하나는 입력해야 합니다.")
                }
                val marketCap = input.marketCapPct ?: CreGuidelines.bovDefaultExitCapPct(assetType)
                val stats = marketDataService.compStats(req.location)
                val r = PriceEstimator.compute(
                    PriceEstimator.Inputs(
                        noiEok = input.noiEok,
                        marketCapPct = marketCap,
                        areaPyeong = input.areaPyeong,
                        compPyeongManwon = stats?.medianPyeongManwon?.toDouble(),
                        compCount = stats?.count ?: 0,
                    )
                )
                calc = r
                UnderwritingPrompts.priceForecast(
                    DocCalcFacts.priceFacts(input, marketCap, stats, r, req.documentText) +
                        marketDataService.marketFacts(req.location, assetType) +
                        marketDataService.landValuationFactLine(req.parcelAddress),
                    req.dealName,
                )
            }
            else -> {
                val doc = req.documentText?.takeIf { it.isNotBlank() }
                    ?: throw BadRequestException("분석 대상 정보(documentText)는 필수입니다.")
                when (type) {
                    DocAnalysisType.UNDERWRITING_GUIDE ->
                        UnderwritingPrompts.underwritingGuide(doc, req.dealName, CreGuidelines.underwritingGuidelineText(assetType))
                    DocAnalysisType.BUILDING_RESEARCH ->
                        UnderwritingPrompts.buildingResearch(doc, req.dealName)
                    DocAnalysisType.TAX_PRICE_DIAGNOSIS ->
                        UnderwritingPrompts.taxPriceDiagnosis(
                            doc + marketDataService.marketFacts(req.location, assetType) +
                                marketDataService.landComparablesFactLine(req.location) +
                                marketDataService.landValuationFactLine(req.parcelAddress),
                            req.dealName,
                        )
                    DocAnalysisType.AM_QUARTERLY ->
                        UnderwritingPrompts.amQuarterly(doc, req.dealName)
                    DocAnalysisType.HOLD_SELL_REFI ->
                        UnderwritingPrompts.holdSellRefi(doc, req.dealName, CreGuidelines.holdSellRefiGuidelineText())
                    DocAnalysisType.MARKET_RESEARCH_DEEP ->
                        UnderwritingPrompts.marketResearchDeep(
                            doc + marketDataService.marketFacts(req.location ?: req.assetType, req.assetType),
                            req.dealName,
                        )
                    else -> error("unreachable: $type")
                }
            }
        }

        val ai = try {
            creditGate.charge { aiServiceManager.complete(prompt) }
        } catch (e: InsufficientCreditsException) {
            throw e
        } catch (e: Exception) {
            log.error("[Underwriting] 문서 분석 실패 (type={})", type, e)
            throw ServiceUnavailableException("AI 분석 호출에 실패했습니다: ${rootMessage(e)}")
        }

        val parsed = tryParseJson(ai.text)
        val resultPayload = linkedMapOf<String, Any?>(
            "analysisType" to type.name,
            "analysis" to parsed,
            "analysisRaw" to if (parsed == null) ai.text else null,
            "calc" to calc,
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
        return DocAnalyzeResponse(
            runId = requireNotNull(run.id),
            analysisType = type.name,
            analysis = parsed,
            analysisRaw = if (parsed == null) ai.text else null,
            calc = calc?.let { objectMapper.valueToTree(it) },
            provider = ai.provider,
            creditBalance = creditService.balance(current.tenantId, current.userId),
            disclaimer = Disclaimer.TEXT,
        )
    }

    /**
     * 딜 추출 — 기사/딜 텍스트를 구조화 필드로(분석 폼 프리필용). 진입 깔때기라 크레딧 미차감(인증·AI설정만).
     * 파싱 실패 시 raw 원문 반환. 실제 분석(가격예측·언더라이팅)이 과금 단위.
     */
    fun extractDeal(req: DealExtractRequest): DealExtractResponse {
        if (!aiServiceManager.hasConfiguredProvider()) {
            throw ServiceUnavailableException("AI 분석 서비스가 설정되지 않았습니다(API 키 미설정).")
        }
        val text = req.text?.takeIf { it.isNotBlank() }
            ?: throw BadRequestException("분석할 기사/딜 텍스트를 입력하세요.")
        val ai = try {
            aiServiceManager.complete(UnderwritingPrompts.dealExtract(text.take(MAX_EXTRACT_CHARS)))
        } catch (e: Exception) {
            log.error("[Underwriting] 딜 추출 실패", e)
            throw ServiceUnavailableException("딜 추출 호출에 실패했습니다: ${rootMessage(e)}")
        }
        val parsed = tryParseJson(ai.text)
        val extract = parsed?.let { runCatching { objectMapper.treeToValue(it, DealExtract::class.java) }.getOrNull() }
        return DealExtractResponse(
            extract = extract,
            raw = if (extract == null) ai.text else null,
            provider = ai.provider,
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

    /**
     * IC 메모 <FACTS> 조립 — 같은 딜의 앞 단계(스크리닝·시장조사·언더라이팅) 실제 AI 결과 JSON을
     * 종합하고, 결정론적 Pro Forma 수치를 재무 베이스로 덧붙인다. 앞 단계가 있을수록 종합이 깊어진다.
     * (앞 단계가 없어도 ProForma·자산 facts 만으로 독립 동작.)
     */
    private fun buildIcMemoFacts(facts: String, assetFacts: String, dealName: String?): String {
        val priors = dealName?.takeIf { it.isNotBlank() }
            ?.let { aiToolRunService.latestSuccessResultsByTool(it) }
            ?: emptyMap()
        val sb = StringBuilder()
        priors[AnalysisType.SCREENING.tool]?.let { sb.append("[스크리닝 결과]\n").append(it).append("\n\n") }
        priors[AnalysisType.MARKET_STUDY.tool]?.let { sb.append("[시장조사 결과]\n").append(it).append("\n\n") }
        priors[AnalysisType.UNDERWRITING.tool]?.let { sb.append("[언더라이팅 결과]\n").append(it).append("\n\n") }
        sb.append("[자산·매입 가정]\n").append(assetFacts).append("\n\n")
        sb.append("[Pro Forma 확정 수치]\n").append(facts)
        return sb.toString()
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

    private companion object {
        /** 딜 추출 입력 상한(프롬프트 비대·타임아웃 방지). */
        const val MAX_EXTRACT_CHARS = 8000
    }
}
