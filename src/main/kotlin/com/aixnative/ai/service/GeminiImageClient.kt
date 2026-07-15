package com.aixnative.ai.service

import com.aixnative.social.service.ImageEngine
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientResponseException

/**
 * Google Gemini 이미지 생성 엔진([ImageEngine]) - generativelanguage generateContent.
 * `MistralClient` 구조 미러링(RestClient + graceful). 배경 스토리 이미지 생성 전용,
 * 텍스트 라우터(AiServiceManager)에 안 잡히도록 AiProvider 미구현.
 * 키 미설정/실패 시 null → 호출부([com.aixnative.social.service.StoryImageComposer])가 타이포 폴백.
 * (키는 신규 발급, MASTERN 값 복사 금지.)
 */
@Component
class GeminiImageClient(
    @Qualifier("imageGenRestClient") private val rest: RestClient,
    private val props: GeminiProperties,
    private val objectMapper: ObjectMapper,
) : ImageEngine {

    private val log = LoggerFactory.getLogger(javaClass)

    override val name: String = "Gemini"

    override fun isConfigured(): Boolean = props.api.key.isNotBlank()

    override fun generate(prompt: String, aspectRatio: String): String? {
        if (!isConfigured()) return null
        val fullPrompt = "$prompt. Vertical $aspectRatio composition, no text, no watermark, no real person's face."
        val body = mapOf(
            "contents" to listOf(
                mapOf("parts" to listOf(mapOf("text" to fullPrompt))),
            ),
            "generationConfig" to mapOf("responseModalities" to listOf("IMAGE")),
        )
        return try {
            val uri = "${props.api.url}/models/${props.api.model}:generateContent?key=${props.api.key}"
            val response = rest.post()
                .uri(uri)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(String::class.java)
                ?: return null
            extractImage(response)
        } catch (e: RestClientResponseException) {
            log.warn("[Gemini] 이미지 생성 HTTP {} - {}", e.statusCode, e.responseBodyAsString.take(300))
            null
        } catch (e: Exception) {
            log.warn("[Gemini] 이미지 생성 실패: {}", e.message)
            null
        }
    }

    /** candidates[0].content.parts[].inlineData.data (base64). */
    private fun extractImage(responseBody: String): String? {
        val parts: JsonNode = objectMapper.readTree(responseBody)
            .path("candidates").firstOrNull()
            ?.path("content")?.path("parts")
            ?: return null
        if (!parts.isArray) return null
        for (part in parts) {
            val data = part.path("inlineData").path("data").asText("")
            if (data.isNotBlank()) return data
        }
        log.warn("[Gemini] 응답에 이미지 파트 없음")
        return null
    }
}
