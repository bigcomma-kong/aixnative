package com.aixnative.ai.service

import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientResponseException
import com.aixnative.ai.domain.AiProvider

/**
 * Mistral 제공자 (Chat Completions API, OpenAI 호환 스키마).
 * `Authorization: Bearer <key>` + `{model, messages, max_tokens}` → `choices[0].message.content`.
 *
 * 무료 티어용. 스케줄 배치(시장 데이터 수집 보강)에서 비용 없이 쓰기 위해 도입했고,
 * 과금 분석(Claude)과 격리된다. 키 미설정 시 [isConfigured] 가 false → 호출부에서 건너뜀.
 */
@Component
class MistralClient(
    private val aiRestClient: RestClient,
    private val props: MistralProperties,
    private val objectMapper: ObjectMapper,
) : AiProvider {

    private val log = LoggerFactory.getLogger(javaClass)

    override val name: String = "Mistral"

    override val priority: Int get() = props.priority

    override fun isConfigured(): Boolean = props.api.key.isNotBlank()

    override fun complete(prompt: String): String {
        check(isConfigured()) { "Mistral API 키가 설정되지 않았습니다." }

        val body = mapOf(
            "model" to props.api.model,
            "max_tokens" to props.api.maxTokens,
            "messages" to listOf(mapOf("role" to "user", "content" to prompt)),
        )

        val response = try {
            aiRestClient.post()
                .uri(props.api.url)
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer ${props.api.key}")
                .body(body)
                .retrieve()
                .body(String::class.java)
                ?: throw RuntimeException("Mistral 응답이 비어 있습니다.")
        } catch (e: RestClientResponseException) {
            val snippet = e.responseBodyAsString.take(400)
            log.error("[Mistral] HTTP {} - {}", e.statusCode, snippet)
            throw RuntimeException("Mistral API ${e.statusCode.value()}: $snippet", e)
        }
        return extractText(response)
    }

    /** OpenAI 호환: choices[0].message.content. */
    private fun extractText(responseBody: String): String {
        val content = objectMapper.readTree(responseBody)
            .path("choices").firstOrNull()
            ?.path("message")?.path("content")?.asText()
        if (!content.isNullOrBlank()) return content
        throw RuntimeException("Mistral 응답에서 텍스트를 추출할 수 없습니다.")
    }
}
