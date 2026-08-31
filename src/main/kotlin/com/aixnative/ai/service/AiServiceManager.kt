package com.aixnative.ai.service

import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import com.aixnative.ai.domain.AiProvider
import com.aixnative.ai.domain.AiResult

/**
 * AI router (priority + fallback orchestration):
 *  - selects configured providers by ascending priority,
 *  - returns the first success, falling back across the rest,
 *  - enforces a per-provider timeout bounded by an overall deadline.
 *
 * TODO (PORT-MAP section C, follow-up): circuit breaker + call metering.
 */
@Service
class AiServiceManager(
    private val providers: List<AiProvider>,
    private val props: AiServiceProperties,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    // Bounded pool for timeout-wrapped provider calls; shut down with the context.
    private val executor = Executors.newFixedThreadPool(AI_CALL_THREADS)

    @PreDestroy
    fun shutdown() {
        executor.shutdown()
        if (!executor.awaitTermination(5, TimeUnit.SECONDS)) executor.shutdownNow()
    }

    /** True if at least one provider is ready to be called. */
    fun hasConfiguredProvider(): Boolean = providers.any { it.isConfigured() }

    /**
     * Run [prompt] against the best available provider, falling back on failure.
     *
     * @param preferred optional provider name to try first.
     *   ⚠ 값을 주면 후보가 그 하나로 좁혀져 **폴백이 사라진다**([selectCandidates]). 사내 레거시에서
     *   문서 도구를 특정 provider 로 고정했다가 그쪽이 429 를 내는 순간 기능이 통째로 죽은 사고가 있었다.
     *   특별한 이유가 없으면 null 로 두고 우선순위 라우팅에 맡길 것.
     * @param budget 호출별 시간 예산. null 이면 전역 기본값([AiServiceProperties.providerTimeoutMs] /
     *   [AiServiceProperties.overallDeadlineMs])을 쓴다. 문서 장문 분석처럼 오래 걸리는 호출만 넘긴다.
     */
    fun complete(prompt: String, preferred: String? = null, budget: AiBudget? = null): AiResult {
        val candidates = selectCandidates(preferred)
        check(candidates.isNotEmpty()) { "설정된 AI 서비스가 없습니다. API 키를 설정하세요." }

        val providerTimeoutMs = budget?.providerTimeoutMs ?: props.providerTimeoutMs
        val overallDeadlineMs = budget?.overallDeadlineMs ?: props.overallDeadlineMs

        // 모든 프롬프트에 공통 표기 규칙 주입 — 모델이 한자를 섞어 쓰는 문제 차단(순한글 강제).
        val effectivePrompt = prompt + LANG_RULE

        val startedAt = System.currentTimeMillis()
        var lastError: Exception? = null

        for (provider in candidates) {
            val remaining = overallDeadlineMs - (System.currentTimeMillis() - startedAt)
            if (remaining <= 0) {
                log.error("[AI] 전체 deadline({}ms) 초과 — 폴백 중단", overallDeadlineMs)
                break
            }
            val timeout = minOf(providerTimeoutMs, remaining)
            try {
                log.info("[AI] {} 시도 (timeout {}ms / 남은 {}ms)", provider.name, timeout, remaining)
                val text = callWithTimeout(provider, effectivePrompt, timeout)
                log.info("[AI] {} 성공", provider.name)
                return AiResult(provider.name, text)
            } catch (e: Exception) {
                log.error("[AI] {} 실패 ({})", provider.name, e.javaClass.simpleName, e)
                lastError = e
                if (!props.autoFallback || candidates.size == 1) break
            }
        }
        throw lastError ?: RuntimeException("모든 AI 서비스에서 분석 실패")
    }

    /** Configured providers ordered by priority, minus none (Phase 1: no disabled list). */
    private fun configuredByPriority(): List<AiProvider> =
        providers.filter { it.isConfigured() }.sortedBy { it.priority }

    private fun selectCandidates(preferred: String?): List<AiProvider> {
        if (preferred.isNullOrBlank()) return configuredByPriority()
        val match = providers.firstOrNull { it.name.equals(preferred, ignoreCase = true) }
        return when {
            match != null && match.isConfigured() -> listOf(match)
            props.autoFallback -> configuredByPriority()
            else -> emptyList()
        }
    }

    /** Apply a hard timeout to a single provider call (CompletableFuture.orTimeout pattern). */
    private fun callWithTimeout(provider: AiProvider, prompt: String, timeoutMs: Long): String {
        val future = CompletableFuture.supplyAsync({ provider.complete(prompt) }, executor)
        try {
            return future.orTimeout(timeoutMs, TimeUnit.MILLISECONDS).join()
        } catch (ce: CompletionException) {
            val cause = ce.cause
            if (cause is TimeoutException) {
                future.cancel(true)
                throw TimeoutException("provider timeout ${timeoutMs}ms: ${cause.message}")
            }
            throw (cause as? Exception) ?: RuntimeException(cause ?: ce)
        }
    }

    private companion object {
        const val AI_CALL_THREADS = 16

        /** 공통 표기 규칙 — 한자 혼용 차단(순한글). 영문 약어·숫자·고유명사 원문은 예외. */
        const val LANG_RULE =
            "\n\n[표기 규칙] 모든 서술과 JSON 문자열 값은 순수 한글로 작성한다. " +
            "한자(漢字)·중국어·일본어 문자를 절대 사용하지 않는다(예: '優位'→'우위', '對備'→'대비'). " +
            "영문 약어(IRR·NOI·Cap·DSCR·LTV·GBD·CBD 등)·숫자·고유명사 원문은 그대로 둔다."
    }
}
