package com.aixnative.residential.service

import com.aixnative.ai.service.AiServiceManager
import com.aixnative.billing.domain.ToolPricing
import com.aixnative.billing.service.CreditGate
import com.aixnative.billing.service.CreditService
import com.aixnative.common.tenant.TenantContext
import com.aixnative.common.web.InsufficientCreditsException
import com.aixnative.common.web.ServiceUnavailableException
import com.aixnative.residential.domain.PresaleNotice
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * AI 분양 브리핑(크레딧 과금, 인증) - 최근 청약 분양공고를 실수요자 관점으로 요약·비교.
 * 무료 동네 리포트의 '분양 동향' 목록과 달리, Claude 서술은 크레딧 게이트로 과금(성공 시에만 차감, ADMIN 무제한).
 * [com.aixnative.underwriting.service.UnderwritingService] 의 creditGate.charge{aiServiceManager.complete} 패턴 미러링.
 */
@Service
class PresaleBriefService(
    private val cheongyak: CheongyakClient,
    private val creditGate: CreditGate,
    private val aiServiceManager: AiServiceManager,
    private val creditService: CreditService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** 브리핑 결과(서술 + 사용 공고 수 + 남은 크레딧). */
    data class Result(val brief: String, val noticesUsed: Int, val creditBalance: Int)

    /** region(선택) 최근 분양공고 브리핑. AI 미설정/공고없음 시 예외(크레딧 미차감). */
    fun brief(region: String?): Result {
        if (!aiServiceManager.hasConfiguredProvider()) {
            throw ServiceUnavailableException("AI 분석 서비스가 설정되지 않았습니다(API 키 미설정).")
        }
        val notices = cheongyak.recentNotices(region?.trim()?.ifBlank { null }, NOTICE_LIMIT)
        require(notices.isNotEmpty()) { "최근 분양공고가 없어 브리핑을 만들 수 없습니다." }

        val ai = try {
            creditGate.charge(ToolPricing.costOf(TOOL)) { aiServiceManager.complete(buildPrompt(notices, region)) }
        } catch (e: InsufficientCreditsException) {
            throw e // 402 페이월은 그대로
        } catch (e: Exception) {
            log.error("[residential] 분양 브리핑 AI 실패", e)
            throw ServiceUnavailableException("AI 브리핑 호출에 실패했습니다: ${e.message}")
        }
        val cur = TenantContext.require()
        return Result(ai.text.trim(), notices.size, creditService.balance(cur.tenantId, cur.userId))
    }

    private fun buildPrompt(notices: List<PresaleNotice>, region: String?): String {
        val scope = region?.trim()?.ifBlank { null }?.let { "$it 지역" } ?: "전국"
        val facts = notices.joinToString("\n") { n ->
            "- ${n.region ?: "-"} · ${n.houseName} · ${n.totalSupply ?: "-"}세대 · " +
                "공고 ${n.noticeDate ?: "-"} · 청약 ${n.receiptStart ?: "-"}~${n.receiptEnd ?: "-"} · 당첨 ${n.winnerDate ?: "-"}"
        }
        return """
            당신은 대한민국 청약·분양 시장 애널리스트입니다. 아래 최근 $scope 아파트 분양공고(공공데이터, 청약홈)를
            바탕으로 실수요자 관점의 간결한 브리핑을 작성하세요.

            규칙(엄수):
            - 제공된 공고 목록 안에서만 사실을 쓴다(추측·과장·투자권유 금지).
            - 한국어 6~9문장. 주목할 단지(규모·지역), 청약 일정이 임박한 건, 지역 분포 특징, 실수요 유의점을 담는다.
            - 특정 단지 매수 권유·수익 보장 표현 금지("투자자문 아님" 톤 유지).

            <최근 분양공고>
            $facts
            </최근 분양공고>
        """.trimIndent()
    }

    private companion object {
        const val TOOL = "PRESALE_BRIEF"
        const val NOTICE_LIMIT = 12
    }
}
