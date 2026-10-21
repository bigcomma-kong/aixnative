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
import com.aixnative.contract.domain.ContractClauseParser
import com.aixnative.contract.domain.ContractMoneyCalculator
import com.aixnative.contract.domain.ContractReviewRequest
import com.aixnative.contract.domain.ContractTermCalculator
import com.aixnative.contract.domain.ContractVersionCompareRequest
import com.aixnative.contract.domain.ContractVersionDiff
import com.aixnative.contract.domain.LeaseFlagRules
import com.aixnative.contract.domain.LoiFlagRules
import com.aixnative.contract.domain.RuleFlag
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
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.databind.node.TextNode
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
        val promptText = text.take(MAX_PROMPT_CHARS)
        // 금액·기간은 코드가 실측해 사실로 주입한다(AI 산술 오류로 금액 100배·없는 기간 모순이 실린 레거시 사고).
        val factBlocks = ContractMoneyCalculator.factBlock(promptText) + ContractTermCalculator.factBlock(promptText)
        val prompt = ContractPrompts.review(promptText, docName, perspective, factBlocks)

        val (rawParsed, raw, provider) = chargeAndCall(ContractTools.REVIEW, prompt, REVIEW_KEYS)
        val parsed = rawParsed?.let { withContractFacts(it, promptText) }

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

    /** 매입의향서(LOI) - 핵심 조항 정형 추출 + 규칙 엔진 플래그(계약금 비율·유효기간·우선협상권 등). */
    fun loiReview(req: ContractReviewRequest): ContractResponse =
        extractionReview(req, ContractTools.LOI_REVIEW, LOI_KEYS, ContractPrompts::loiReview) { LoiFlagRules.evaluate(it) }

    /** 임대차계약 - 12개 핵심 조항 정형 추출 + 규칙 엔진 플래그(인상률·렌트프리·보증금 배수 등). */
    fun leaseReview(req: ContractReviewRequest): ContractResponse =
        extractionReview(req, ContractTools.LEASE_REVIEW, LEASE_KEYS, ContractPrompts::leaseReview) { LeaseFlagRules.evaluate(it) }

    /**
     * 정형 추출형 검토 공통 흐름: AI 추출 → 코드 규칙 플래그(ruleFlags) → 기록.
     * 규칙 플래그는 AI 와 무관하게 같은 입력이면 같은 결과라, AI 가 놓친 거래 통념 위반을 안정적으로 잡는다.
     */
    private fun extractionReview(
        req: ContractReviewRequest,
        tool: String,
        identityKeys: Array<String>,
        promptOf: (String, String?, String) -> String,
        rules: (JsonNode) -> List<RuleFlag>,
    ): ContractResponse {
        requireAiConfigured()
        val text = req.text.trim()
        if (text.length < MIN_CONTRACT_TEXT_LEN) {
            throw BadRequestException("문서 원문을 ${MIN_CONTRACT_TEXT_LEN}자 이상 입력하거나 파일에서 불러와 주세요.")
        }
        val docName = req.sourceFileName?.trim()?.takeIf { it.isNotBlank() } ?: req.dealName
        val promptText = text.take(MAX_PROMPT_CHARS)
        val factBlocks = ContractMoneyCalculator.factBlock(promptText) + ContractTermCalculator.factBlock(promptText)
        val prompt = promptOf(promptText, docName, factBlocks)

        val (parsed, raw, provider) = chargeAndCall(tool, prompt, identityKeys)
        val analysis = (parsed as? ObjectNode)?.also { it.set<JsonNode>("ruleFlags", objectMapper.valueToTree(rules(it))) }
            ?: parsed
        val perspective = ReviewPerspective.NEUTRAL

        val payload = linkedMapOf<String, Any?>(
            "tool" to tool,
            "perspective" to perspective.name,
            "perspectiveLabel" to perspective.label,
            "docName" to docName,
            "analysis" to analysis,
            "analysisRaw" to if (analysis == null) raw else null,
            "provider" to provider,
            "disclaimer" to Disclaimer.TEXT,
        )
        val run = aiToolRunService.record(
            tool = tool,
            status = RunStatus.SUCCESS,
            requestHash = sha256(prompt),
            dealName = req.dealName,
            dealId = req.dealId,
            requestJson = redactedRequestJson(req, text, perspective),
            resultJson = objectMapper.writeValueAsString(payload),
        )
        return response(run.id, run.dealId, tool, analysis, raw, provider, perspective)
    }

    /**
     * 같은 계약서의 판(버전) 2~4건 비교. 달라진 조항 검출은 코드([ContractVersionDiff])가 하고,
     * AI 는 달라진 조항에 변경 유형·사유 초안만 붙인다. 원문은 코드가 채우므로 AI 가 문구를 바꿔 쓸 수 없다.
     * 차이가 없으면 AI 를 부르지 않고 무과금으로 "차이 없음"을 돌려준다.
     */
    fun versionCompare(req: ContractVersionCompareRequest): ContractResponse {
        if (req.versions.size > VERSION_MAX) throw BadRequestException("판 비교는 최대 ${VERSION_MAX}건입니다.")
        val inputs = req.versions.filter { it.text.isNotBlank() }
        if (inputs.size !in VERSION_MIN..VERSION_MAX) {
            throw BadRequestException("판 비교는 계약서 ${VERSION_MIN}~${VERSION_MAX}건이 필요합니다.")
        }
        val labels = mutableListOf<String>()
        val versions = inputs.mapIndexed { i, v ->
            val text = v.text.trim()
            if (text.length < MIN_CONTRACT_TEXT_LEN) throw BadRequestException("${i + 1}번째 판의 본문이 너무 짧습니다.")
            if (text.length > VERSION_MAX_CHARS) throw BadRequestException("${i + 1}번째 판이 너무 깁니다(${text.length}자). 별첨을 줄여 주세요.")
            val label = uniqueLabel(v.label?.trim()?.take(MAX_LABEL_LEN)?.takeIf { it.isNotEmpty() } ?: "${i + 1}차안", labels).also { labels += it }
            ContractVersionDiff.Version(label, ContractClauseParser.parse(text))
        }
        val structured = versions.all { it.clauses.size >= 2 }
        val result = when {
            structured -> ContractVersionDiff.diff(versions)
            versions.size == 2 -> ContractVersionDiff.diffParagraphs(labels[0], inputs[0].text, labels[1], inputs[1].text)
            else -> throw BadRequestException("조·항 구조를 인식하지 못했습니다. 3건 이상은 '제1조' 형식 계약서만 비교할 수 있고, 2건이면 문단 단위로 비교합니다.")
        }
        val summary = linkedMapOf(
            "mode" to result.mode, "total" to result.total, "changed" to result.changed.size,
            "identical" to result.identical, "minor" to result.minor,
        )
        val fallbackTitle = req.dealName?.takeIf { it.isNotBlank() } ?: "계약서 판 비교"

        if (result.changed.isEmpty()) {
            val empty = objectMapper.valueToTree<JsonNode>(
                linkedMapOf("title" to fallbackTitle, "versions" to labels, "summary" to summary, "rows" to emptyList<Any>()),
            )
            return recordVersionCompare(req, empty, "", "none", labels, inputs.map { it.text.length }, sha256(labels.joinToString()))
        }

        requireAiConfigured()
        val payloadJson = objectMapper.writeValueAsString(
            linkedMapOf(
                "preamble" to inputs[0].text.trim().take(300),
                "versions" to labels.map { mapOf("label" to it) },
                // AI 에는 조항별 앞부분·최대 건수만 보낸다(원가 상한). 표의 원문은 아래에서 코드가 전체를 채운다.
                "clauses" to result.changed.take(VERSION_AI_MAX_CLAUSES).map { c ->
                    mapOf("ref" to c.ref, "heading" to c.heading, "texts" to c.texts.mapValues { (_, t) -> t?.take(VERSION_AI_CLAUSE_CHARS) })
                },
            ),
        )
        val prompt = ContractPrompts.versionCompare(payloadJson)
        val (parsed, raw, provider) = chargeAndCall(ContractTools.VERSION_COMPARE, prompt, VERSION_KEYS)

        // 행은 코드가 조립한다 - AI 가 ref 를 빠뜨려도 표는 항상 완성되고, 원문은 파서 것만 쓴다.
        val byRef = parsed?.path("rows")?.filter { it.isObject }
            ?.associateBy { ContractVersionDiff.normRef(it.path("ref").asText(it.path("clauseRef").asText(""))) }
            .orEmpty()
        val rows = result.changed.mapIndexed { idx, c ->
            val llm = byRef[ContractVersionDiff.normRef(c.ref)]
            fun llmText(key: String) = llm?.path(key)?.asText("")?.trim().orEmpty()
            linkedMapOf(
                "no" to idx + 1,
                "section" to llmText("section"),
                "clauseRef" to c.ref,
                "topic" to llmText("topic").ifEmpty { c.heading },
                "texts" to c.texts.mapValues { (_, t) -> t ?: "(해당 문구 없음)" },
                "changeType" to ContractVersionDiff.resolveChangeType(llmText("changeType"), c.texts),
                "rationale" to llmText("rationale").ifEmpty { "확인 필요" },
            )
        }
        val analysis = objectMapper.valueToTree<JsonNode>(
            linkedMapOf(
                "title" to (parsed?.path("title")?.asText("")?.takeIf { it.isNotBlank() } ?: fallbackTitle),
                "versions" to labels,
                "summary" to summary,
                "rows" to rows,
            ),
        )
        return recordVersionCompare(req, analysis, raw, provider, labels, inputs.map { it.text.length }, sha256(prompt))
    }

    private fun recordVersionCompare(
        req: ContractVersionCompareRequest,
        analysis: JsonNode,
        raw: String,
        provider: String,
        labels: List<String>,
        charCounts: List<Int>,
        hash: String,
    ): ContractResponse {
        val perspective = ReviewPerspective.NEUTRAL
        val payload = linkedMapOf<String, Any?>(
            "tool" to ContractTools.VERSION_COMPARE,
            "analysis" to analysis,
            "provider" to provider,
            "disclaimer" to Disclaimer.TEXT,
        )
        val run = aiToolRunService.record(
            tool = ContractTools.VERSION_COMPARE,
            status = RunStatus.SUCCESS,
            requestHash = hash,
            dealName = req.dealName,
            dealId = req.dealId,
            // 원문은 남기지 않는다(검토와 같은 원칙) - 판 라벨과 글자 수만.
            requestJson = objectMapper.writeValueAsString(
                mapOf("dealName" to req.dealName, "versions" to labels.zip(charCounts).map { (l, n) -> mapOf("label" to l, "charCount" to n) }),
            ),
            resultJson = objectMapper.writeValueAsString(payload),
        )
        return response(run.id, run.dealId, ContractTools.VERSION_COMPARE, analysis, raw, provider, perspective)
    }

    private fun uniqueLabel(label: String, used: List<String>): String {
        if (label !in used) return label
        return generateSequence(2) { it + 1 }.map { "$label ($it)" }.first { it !in used }
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

    /**
     * 검토 결과에 코드 실측값을 심는다.
     *  - 만원 아래를 버려 쓴 금액('30억4,687만원')은 실측 원문 숫자로 되돌린다(실측과 하나로 맞을 때만).
     *  - termFact: 기간 실측. 본문 표기를 못 찾으면 AI 가 뽑은 효력일·만료일로 검산(개월 표기는 모름).
     *  - moneyFacts: 금액 실측 - 화면이 AI 서술과 무관하게 정확한 금액을 보여 주도록.
     * 계산이 정확해도 입력 날짜가 틀릴 수 있으므로 화면은 start/end 를 함께 보여 원문과 대조하게 한다.
     */
    private fun withContractFacts(node: JsonNode, text: String): JsonNode {
        val money = ContractMoneyCalculator.extract(text)
        val restored = if (money.isEmpty()) node else restoreMoney(node, money)
        val obj = restored as? ObjectNode ?: return restored

        val term = ContractTermCalculator.extract(text).firstOrNull()
            ?: ContractTermCalculator.ofIso(obj.path("effectiveDate").asText(null), obj.path("expiryDate").asText(null))
        if (term != null) {
            obj.set<ObjectNode>(
                "termFact",
                objectMapper.valueToTree(
                    linkedMapOf(
                        "start" to term.start.toString(),
                        "end" to term.end.toString(),
                        "months" to term.months,
                        "days" to term.days,
                        "totalDays" to term.totalDays,
                        "text" to term.termText,
                        "statedMonths" to term.statedMonths,
                        "statedMatches" to term.statedMonths?.let { term.matchesStated },
                    ),
                ),
            )
        }
        if (money.isNotEmpty()) {
            obj.set<ObjectNode>(
                "moneyFacts",
                objectMapper.valueToTree(money.map { mapOf("label" to it.label, "won" to it.won, "text" to it.fullText) }),
            )
        }
        return obj
    }

    /** JSON 트리를 새로 만들며 문자열 값의 만원 절삭 금액만 되돌린다. */
    private fun restoreMoney(node: JsonNode, facts: List<ContractMoneyCalculator.MoneyFact>): JsonNode = when {
        node.isTextual -> TextNode(ContractMoneyCalculator.restoreTruncated(node.asText(), facts) ?: node.asText())
        node.isObject -> objectMapper.createObjectNode().also { out ->
            node.fields().forEach { (k, v) -> out.set<JsonNode>(k, restoreMoney(v, facts)) }
        }
        node.isArray -> objectMapper.createArrayNode().also { out -> node.forEach { out.add(restoreMoney(it, facts)) } }
        else -> node
    }

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
        val LOI_KEYS = arrayOf("buyer", "seller", "purchase_price")
        val LEASE_KEYS = arrayOf("lessor", "lessee", "rent", "term")
        val VERSION_KEYS = arrayOf("rows", "title")

        const val VERSION_MIN = 2
        const val VERSION_MAX = 4
        const val MAX_LABEL_LEN = 40

        /** 판 비교 AI 입력 상한 - 조항 수 x 조항당 글자. 넘는 조항은 AI 초안 없이 코드 기본값(수정·확인 필요)으로 표에 남는다. */
        const val VERSION_AI_MAX_CLAUSES = 60
        const val VERSION_AI_CLAUSE_CHARS = 1_200

        /** 판 비교는 AI 가 전문을 읽지 않고 코드만 파싱하므로 상한을 검토보다 크게 둔다. */
        const val VERSION_MAX_CHARS = 150_000

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
