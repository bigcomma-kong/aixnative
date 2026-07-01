package com.aixnative.common.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.web.client.RestClient
import java.time.Duration

/**
 * Shared [RestClient] for outbound AI provider calls. Generous read timeout for
 * heavy LLM responses; the [com.aixnative.ai.service.AiServiceManager] enforces an
 * additional per-call deadline on top of this.
 */
@Configuration
class RestClientConfig {

    @Bean
    fun aiRestClient(builder: RestClient.Builder): RestClient {
        // Read timeout aligned just above the router's overall deadline (90s) so a
        // stalled socket cannot park a worker thread far longer than the AI deadline.
        val factory = SimpleClientHttpRequestFactory().apply {
            setConnectTimeout(Duration.ofSeconds(10))
            setReadTimeout(Duration.ofSeconds(95))
        }
        return builder.requestFactory(factory).build()
    }

    /**
     * Short-timeout client for public market-data APIs (ECOS·R-ONE·RTMS·Kakao).
     * These must respond quickly; a slow source is skipped (graceful degrade) rather
     * than parking the analysis request.
     */
    @Bean
    fun marketDataRestClient(builder: RestClient.Builder): RestClient {
        val factory = SimpleClientHttpRequestFactory().apply {
            setConnectTimeout(Duration.ofSeconds(5))
            setReadTimeout(Duration.ofSeconds(8))
        }
        // data.go.kr 게이트웨이가 일부 기본 User-Agent 를 WAF 차단함 — 명시적 UA 로 회피.
        return builder
            .requestFactory(factory)
            .defaultHeader("User-Agent", "Mozilla/5.0 (compatible; aixnative/1.0)")
            .build()
    }
}
