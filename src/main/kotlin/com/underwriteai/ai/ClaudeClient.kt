package com.underwriteai.ai

import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient

/**
 * Anthropic Claude provider. Ported from MASTERN ClaudeService — same HTTP shape:
 * `x-api-key` + `anthropic-version: 2023-06-01` headers, `{model, max_tokens,
 * messages}` body, response text gathered from `content[].text`.
 *
 * Differences vs the legacy class (intentional, Phase 1 scope):
 *  - RestClient instead of RestTemplate.
 *  - API-key mode only (OAuth round-robin omitted).
 *  - Returns raw text; prompt building + JSON parsing land in Phase 2.
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

    override fun isConfigured(): Boolean {
        val key = props.api.key
        return key.isNotBlank() && key != "YOUR_API_KEY_HERE" && key != "YOUR_CLAUDE_API_KEY_HERE"
    }

    override fun complete(prompt: String): String {
        check(isConfigured()) { "Claude API 키가 설정되지 않았습니다." }
        val body = mapOf(
            "model" to props.api.model,
            "max_tokens" to props.api.maxTokens,
            "messages" to listOf(mapOf("role" to "user", "content" to prompt)),
        )
        log.info("[Claude] API 호출 - model: {}", props.api.model)
        val response = aiRestClient.post()
            .uri(props.api.url)
            .header("x-api-key", props.api.key)
            .header("anthropic-version", ANTHROPIC_VERSION)
            .contentType(MediaType.APPLICATION_JSON)
            .body(body)
            .retrieve()
            .body(String::class.java)
            ?: throw RuntimeException("Claude 응답이 비어 있습니다.")
        return extractText(response)
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
    }
}
