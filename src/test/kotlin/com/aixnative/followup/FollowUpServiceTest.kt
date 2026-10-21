package com.aixnative.followup

import com.aixnative.ai.domain.AiProvider
import com.aixnative.ai.domain.RunStatus
import com.aixnative.ai.service.AiToolRunService
import com.aixnative.billing.service.CreditService
import com.aixnative.common.tenant.TenantContext
import com.aixnative.common.web.BadRequestException
import com.aixnative.common.web.InsufficientCreditsException
import com.aixnative.common.web.NotFoundException
import com.aixnative.common.web.ServiceUnavailableException
import com.aixnative.followup.service.FollowUpService
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
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
import kotlin.test.assertTrue

/** 프롬프트 종류에 따라 답을 바꾸는 가짜 AI. [mode] 로 실패·이상 응답을 흉내낸다. */
class FollowUpFakeAi : AiProvider {
    var mode = "ok"
    var calls = 0
    override val name = "FakeFollowUp"
    override val priority = -1
    override fun isConfigured() = true
    override fun complete(prompt: String): String {
        calls++
        return when {
            mode == "garbage" -> "응답 형식 깨짐"
            prompt.contains("독립 검토위원") ->
                """{"verdict":"REVISE","confidence":"MEDIUM","summary":"임대료 가정 근거 부족","issues":[],"invalidation":["공실 확대"]}"""
            prompt.contains("질문 형태") -> """{"markdown":"- 제안 하나"}"""
            else -> """{"markdown":"**결론**: 6,500억원 이하에서 검토 가능"}"""
        }
    }
}

@TestConfiguration
class FollowUpTestConfig {
    @Bean
    fun followUpFakeAi(): FollowUpFakeAi = FollowUpFakeAi()
}

@SpringBootTest
@ActiveProfiles("test")
@Transactional
@Import(FollowUpTestConfig::class)
class FollowUpServiceTest(
    @Autowired private val service: FollowUpService,
    @Autowired private val runs: AiToolRunService,
    @Autowired private val credits: CreditService,
    @Autowired private val fakeAi: FollowUpFakeAi,
) {
    private val tenantId = 41L
    private val userId = 41L

    @BeforeEach
    fun setUp() {
        TenantContext.set(TenantContext.Current(tenantId, userId, "f@example.com"))
        fakeAi.mode = "ok"
        fakeAi.calls = 0
    }

    @AfterEach
    fun tearDown() = TenantContext.clear()

    private fun seedRun(status: RunStatus = RunStatus.SUCCESS): Long = requireNotNull(
        runs.record(
            tool = "IC_MEMO",
            status = status,
            dealName = "테스트딜",
            resultJson = """{"analysis":{"verdict":"GO"},"disclaimer":"면책"}""",
        ).id,
    )

    @Test
    fun `ask stores a user and ai turn and charges one credit`() {
        credits.grantSignupCredits(tenantId, userId)
        val runId = seedRun()

        val res = service.ask(runId, "어느 가격대부터 검토 가능한가?")

        assertEquals(listOf("USER", "AI"), res.turns.map { it.role.name })
        assertTrue(res.turns[1].content.contains("6,500억원"))
        assertEquals(4, res.creditBalance)
    }

    @Test
    fun `suggest and critique append to the same thread with their own prices`() {
        credits.grantSignupCredits(tenantId, userId)
        val runId = seedRun()

        service.suggest(runId)
        val res = service.critique(runId)

        assertEquals(listOf(0, 1), res.turns.map { it.turnSeq })
        assertEquals("CRITIQUE", res.turns[1].role.name)
        assertTrue(res.turns[1].content.contains("\"verdict\":\"REVISE\""))
        assertEquals(5 - 1 - 2, res.creditBalance)
    }

    @Test
    fun `unparseable ai output retries once, then fails without charging`() {
        credits.grantSignupCredits(tenantId, userId)
        val runId = seedRun()
        fakeAi.mode = "garbage"

        assertFailsWith<ServiceUnavailableException> { service.critique(runId) }
        assertEquals(2, fakeAi.calls)
        assertEquals(5, credits.balance(tenantId, userId))
        assertTrue(service.thread(runId).turns.isEmpty())
    }

    @Test
    fun `no credits means 402 before any ai call`() {
        val runId = seedRun()

        assertFailsWith<InsufficientCreditsException> { service.ask(runId, "질문") }
        assertEquals(0, fakeAi.calls)
    }

    @Test
    fun `failed runs and blank questions are rejected`() {
        credits.grantSignupCredits(tenantId, userId)

        assertFailsWith<BadRequestException> { service.suggest(seedRun(RunStatus.FAILED)) }
        assertFailsWith<BadRequestException> { service.ask(seedRun(), "   ") }
    }

    @Test
    fun `another tenant cannot read or extend the thread`() {
        val runId = seedRun()
        TenantContext.set(TenantContext.Current(999L, 999L, "other@example.com"))

        assertFailsWith<NotFoundException> { service.thread(runId) }
        assertFailsWith<NotFoundException> { service.ask(runId, "남의 딜") }
    }
}
