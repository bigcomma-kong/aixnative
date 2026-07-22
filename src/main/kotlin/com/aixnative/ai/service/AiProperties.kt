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
        /** 이미지 생성 모델 = Nano Banana(Gemini 2.5 Flash Image, 무료 500장/일). 필요 시 env(GEMINI_IMAGE_MODEL)로 교체. */
        val model: String = "gemini-2.5-flash-image",
    )
}

/**
 * Pexels 무료 스톡사진 config — 신규 키만(env PEXELS_API_KEY). 생성형 그림(Gemini) 대안으로
 * 장면 키워드에 맞는 실사진을 검색해 배경으로. 무료 티어(200/시간·20000/월), 건당 0원.
 * 키 미설정 시 [com.aixnative.social.service.ImageEngine.isConfigured] false → 편집형 타이포 폴백.
 */
@ConfigurationProperties(prefix = "pexels")
data class PexelsProperties(
    val api: Api = Api(),
) {
    data class Api(
        val key: String = "",
        val url: String = "https://api.pexels.com/v1",
    )
}

/**
 * Pollinations 무료 AI 이미지 생성 config — **키 불필요·건당 0원**. 장면 프롬프트로 AI 그림을 생성해 배경으로.
 * Gemini(유료 빌링 필요)와 달리 진짜 무료라 생성형 그림의 기본 경로. 스톡(Pixabay/Pexels)보다 우선(Order).
 * 생성 실패/느림 시 [com.aixnative.social.service.StoryImageComposer] 가 다음 엔진(스톡)으로 장면별 폴백.
 * model: flux(품질)·turbo(속도) 등. enabled=false 로 끄면 스톡/타이포로 폴백.
 */
@ConfigurationProperties(prefix = "pollinations")
data class PollinationsProperties(
    val enabled: Boolean = true,
    val url: String = "https://image.pollinations.ai/prompt",
    val model: String = "flux",
)

/**
 * Pixabay 무료 스톡사진 config — 신규 키만(application-secret.yml). Pexels 와 함께 스톡 대안,
 * 키는 계정 페이지에 바로 노출돼 발급이 수월. 키 미설정 시 isConfigured false → 편집형 타이포 폴백.
 */
@ConfigurationProperties(prefix = "pixabay")
data class PixabayProperties(
    val api: Api = Api(),
) {
    data class Api(
        val key: String = "",
        val url: String = "https://pixabay.com/api",
    )
}

/**
 * 스크래핑 프록시(ScraperAPI 등) config — 주거용 IP + (필요 시) JS 렌더로 Cloud Run 데이터센터 IP 차단을 우회.
 * 커뮤니티 핫글 리스트 수집·본문 딥페치에 사용([com.aixnative.social.service.ScrapingProxy]).
 * 키 미설정 시 직접 fetch(현행) - graceful. render/premium 은 크레딧 소모가 커 기본 off
 * (대상 대부분이 서버렌더 HTML이라 IP 차단만 우회하면 됨). 무료 티어(월 ~1000건)로 하루 1~2회 수집 커버.
 */
@ConfigurationProperties(prefix = "scraping")
data class ScrapingProxyProperties(
    val api: Api = Api(),
) {
    data class Api(
        val key: String = "",
        val url: String = "https://api.scraperapi.com",
        /** JS 렌더(SPA/JS 챌린지 사이트용). 크레딧 배수 소모 → 기본 off. */
        val renderJs: Boolean = false,
        /** 프리미엄 주거 IP(강한 안티봇용). 크레딧 소모 큼 → 기본 off. */
        val premium: Boolean = false,
        /** 지오타겟 국가코드(예: kr). 유료 플랜 기능일 수 있어 기본 빈값. */
        val countryCode: String = "",
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
