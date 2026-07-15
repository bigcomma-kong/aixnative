package com.aixnative.ai.service

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
        val maxTokens: Int = 8192,
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

/**
 * Mistral provider config — 무료 티어 대상(신규 키만, env). 스케줄 배치 수집의 보강용으로 직접 호출되며
 * (Claude 폴백 격리), 키 미설정 시 graceful 하게 건너뛴다. priority 가 Claude(0)보다 높아(=뒤)
 * 일반 라우터에선 Claude 가 우선 — 과금 분석은 Claude, 무료 배치는 Mistral.
 */
@ConfigurationProperties(prefix = "mistral")
data class MistralProperties(
    val api: Api = Api(),
    val priority: Int = 5,
) {
    data class Api(
        val key: String = "",
        val url: String = "https://api.mistral.ai/v1/chat/completions",
        val model: String = "mistral-small-latest",
        val maxTokens: Int = 4096,
    )
}

/**
 * Gemini 이미지 생성 config — 신규 키만(env). 배경 스토리 이미지 생성에 직접 호출되며(라우터 격리),
 * 키 미설정 시 [com.aixnative.social.service.ImageEngine.isConfigured] 가 false → 타이포 폴백.
 */
@ConfigurationProperties(prefix = "gemini")
data class GeminiProperties(
    val api: Api = Api(),
) {
    data class Api(
        val key: String = "",
        val url: String = "https://generativelanguage.googleapis.com/v1beta",
        /** 이미지 생성 지원 모델. 필요 시 env(GEMINI_IMAGE_MODEL)로 교체. */
        val model: String = "gemini-2.0-flash-preview-image-generation",
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
