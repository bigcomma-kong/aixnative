package com.aixnative.ai.service

import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientResponseException
import com.aixnative.ai.domain.AiProvider

/**
 * Anthropic Claude provider (Messages API). Two auth modes, in priority order:
 *
 *  - **OAuth** (subscription token `sk-ant-oat...`): `Authorization: Bearer <token>`
 *    plus `anthropic-beta: oauth-2025-04-20`. Used when `claude.oauth.token` is set.
 *  - **API key** (`sk-ant-api...`): `x-api-key: <key>`. Used otherwise.
 *
 * Both modes send `anthropic-version: 2023-06-01` and `{model, max_tokens, messages}`,
 * and gather response text from every `content[].text` block.
 */
@Component
class ClaudeClient(
    private val aiRestClient: RestClient,
    private val props: ClaudeProperties,
    private val objectMapper: ObjectMapper,
) : AiProvider {

    private val log = LoggerFactory.getLogger(javaClass)

    override val name: String = "Claude"

    override val priority: Int get() = props.priority

    override fun isConfigured(): Boolean = hasOauth() || hasApiKey()

    private fun hasOauth(): Boolean = props.oauth.token.isNotBlank()

    private fun hasApiKey(): Boolean {
        val key = props.api.key
        return key.isNotBlank() && key != "YOUR_API_KEY_HERE" && key != "YOUR_CLAUDE_API_KEY_HERE"
    }

    override fun complete(prompt: String): String {
        check(isConfigured()) { "Claude 인증 정보가 설정되지 않았습니다." }

        val body = buildMap<String, Any> {
            put("model", props.api.model)
            put("max_tokens", props.api.maxTokens)
            put("messages", listOf(mapOf("role" to "user", "content" to prompt)))
            // OAuth(구독) 토큰은 첫 system 블록이 이 식별 문구여야 인증을 통과한다.
            // 실제 분석 지시문은 user 프롬프트에 그대로 담겨 있어 결과 품질에는 영향이 없다.
            if (hasOauth()) put("system", OAUTH_SYSTEM)
        }

        // 일시적 서버 오류(5xx)·rate limit(429)는 짧은 백오프로 재시도한다(앤트로픽 API 500 간헐 발생).
        // 4xx(키·모델·요청 오류)는 재시도해도 동일하므로 즉시 전파. 성공 시 즉시 반환.
        var lastError: RuntimeException? = null
        for (attempt in 0..MAX_RETRIES) {
            try {
                return extractText(callApi(body))
            } catch (e: RestClientResponseException) {
                val status = e.statusCode.value()
                val snippet = e.responseBodyAsString.take(400)
                log.error("[Claude] HTTP {} - {} (시도 {}/{})", e.statusCode, snippet, attempt + 1, MAX_RETRIES + 1)
                lastError = RuntimeException("Claude API $status: $snippet", e)
                val retryable = status == 429 || status >= 500
                if (!retryable || attempt == MAX_RETRIES) throw lastError
                Thread.sleep(RETRY_BACKOFF_MS * (attempt + 1))
            }
        }
        throw lastError ?: RuntimeException("Claude 호출 실패")
    }

    /** 단일 HTTP 호출(헤더·인증 모드 구성 + 전송). 5xx/429 시 [RestClientResponseException] 전파(호출부 재시도). */
    private fun callApi(body: Map<String, Any>): String {
        var spec = aiRestClient.post()
            .uri(props.api.url)
            .contentType(MediaType.APPLICATION_JSON)
            .header("anthropic-version", ANTHROPIC_VERSION)

        spec = if (hasOauth()) {
            log.info("[Claude] API 호출 (OAuth) - model: {}", props.api.model)
            spec.header("Authorization", "Bearer ${props.oauth.token}")
                .header("anthropic-beta", OAUTH_BETA)
        } else {
            log.info("[Claude] API 호출 (API key) - model: {}", props.api.model)
            spec.header("x-api-key", props.api.key)
        }

        return spec.body(body).retrieve().body(String::class.java)
            ?: throw RuntimeException("Claude 응답이 비어 있습니다.")
    }

    /** Concatenate every text block from the Anthropic `content` array. */
    private fun extractText(responseBody: String): String {
        val content = objectMapper.readTree(responseBody).path("content")
        if (content.isArray) {
            val text = content.asSequence()
                .filter { it.path("type").asText() == "text" }
                .joinToString("\n") { it.path("text").asText() }
            if (text.isNotBlank()) return text
        }
        throw RuntimeException("Claude 응답에서 텍스트를 추출할 수 없습니다.")
    }

    companion object {
        private const val ANTHROPIC_VERSION = "2023-06-01"
        private const val OAUTH_BETA = "oauth-2025-04-20"
        private const val OAUTH_SYSTEM = "You are Claude Code, Anthropic's official CLI for Claude."
        // 일시 오류 재시도(5xx/429). 총 시도 = 1 + MAX_RETRIES. 백오프 = BACKOFF * (시도회차).
        private const val MAX_RETRIES = 2
        private const val RETRY_BACKOFF_MS = 600L
    }
}
