package com.aixnative.payment

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientResponseException
import java.util.Base64

/** 토스 결제 승인 결과. ok=false 면 message 에 사유. */
data class TossConfirmResult(
    val ok: Boolean,
    val method: String? = null,
    val approvedAt: String? = null,
    val message: String? = null,
)

/**
 * 토스페이먼츠 서버 API 클라이언트. 결제 승인(confirm)은 반드시 서버에서 secretKey 로 호출한다
 * (클라이언트 결과를 신뢰하지 않음). Basic 인증: base64("{secretKey}:") — 콜론 뒤 비밀번호 없음.
 */
@Component
class TossClient(
    builder: RestClient.Builder,
    private val props: TossProperties,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val rest: RestClient = builder.build()

    private fun authHeader(): String =
        "Basic " + Base64.getEncoder().encodeToString("${props.secretKey}:".toByteArray())

    /** 결제 승인. 성공 시 ok=true + method/approvedAt. 실패 시 ok=false + 사유. */
    fun confirm(paymentKey: String, orderId: String, amount: Int): TossConfirmResult {
        return try {
            val res: JsonNode = rest.post()
                .uri("${props.apiUrl}/v1/payments/confirm")
                .header(HttpHeaders.AUTHORIZATION, authHeader())
                .contentType(MediaType.APPLICATION_JSON)
                .body(mapOf("paymentKey" to paymentKey, "orderId" to orderId, "amount" to amount))
                .retrieve()
                .body(JsonNode::class.java)
                ?: return TossConfirmResult(ok = false, message = "빈 응답")
            TossConfirmResult(
                ok = true,
                method = res.path("method").asText(null),
                approvedAt = res.path("approvedAt").asText(null),
            )
        } catch (e: RestClientResponseException) {
            // 토스 에러 본문: {"code","message"}
            val msg = runCatching { objectMapper.readTree(e.responseBodyAsString).path("message").asText("") }
                .getOrDefault("")
            log.warn("[toss] 승인 실패 status={} body={}", e.statusCode, e.responseBodyAsString)
            TossConfirmResult(ok = false, message = msg.ifBlank { "결제 승인 실패(${e.statusCode})" })
        }
    }
}
