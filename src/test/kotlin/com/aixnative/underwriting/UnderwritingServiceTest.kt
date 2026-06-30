package com.aixnative.underwriting

import com.aixnative.ai.AiProvider
import com.aixnative.billing.CreditService
import com.aixnative.common.tenant.TenantContext
import com.aixnative.common.web.InsufficientCreditsException
import com.aixnative.common.web.NotFoundException
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.annotation.Transactional
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** 가짜 AI provider — 네트워크 없이 언더라이팅 흐름(크레딧 차감·이력·파싱)을 검증. */
class FakeAiProvider : AiProvider {
    override val name = "FakeClaude"
    override val priority = 0
    override fun isConfigured() = true
    override fun complete(prompt: String): String =
        """{"summary":"테스트 요약","guideline_check":"가이드라인 충족","key_drivers":["Exit Cap"],""" +
            """"key_risks":[{"risk":"금리 상승","impact":"MEDIUM"}],"recommendation":"GO","recommendation_reason":"테스트"}"""
}

@TestConfiguration
class UnderwritingTestConfig {
    @Bean
    fun fakeAiProvider(): AiProvider = FakeAiProvider()
}

@SpringBootTest
@ActiveProfiles("test")
@Transactional
@Import(UnderwritingTestConfig::class)
class UnderwritingServiceTest(
    @Autowired private val service: UnderwritingService,
    @Autowired private val creditService: CreditService,
    @Autowired private val reportService: ReportService,
) {

    private val tenantId = 7L
    private val userId = 7L

    private val req = UnderwriteRequest(
        dealName = "테스트딜",
        assetType = "오피스",
        askingPriceEok = 6800.0,
        noiEok = 270.0,
        ltvPct = 55.0,
        loanRatePct = 4.3,
        exitCapPct = 4.75,
        acqCostPct = 4.6, // 검산 케이스와 동일 (기본값 5.1 대신)
    )

    @AfterEach
    fun tearDown() = TenantContext.clear()

    private fun asTenant() = TenantContext.set(TenantContext.Current(tenantId, userId, "u@example.com"))

    @Test
    fun `proforma 는 무료 - 크레딧 변동 없음`() {
        asTenant()
        creditService.grantSignupCredits(tenantId, userId)
        val res = service.proForma(req)
        assertEquals(7112.8, res.proForma.totalInvestEok, 0.05)
        assertEquals(5, creditService.balance(tenantId, userId)) // 변동 없음
    }

    @Test
    fun `analyze 성공 - UNDERWRITING 3 크레딧 차감 + AI 내러티브 파싱 + 이력 기록`() {
        asTenant()
        creditService.grantSignupCredits(tenantId, userId)

        val res = service.analyze(req) // 기본 = UNDERWRITING (3크레딧)

        assertEquals(2, res.creditBalance) // 5 → 2
        assertEquals("FakeClaude", res.provider)
        assertNotNull(res.analysis)
        assertEquals("GO", res.analysis!!.get("recommendation").asText())
        assertEquals(7112.8, res.proForma.totalInvestEok, 0.05)
        assertEquals(2, creditService.balance(tenantId, userId))
    }

    @Test
    fun `analyze 잔액 0 - 402 페이월 + 미차감`() {
        asTenant() // 크레딧 미부여 → 잔액 0
        assertFailsWith<InsufficientCreditsException> { service.analyze(req) }
        assertEquals(0, creditService.balance(tenantId, userId))
    }

    @Test
    fun `분석 후 이력 저장·조회 + 타 테넌트 차단`() {
        asTenant()
        creditService.grantSignupCredits(tenantId, userId)
        val res = service.analyze(req)

        val runs = service.listRuns()
        assertEquals(1, runs.size)
        assertEquals("테스트딜", runs[0].dealName)

        val detail = service.getRun(res.runId)
        assertEquals("GO", detail.result!!.get("analysis").get("recommendation").asText())

        // 다른 테넌트는 같은 id 를 조회할 수 없다(IDOR 차단).
        TenantContext.set(TenantContext.Current(99L, 99L, "x@example.com"))
        assertFailsWith<NotFoundException> { service.getRun(res.runId) }
    }

    @Test
    fun `단계 지정 분석 - 해당 tool 로 이력 저장`() {
        asTenant()
        creditService.grantSignupCredits(tenantId, userId)

        val res = service.analyze(AnalysisType.MARKET_STUDY, req)

        assertEquals("MARKET_STUDY", res.analysisType)
        assertEquals(3, res.creditBalance) // 5 → 3 (MARKET_STUDY = 2크레딧)
        assertEquals("MARKET_STUDY", service.listRuns().first().tool)
    }

    @Test
    fun `보고서 HTML - 같은 딜의 단계들을 합본`() {
        asTenant()
        creditService.grantSignupCredits(tenantId, userId)
        val uw = service.analyze(AnalysisType.UNDERWRITING, req)
        service.analyze(AnalysisType.SCREENING, req)

        val html = reportService.buildHtml(uw.runId)

        assertTrue(html.contains("투자 분석 보고서"))
        assertTrue(html.contains("테스트딜"))
        assertTrue(html.contains("Levered IRR"))
        assertTrue(html.contains("언더라이팅")) // 단계 라벨 섹션
    }

    @Test
    fun `중복 분석 가드 - 동일 입력 재실행만 감지(단계·입력·테넌트 스코프)`() {
        asTenant()
        creditService.grantSignupCredits(tenantId, userId)
        val res = service.analyze(AnalysisType.MARKET_STUDY, req)

        // 동일 입력·동일 단계 → 중복
        val dup = service.checkDuplicate(AnalysisType.MARKET_STUDY, req)
        assertTrue(dup.duplicate)
        assertEquals(res.runId, dup.lastRunId)
        assertEquals(60L, dup.withinMinutes)

        // 다른 단계 → 중복 아님
        assertTrue(!service.checkDuplicate(AnalysisType.SCREENING, req).duplicate)

        // 입력 변경 → 중복 아님
        assertTrue(!service.checkDuplicate(AnalysisType.MARKET_STUDY, req.copy(askingPriceEok = 9999.0)).duplicate)

        // 다른 테넌트 → 중복 아님(스코프 격리)
        TenantContext.set(TenantContext.Current(99L, 99L, "x@example.com"))
        assertTrue(!service.checkDuplicate(AnalysisType.MARKET_STUDY, req).duplicate)
    }

    @Test
    fun `중복 가드는 과금하지 않는다`() {
        asTenant()
        creditService.grantSignupCredits(tenantId, userId)
        val before = creditService.balance(tenantId, userId)
        service.checkDuplicate(AnalysisType.MARKET_STUDY, req)
        assertEquals(before, creditService.balance(tenantId, userId)) // 변동 없음
    }

    @Test
    fun `보고서 - 분석 이력 없으면 404`() {
        asTenant()
        creditService.grantSignupCredits(tenantId, userId)
        val uw = service.analyze(req)
        // 다른 테넌트가 남의 run 으로 보고서를 만들 수 없다.
        TenantContext.set(TenantContext.Current(99L, 99L, "x@example.com"))
        assertFailsWith<NotFoundException> { reportService.buildHtml(uw.runId) }
    }
}
