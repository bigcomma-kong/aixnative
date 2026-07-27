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
     * 이미지 생성(Gemini/Imagen) 전용 - base64 이미지 응답이 크고 생성 지연이 길어
     * read timeout 을 넉넉히(120s) 준다. 배경 작업이라 요청 스레드 파킹 우려 낮음.
     */
    @Bean
    fun imageGenRestClient(builder: RestClient.Builder): RestClient {
        val factory = SimpleClientHttpRequestFactory().apply {
            setConnectTimeout(Duration.ofSeconds(10))
            setReadTimeout(Duration.ofSeconds(120))
        }
        return builder.requestFactory(factory).build()
    }

    /**
     * Pollinations 무료 AI 이미지 생성 전용 - 지연 편차가 커(1~48s) 한 장이 오래 붙잡지 않도록
     * read 20s 로 제한(초과 시 [com.aixnative.ai.service.PollinationsImageClient] 가 재시도 후 다음 엔진으로 폴백).
     * 동기 재생성 엔드포인트가 Cloud Run 300s 요청 상한을 넘지 않도록 장면당 최악(재시도 포함)을 bound.
     */
    @Bean
    fun pollinationsRestClient(builder: RestClient.Builder): RestClient {
        val factory = SimpleClientHttpRequestFactory().apply {
            setConnectTimeout(Duration.ofSeconds(8))
            setReadTimeout(Duration.ofSeconds(20))
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
