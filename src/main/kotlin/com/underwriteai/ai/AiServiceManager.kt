package com.underwriteai.ai

import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * AI router ported from MASTERN AiServiceManager (slimmed for Phase 1):
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
     * @param preferred optional provider name to try first.
     */
    fun complete(prompt: String, preferred: String? = null): AiResult {
        val candidates = selectCandidates(preferred)
        check(candidates.isNotEmpty()) { "설정된 AI 서비스가 없습니다. API 키를 설정하세요." }

        val startedAt = System.currentTimeMillis()
        var lastError: Exception? = null

        for (provider in candidates) {
            val remaining = props.overallDeadlineMs - (System.currentTimeMillis() - startedAt)
            if (remaining <= 0) {
                log.error("[AI] 전체 deadline({}ms) 초과 — 폴백 중단", props.overallDeadlineMs)
                break
            }
            val timeout = minOf(props.providerTimeoutMs, remaining)
            try {
                log.info("[AI] {} 시도 (timeout {}ms / 남은 {}ms)", provider.name, timeout, remaining)
                val text = callWithTimeout(provider, prompt, timeout)
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
    }
}
