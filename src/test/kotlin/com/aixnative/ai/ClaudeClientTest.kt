package com.aixnative.ai

import com.aixnative.ai.service.AiOutputTruncatedException
import com.aixnative.ai.service.ClaudeClient
import com.aixnative.ai.service.ClaudeProperties
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.client.ExpectedCount
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.header
import org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath
import org.springframework.test.web.client.response.MockRestResponseCreators.withStatus
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestClient
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * ClaudeClient 단위 테스트 — Spring 컨텍스트 없이 [MockRestServiceServer] 로 Anthropic 응답만 흉내낸다.
 * 검증 대상은 사내 레거시에서 이식한 두 방어선(출력 잘림 감지·OAuth 토큰 failover)과 배치 모델 분리.
 */
class ClaudeClientTest {

    private val url = "https://api.anthropic.com/v1/messages"

    private fun ok(text: String, stopReason: String = "end_turn") =
        """{"stop_reason":"$stopReason","content":[{"type":"text","text":"$text"}]}"""

    /** 클라이언트 + 그 클라이언트의 RestClient 에 물린 목 서버를 함께 만든다. */
    private fun fixture(props: ClaudeProperties): Pair<ClaudeClient, MockRestServiceServer> {
        val builder = RestClient.builder()
        val server = MockRestServiceServer.bindTo(builder).build()
        return ClaudeClient(builder.build(), props, ObjectMapper()) to server
    }

    private fun props(
        apiKey: String = "",
        token: String = "",
        tokens: String = "",
        model: String = "claude-opus-4-8",
        batchModel: String = "claude-haiku-4-5-20251001",
    ) = ClaudeProperties(
        api = ClaudeProperties.Api(key = apiKey, url = url, model = model, maxTokens = 8192),
        oauth = ClaudeProperties.Oauth(token = token, tokens = tokens),
        batch = ClaudeProperties.Batch(model = batchModel, maxTokens = 4096),
    )

    @Test
    fun `returns the concatenated text of every text block`() {
        val (client, server) = fixture(props(apiKey = "sk-ant-api-x"))
        server.expect(org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo(url))
            .andExpect(header("x-api-key", "sk-ant-api-x"))
            .andRespond(withSuccess(ok("브리핑 본문"), MediaType.APPLICATION_JSON))

        assertEquals("브리핑 본문", client.complete("prompt"))
        server.verify()
    }

    @Test
    fun `throws truncation error instead of returning a cut response`() {
        val (client, server) = fixture(props(apiKey = "sk-ant-api-x"))
        server.expect(org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo(url))
            .andRespond(withSuccess(ok("""{\"headline\":\"잘린""", stopReason = "max_tokens"), MediaType.APPLICATION_JSON))

        // 잘린 JSON 을 그대로 돌려주면 호출부에서 "파싱 실패"로 둔갑해 원인이 가려진다.
        assertFailsWith<AiOutputTruncatedException> { client.complete("prompt") }
        server.verify()
    }

    @Test
    fun `batch calls use the cheaper batch model, not the analysis model`() {
        val (client, server) = fixture(props(apiKey = "sk-ant-api-x"))
        server.expect(org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo(url))
            .andExpect(jsonPath("$.model").value("claude-haiku-4-5-20251001"))
            .andExpect(jsonPath("$.max_tokens").value(4096))
            .andRespond(withSuccess(ok("배치 결과"), MediaType.APPLICATION_JSON))

        assertEquals("배치 결과", client.completeBatch("prompt"))
        server.verify()
    }

    @Test
    fun `falls over to the next oauth token when one is rate limited`() {
        val (client, server) = fixture(props(token = "oat-first", tokens = "oat-second"))
        server.expect(ExpectedCount.once(), org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo(url))
            .andExpect(header("Authorization", "Bearer oat-first"))
            .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).body("""{"type":"rate_limited"}"""))
        server.expect(ExpectedCount.once(), org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo(url))
            .andExpect(header("Authorization", "Bearer oat-second"))
            .andRespond(withSuccess(ok("두번째 토큰 성공"), MediaType.APPLICATION_JSON))

        // 첫 토큰이 429 여도 같은 호출 안에서 다음 토큰으로 넘어가야 한다(백오프 재시도 이전 단계).
        assertEquals("두번째 토큰 성공", client.completeBatch("prompt"))
        server.verify()
    }

    @Test
    fun `merges single and csv oauth tokens, dropping blanks and duplicates`() {
        val (client, server) = fixture(props(token = "oat-a", tokens = " , oat-a , oat-b "))
        // 토큰이 a·b 두 개로 정리되면 모두 실패했을 때 정확히 2회만 호출된다(중복 a 로 3회가 아니라).
        repeat(2) {
            server.expect(ExpectedCount.once(), org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo(url))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED).body("""{"type":"authentication_error"}"""))
        }

        assertFailsWith<RuntimeException> { client.completeBatch("prompt") }
        server.verify()
    }
}
