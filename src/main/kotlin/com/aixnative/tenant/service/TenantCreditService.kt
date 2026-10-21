package com.aixnative.tenant.service

import com.aixnative.ai.domain.RunStatus
import com.aixnative.ai.service.AiJsonExtractor
import com.aixnative.ai.service.AiServiceManager
import com.aixnative.ai.service.AiToolRunService
import com.aixnative.billing.domain.ToolPricing
import com.aixnative.billing.service.CreditGate
import com.aixnative.billing.service.CreditService
import com.aixnative.common.Disclaimer
import com.aixnative.common.tenant.TenantContext
import com.aixnative.common.web.BadRequestException
import com.aixnative.common.web.InsufficientCreditsException
import com.aixnative.common.web.NotFoundException
import com.aixnative.common.web.ServiceUnavailableException
import com.aixnative.integration.dart.domain.DartCorp
import com.aixnative.integration.dart.service.DartClient
import com.aixnative.tenant.domain.TenantCreditCalculator
import com.aixnative.underwriting.service.UnderwritingPrompts
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * 임차인 신용·지급능력 분석 - DART 사업보고서 재무(최근 5개년) → 코드 계산(비율·등급·지급능력·10년 임대료 커버리지)
 * → AI 는 그 결과를 임대차 실무 관점으로 서술만 한다(새 수치 창작 금지).
 *
 * 거래상대방 실사(COUNTERPARTY_DD)가 사업자 상태만 보는 데 비해, 이 도구는 "임대료를 낼 수 있는 회사인가"를 본다.
 */
@Service
class TenantCreditService(
    private val dart: DartClient,
    private val aiServiceManager: AiServiceManager,
    private val aiToolRunService: AiToolRunService,
    private val creditGate: CreditGate,
    private val creditService: CreditService,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** 회사 검색(무료) - 자동완성용. */
    fun search(q: String): List<DartCorp> {
        requireDart()
        val query = q.trim().take(MAX_QUERY_LEN)
        if (query.length < 2) return emptyList()
        // 예외 메시지에는 인증키가 든 요청 URL 이 섞일 수 있어 사용자에게는 일반 문구만 돌려준다.
        return runCatching { dart.searchCompanies(query, SEARCH_LIMIT) }
            .getOrElse {
                log.warn("[TenantCredit] DART 회사 목록 조회 실패: {}", it.javaClass.simpleName)
                throw ServiceUnavailableException("DART 회사 목록을 불러오지 못했습니다. 잠시 후 다시 시도해 주세요.")
            }
    }

    fun analyze(req: TenantCreditRequest): TenantCreditResponse {
        requireDart()
        if (!aiServiceManager.hasConfiguredProvider()) throw ServiceUnavailableException("AI 분석 서비스가 설정되지 않았습니다(API 키 미설정).")
        val identifier = req.company.trim().take(MAX_QUERY_LEN).takeIf { it.isNotEmpty() } ?: throw BadRequestException("회사명·종목코드를 입력해 주세요.")
        if (req.monthlyRentWon != null && req.monthlyRentWon <= 0) throw BadRequestException("월 임대료는 0보다 커야 합니다.")

        // 1) DART 조회(무과금 구간) - 재무가 없으면 AI 를 부르지 않고 끝낸다.
        val corp = dartCall { dart.resolve(identifier) }
            ?: throw NotFoundException("'$identifier' 에 해당하는 회사를 DART 에서 찾지 못했습니다.")
        val overview = dartCall { dart.overview(corp.corpCode) }
        val history = dartCall { dart.annualFinancials(corp.corpCode, HISTORY_YEARS) }
        val latest = history.firstOrNull()
            ?: throw NotFoundException("${corp.corpName} 의 사업보고서 재무를 DART 에서 찾지 못했습니다. 비상장 소규모 법인은 사업보고서가 없을 수 있습니다.")

        // 2) 코드 계산
        val ratios = TenantCreditCalculator.ratios(latest)
        val rating = TenantCreditCalculator.rate(ratios)
        val capacity = TenantCreditCalculator.paymentCapacity(latest, ratios)
        val risks = TenantCreditCalculator.risks(latest, ratios)
        val simulation = req.monthlyRentWon?.let {
            TenantCreditCalculator.simulate(latest, history, it, req.rentGrowthPct ?: DEFAULT_RENT_GROWTH)
        }
        val calc = linkedMapOf(
            "company" to overview,
            "corp" to corp,
            "latestYear" to latest.year,
            "fsDiv" to latest.fsDivLabel,
            "history" to history,
            "ratios" to ratios,
            "rating" to rating,
            "paymentCapacity" to capacity,
            "codeRisks" to risks,
            "simulation" to simulation,
            "source" to "금융감독원 OpenDART 사업보고서(${latest.year}년, ${latest.fsDivLabel})",
        )

        // 3) AI 서술(과금)
        val prompt = TenantCreditPrompts.narrative(objectMapper.writeValueAsString(calc), corp.corpName)
        val ai = try {
            creditGate.charge(ToolPricing.costOf(TOOL)) {
                repeat(MAX_ATTEMPTS) {
                    val r = aiServiceManager.complete(prompt)
                    AiJsonExtractor.parseUsable(r.text, "summary", "verdict")?.let { node -> return@charge r.provider to node }
                    log.warn("[TenantCredit] 응답 파싱 실패 - 재시도")
                }
                throw IllegalStateException("AI 응답을 해석하지 못했습니다. 잠시 후 다시 시도해 주세요.")
            }
        } catch (e: InsufficientCreditsException) {
            throw e
        } catch (e: Exception) {
            log.error("[TenantCredit] AI 서술 실패", e)
            throw ServiceUnavailableException(e.message?.take(300) ?: "AI 분석 호출에 실패했습니다.")
        }

        val calcNode: JsonNode = objectMapper.valueToTree(calc)
        val payload = linkedMapOf<String, Any?>(
            "tool" to TOOL,
            "calc" to calcNode,
            "analysis" to ai.second,
            "provider" to ai.first,
            "disclaimer" to Disclaimer.TEXT,
        )
        val run = aiToolRunService.record(
            tool = TOOL,
            status = RunStatus.SUCCESS,
            dealName = req.dealName?.takeIf { it.isNotBlank() } ?: corp.corpName,
            dealId = req.dealId,
            requestJson = objectMapper.writeValueAsString(req),
            resultJson = objectMapper.writeValueAsString(payload),
        )
        val current = TenantContext.require()
        return TenantCreditResponse(
            runId = requireNotNull(run.id),
            dealId = requireNotNull(run.dealId),
            calc = calcNode,
            analysis = ai.second,
            provider = ai.first,
            creditBalance = creditService.balance(current.tenantId, current.userId),
            disclaimer = Disclaimer.TEXT,
        )
    }

    /** DART 호출 실패를 503 으로 - 예외 메시지(요청 URL 의 인증키 포함 가능)는 사용자에게 내보내지 않는다. */
    private fun <T> dartCall(block: () -> T): T = try {
        block()
    } catch (e: Exception) {
        log.warn("[TenantCredit] DART 호출 실패: {}", e.javaClass.simpleName)
        throw ServiceUnavailableException("DART 조회에 실패했습니다. 잠시 후 다시 시도해 주세요.")
    }

    private fun requireDart() {
        if (!dart.enabled) throw ServiceUnavailableException("임차인 신용 분석은 OpenDART 인증키 설정 후 사용할 수 있습니다.")
    }

    companion object {
        const val TOOL = "TENANT_CREDIT"
        private const val HISTORY_YEARS = 5
        private const val SEARCH_LIMIT = 10
        private const val MAX_QUERY_LEN = 50
        private const val MAX_ATTEMPTS = 2
        private const val DEFAULT_RENT_GROWTH = 3.0
    }
}

object TenantCreditPrompts {
    /** 코드가 계산한 신용·지급능력 결과(JSON)를 임대차 실무 관점으로 서술. 수치는 인용만. */
    fun narrative(calcJson: String, corpName: String): String =
        "당신은 한국 상업용 부동산 임대인을 돕는 임차인 신용 심사 애널리스트입니다.\n" +
            "아래 <DATA> 는 '$corpName' 의 DART 사업보고서 재무와 **코드가 계산한** 재무비율·신용등급·지급능력·임대료 커버리지 시뮬레이션입니다.\n" +
            "이를 근거로 이 회사를 임차인으로 받을 때의 판단을 JSON 으로 쓰세요.\n\n" +
            UnderwritingPrompts.COMMON_STRICT_RULES +
            "- 등급·점수·비율·커버리지는 <DATA> 값을 그대로 인용하고 다시 계산하지 않는다. <DATA> 에 없는 업황·뉴스·신용평가사 등급을 지어내지 않는다.\n" +
            "- 금액은 <DATA> 가 원 단위이므로 억원으로 옮길 때 자릿수에 주의하고, 애매하면 원 단위 그대로 쓴다.\n" +
            "- 연도별 추이(history)에서 매출·영업이익·현금흐름의 방향을 짚는다. 단년 수치만 보고 결론 내지 않는다.\n" +
            "- simulation 이 있으면 기준·비관 시나리오의 커버리지와 손익분기 연차를 근거로 계약 기간·보증금 수준을 제안한다. 없으면 월 임대료 입력 시 시뮬레이션이 가능하다고 안내한다.\n" +
            "- 투자·법률 자문이 아닌 심사 보조 의견. 대시(—·–) 대신 하이픈(-).\n\n" +
            "[출력 스키마]\n" +
            "{\n" +
            "  \"verdict\": \"적격|조건부|부적격\",\n" +
            "  \"summary\": \"임차인 적격성 결론 2~3문장\",\n" +
            "  \"trend\": \"최근 연도 추이 해석 2~3문장\",\n" +
            "  \"strengths\": [\"강점\"],\n" +
            "  \"concerns\": [\"우려 사항\"],\n" +
            "  \"leaseTerms\": { \"depositMonths\": \"권장 보증금(월 임대료 N개월분) 또는 null\", \"termYears\": \"권장 계약 기간 또는 null\", \"guarantees\": [\"보증·담보·특약 제안\"] },\n" +
            "  \"monitoring\": [\"계약 후 점검할 지표·공시\"]\n" +
            "}\n\n" +
            "<DATA>\n" + calcJson + "\n</DATA>"
}

data class TenantCreditRequest(
    val dealId: Long? = null,
    val dealName: String? = null,
    /** 회사명·종목코드(6)·DART 고유번호(8). */
    val company: String = "",
    /** 월 임대료(원) - 있으면 10년 커버리지 시뮬레이션을 돌린다. */
    val monthlyRentWon: Long? = null,
    /** 연 임대료 인상률(%). 기본 3. */
    val rentGrowthPct: Double? = null,
)

data class TenantCreditResponse(
    val runId: Long,
    val dealId: Long,
    /** 코드 계산 결과(AI 아님) - 회사·재무 이력·비율·등급·지급능력·시뮬레이션. */
    val calc: JsonNode,
    val analysis: JsonNode,
    val provider: String,
    val creditBalance: Int,
    val disclaimer: String,
)
