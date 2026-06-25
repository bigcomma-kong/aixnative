package com.underwriteai.common.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.web.client.RestClient
import java.time.Duration

/**
 * Shared [RestClient] for outbound AI provider calls. Generous read timeout for
 * heavy LLM responses; the [com.underwriteai.ai.AiServiceManager] enforces an
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
}
