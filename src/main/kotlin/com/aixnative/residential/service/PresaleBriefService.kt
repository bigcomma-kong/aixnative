package com.aixnative.residential.service

import com.aixnative.ai.service.AiServiceManager
import com.aixnative.billing.domain.ToolPricing
import com.aixnative.billing.service.CreditGate
import com.aixnative.billing.service.CreditService
import com.aixnative.common.tenant.TenantContext
import com.aixnative.common.web.InsufficientCreditsException
import com.aixnative.common.web.ServiceUnavailableException
import com.aixnative.residential.domain.LocationReport
import com.aixnative.residential.domain.MonthlyPrice
import com.aixnative.residential.domain.PresaleNotice
import com.aixnative.residential.domain.ResidentialBrief
import com.aixnative.residential.repository.ResidentialBriefRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * AI 동네 브리핑(크레딧 과금, 인증) - 특정 주소/지역의 **실측 컨텍스트**(인근 단지·최근 실거래·평단가 추이·
 * 기준금리 + 그 지역 분양공고)를 묶어 Claude 가 실수요자 관점 시장·청약 브리핑을 작성. 결과는 저장(마이페이지).
 * 무료 리포트(공공데이터 조립)와 달리 이 서술만 과금([ToolPricing] PRESALE_BRIEF). ADMIN 무제한.
 */
@Service
class PresaleBriefService(
    private val locationReportService: LocationReportService,
    private val cheongyak: CheongyakClient,
    private val creditGate: CreditGate,
    private val aiServiceManager: AiServiceManager,
    private val creditService: CreditService,
    private val briefRepo: ResidentialBriefRepository,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** 브리핑 결과(서술 + 저장 id + 남은 크레딧). */
    data class Result(val id: Long?, val brief: String, val creditBalance: Int)

    /** 저장된 브리핑(마이페이지 목록). */
    data class Saved(val id: Long, val query: String, val region: String?, val brief: String, val createdAt: String?)

    /** query(주소/지역) 기준 지역 맞춤 브리핑. 성공 시 저장·과금. AI 미설정/주소인식 실패 시 예외(미차감). */
    fun brief(query: String): Result {
        if (!aiServiceManager.hasConfiguredProvider()) {
            throw ServiceUnavailableException("AI 분석 서비스가 설정되지 않았습니다(API 키 미설정).")
        }
        val report = locationReportService.report(query.trim())
        val geo = report.geo
            ?: throw IllegalArgumentException("주소를 인식하지 못했습니다. 도로명/동 단위로 입력해 주세요.")
        val region = shortSido(geo.roadAddress ?: geo.jibunAddress ?: query)
        val trend = locationReportService.priceTrend(geo.sigunguCode, 12)
        val notices = cheongyak.recentNotices(region, 8)

        val ai = try {
            creditGate.charge(ToolPricing.costOf(TOOL)) { aiServiceManager.complete(buildPrompt(query, region, report, trend, notices)) }
        } catch (e: InsufficientCreditsException) {
            throw e
        } catch (e: Exception) {
            log.error("[residential] 동네 브리핑 AI 실패", e)
            throw ServiceUnavailableException("AI 브리핑 호출에 실패했습니다: ${e.message}")
        }

        val cur = TenantContext.require()
        val saved = briefRepo.save(
            ResidentialBrief(query = query.trim().take(300), region = region, briefText = ai.text.trim()).also {
                it.tenantId = cur.tenantId
                it.ownerUserId = cur.userId
            },
        )
        return Result(saved.id, ai.text.trim(), creditService.balance(cur.tenantId, cur.userId))
    }

    /** 현재 사용자의 저장된 브리핑 목록(최신순). */
    fun myBriefs(): List<Saved> {
        val cur = TenantContext.require()
        return briefRepo.findTop50ByTenantIdAndOwnerUserIdOrderByIdDesc(cur.tenantId, cur.userId)
            .map { Saved(it.id ?: 0, it.query, it.region, it.briefText, it.createdAt?.toString()) }
    }

    private fun buildPrompt(
        query: String,
        region: String?,
        report: LocationReport,
        trend: List<MonthlyPrice>,
        notices: List<PresaleNotice>,
    ): String {
        val scope = region?.let { "$it · " }.orEmpty() + query.trim()

        val complexLines = report.complexes.joinToString("\n") { c ->
            "- ${c.name}: ${c.householdCount?.let { "${it}세대" } ?: "-"}, " +
                "${c.approvalDate?.take(4)?.let { "${it}년 승인" } ?: "-"}, " +
                "주차 ${c.parkingTotal ?: "-"}, ${c.heatingType ?: "-"}"
        }.ifBlank { "- (없음)" }

        val dealLines = report.recentDeals.take(6).joinToString("\n") { d ->
            "- ${d.dealYmd} ${d.aptName}(${d.dong ?: "-"}) 전용${d.areaSqm}㎡ ${d.floor}층 ${d.amountManwon}만원"
        }.ifBlank { "- (없음)" }

        val trendLine = if (trend.size >= 2) {
            val first = trend.first()
            val last = trend.last()
            "${first.ym} ${first.avgPricePerPyeong}만원/평 → ${last.ym} ${last.avgPricePerPyeong}만원/평 " +
                "(월 거래 ${trend.minOf { it.dealCount }}~${trend.maxOf { it.dealCount }}건)"
        } else "- (데이터 부족)"

        val macroLine = report.macro?.let {
            "기준금리 ${it.baseRate}% · 국고채10년 ${it.gov10y}% (${it.asOf ?: "-"})"
        } ?: "- (없음)"

        val noticeLines = notices.joinToString("\n") { n ->
            "- ${n.region ?: "-"} ${n.houseName} ${n.totalSupply?.let { "${it}세대" } ?: ""} " +
                "청약 ${n.receiptStart ?: "-"}~${n.receiptEnd ?: "-"}"
        }.ifBlank { "- (해당 지역 최근 공고 없음)" }

        return """
            당신은 대한민국 주거 부동산·청약 애널리스트입니다. 아래 **$scope** 의 실측 데이터(공공데이터)를 바탕으로
            실수요자 관점의 시장·청약 브리핑을 작성하세요.

            규칙(엄수):
            - 제공된 데이터 안에서만 사실을 쓴다(추측·과장·투자권유·수익보장 금지, "투자자문 아님" 톤).
            - 한국어 8~12문장. 포함: ① 시세 수준·추세(평단가 방향), ② 주목할 인근 단지(규모·연식),
              ③ 청약/분양 관점(해당 지역 공고, 시세 대비 눈여겨볼 점, 청약 일정 임박 건), ④ 금리 맥락, ⑤ 실수요 유의점.

            <평단가 추이(시군구)>
            $trendLine

            <최근 실거래>
            $dealLines

            <인근 단지>
            $complexLines

            <거시>
            $macroLine

            <해당 지역 최근 분양공고>
            $noticeLines
        """.trimIndent()
    }

    /** "서울특별시"→"서울", "경기도"→"경기" 등 시도 축약(분양공고 지역 필터·표시용). */
    private fun shortSido(addr: String): String? {
        val first = addr.trim().split(" ").firstOrNull()?.trim().orEmpty()
        return first
            .removeSuffix("특별자치시").removeSuffix("특별자치도")
            .removeSuffix("특별시").removeSuffix("광역시").removeSuffix("도")
            .ifBlank { null }
    }

    private companion object {
        const val TOOL = "PRESALE_BRIEF"
    }
}
