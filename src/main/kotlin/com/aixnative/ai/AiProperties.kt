package com.aixnative.ai

import org.springframework.boot.context.properties.ConfigurationProperties

/** Claude provider config. All values injected from env — new keys only. */
@ConfigurationProperties(prefix = "claude")
data class ClaudeProperties(
    val api: Api,
    val oauth: Oauth = Oauth(),
    val priority: Int = 0,
) {
    data class Api(
        val key: String = "",
        val url: String = "https://api.anthropic.com/v1/messages",
        val model: String = "claude-opus-4-8",
        val maxTokens: Int = 4096,
    )

    /**
     * Subscription OAuth access token (`sk-ant-oat...`, issued via `claude setup-token`).
     * When set, the client authenticates with `Authorization: Bearer` + the oauth beta
     * header instead of `x-api-key`. Personal/single-token mode (round-robin omitted).
     */
    data class Oauth(
        val token: String = "",
    )
}

/** AI router behaviour (priority/fallback/timeout). */
@ConfigurationProperties(prefix = "ai.service")
data class AiServiceProperties(
    val autoFallback: Boolean = true,
    /** Per-provider single-call timeout (ms). */
    val providerTimeoutMs: Long = 75_000,
    /** Overall deadline across all fallback attempts (ms). */
    val overallDeadlineMs: Long = 90_000,
)
