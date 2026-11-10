package com.aixnative.ai.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientResponseException
import java.util.concurrent.atomic.AtomicInteger
import com.aixnative.ai.domain.AiProvider

/**
 * AI 응답이 출력 한도에서 잘렸을 때. 잘린 JSON 을 파서에 넘기면 "파싱 실패"로 둔갑해
 * 상위가 provider 장애로 오인하므로, 여기서 명시적으로 끊는다(사내 레거시 운영 최다 실패 유형).
 */
class AiOutputTruncatedException(message: String) : RuntimeException(message)

/**
 * Anthropic Claude provider (Messages API). Two auth modes, in priority order:
 *
 *  - **OAuth** (subscription token `sk-ant-oat...`): `Authorization: Bearer <token>`
 *    plus `anthropic-beta: oauth-2025-04-20`. Used when `claude.oauth.token`/`.tokens` is set.
 *  - **API key** (`sk-ant-api...`): `x-api-key: <key>`. Used otherwise.
 *
 * Both modes send `anthropic-version: 2023-06-01` and `{model, max_tokens, messages}`,
 * and gather response text from every `content[].text` block.
 *
 * 실패 대응은 2층이다:
 *  - **공간축** — OAuth 토큰이 2개 이상이면 호출마다 라운드로빈, 한 토큰이 죽으면 다음 토큰으로([callWithTokenFailover]).
 *  - **시간축** — 그래도 429/5xx 면 짧은 백오프 후 재시도([MAX_RETRIES]).
 */
@Component
class ClaudeClient(
    private val aiRestClient: RestClient,
    private val props: ClaudeProperties,
    private val objectMapper: ObjectMapper,
) : AiProvider {

    private val log = LoggerFactory.getLogger(javaClass)

    /** OAuth 토큰 라운드로빈 커서 - 호출마다 시작 토큰을 옮겨 한 토큰에 부하가 몰리지 않게 한다. */
    private val rrIndex = AtomicInteger(0)

    override val name: String = "Claude"

    override val priority: Int get() = props.priority

    override fun isConfigured(): Boolean = hasOauth() || hasApiKey()

    /**
     * 설정된 OAuth 토큰 목록 - 단일(`claude.oauth.token`) + CSV(`claude.oauth.tokens`) 병합, 공백·중복 제거.
     * 2개 이상이면 [callWithTokenFailover] 가 번갈아 사용한다.
     */
    private fun oauthTokens(): List<String> =
        (listOf(props.oauth.token) + props.oauth.tokens.split(','))
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()

    private fun hasOauth(): Boolean = oauthTokens().isNotEmpty()

    private fun hasApiKey(): Boolean {
        val key = props.api.key
        return key.isNotBlank() && key != "YOUR_API_KEY_HERE" && key != "YOUR_CLAUDE_API_KEY_HERE"
    }

    /** 사용자 과금 분석 경로 - 기본 모델([ClaudeProperties.Api.model], Opus). */
    override fun complete(prompt: String): String =
        call(prompt, props.api.model, props.api.maxTokens)

    /**
     * 배치(무인) 경로 - 마켓 브리핑처럼 사용자 크레딧이 걸리지 않는 백그라운드 작업용.
     * 저렴한 배치 모델([ClaudeProperties.Batch])로 호출해 매일 도는 작업의 운영비를 억제한다.
     * 라우터([AiServiceManager])를 타지 않으므로 과금 분석과 예산이 섞이지 않는다.
     */
    fun completeBatch(prompt: String): String {
        val model = props.batch.model.ifBlank { props.api.model }
        return call(prompt, model, props.batch.maxTokens)
    }

    private fun call(prompt: String, model: String, maxTokens: Int): String {
        check(isConfigured()) { "Claude 인증 정보가 설정되지 않았습니다." }

        val body = buildMap<String, Any> {
            put("model", model)
            put("max_tokens", maxTokens)
            put("messages", listOf(mapOf("role" to "user", "content" to prompt)))
            // 5세대 모델(haiku-5-5 등)은 thinking 이 기본 켜짐 - 출력 한도의 절반 이상을 추론에 써서
            // 본문이 max_tokens 에서 잘린다(실측: 8192 중 5326 소모). 분석 지시가 프롬프트에 구조화돼
            // 있어 추론 없이도 품질이 충분하므로 명시적으로 끈다.
            put("thinking", THINKING_DISABLED)
            // OAuth(구독) 토큰은 첫 system 블록이 이 식별 문구여야 인증을 통과한다.
            // 실제 분석 지시문은 user 프롬프트에 그대로 담겨 있어 결과 품질에는 영향이 없다.
            if (hasOauth()) put("system", OAUTH_SYSTEM)
        }

        // 일시적 서버 오류(5xx)·rate limit(429)는 짧은 백오프로 재시도한다(앤트로픽 API 500 간헐 발생).
        // 4xx(키·모델·요청 오류)는 재시도해도 동일하므로 즉시 전파. 성공 시 즉시 반환.
        var lastError: RuntimeException? = null
        for (attempt in 0..MAX_RETRIES) {
            try {
                return extractText(callWithTokenFailover(body, model), maxTokens)
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

    /**
     * OAuth 토큰이 여러 개면 라운드로빈으로 시도하고 실패(429·만료 등) 시 다음 토큰으로 넘어간다.
     * 토큰이 하나뿐이거나 API 키 모드면 단일 호출과 동일. 모든 토큰이 실패하면 마지막 예외를 전파해
     * 상위 재시도 루프가 429/5xx 판정을 그대로 할 수 있게 한다.
     */
    private fun callWithTokenFailover(body: Map<String, Any>, model: String): String {
        val tokens = oauthTokens()
        if (tokens.isEmpty()) return callApi(body, model, oauthToken = null)

        val start = Math.floorMod(rrIndex.getAndIncrement(), tokens.size)
        var last: RuntimeException? = null
        for (i in tokens.indices) {
            val idx = (start + i) % tokens.size
            try {
                return callApi(body, model, oauthToken = tokens[idx])
            } catch (e: RestClientResponseException) {
                last = e
                if (i < tokens.lastIndex) {
                    log.warn("[Claude] OAuth 토큰 #{} 실패({}), 다음 토큰 시도 ({}/{})",
                        idx, e.statusCode, i + 1, tokens.size)
                }
            }
        }
        throw last ?: RuntimeException("모든 Claude OAuth 토큰 실패")
    }

    /** 단일 HTTP 호출(헤더·인증 모드 구성 + 전송). 5xx/429 시 [RestClientResponseException] 전파(호출부 처리). */
    private fun callApi(body: Map<String, Any>, model: String, oauthToken: String?): String {
        var spec = aiRestClient.post()
            .uri(props.api.url)
            .contentType(MediaType.APPLICATION_JSON)
            .header("anthropic-version", ANTHROPIC_VERSION)

        spec = if (oauthToken != null) {
            log.info("[Claude] API 호출 (OAuth) - model: {}", model)
            spec.header("Authorization", "Bearer $oauthToken")
                .header("anthropic-beta", OAUTH_BETA)
        } else {
            log.info("[Claude] API 호출 (API key) - model: {}", model)
            spec.header("x-api-key", props.api.key)
        }

        return spec.body(body).retrieve().body(String::class.java)
            ?: throw RuntimeException("Claude 응답이 비어 있습니다.")
    }

    /**
     * Concatenate every text block from the Anthropic `content` array.
     *
     * 출력 한도로 잘린 응답은 텍스트를 꺼내기 전에 끊는다 - 잘린 JSON 을 그대로 돌려주면
     * 호출부에서 "JSON 파싱 실패"로 나타나 진짜 원인(분량 초과)이 가려진다.
     */
    private fun extractText(responseBody: String, maxTokens: Int): String {
        val root: JsonNode = objectMapper.readTree(responseBody)

        if (root.path("stop_reason").asText() == "max_tokens") {
            log.error("[Claude] 응답이 출력 한도({} tokens)에서 잘림 - stop_reason=max_tokens", maxTokens)
            throw AiOutputTruncatedException(
                "AI 응답이 최대 길이에 도달해 중간에서 잘렸습니다. 입력 분량을 줄여 다시 시도해 주세요.",
            )
        }

        val content = root.path("content")
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
        private val THINKING_DISABLED = mapOf("type" to "disabled")
        private const val OAUTH_SYSTEM = "You are Claude Code, Anthropic's official CLI for Claude."
        // 일시 오류 재시도(5xx/429). 총 시도 = 1 + MAX_RETRIES. 백오프 = BACKOFF * (시도회차).
        private const val MAX_RETRIES = 2
        private const val RETRY_BACKOFF_MS = 600L
    }
}
