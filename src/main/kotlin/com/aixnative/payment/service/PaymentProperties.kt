package com.aixnative.payment.service

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * 토스페이먼츠 설정. clientKey 는 프론트 노출(공개), secretKey 는 서버 전용(절대 노출 금지).
 * 테스트키로 시작 → 사업자등록·PG 심사 후 라이브키로 교체. 모두 env/Secret Manager 로 주입.
 */
@ConfigurationProperties(prefix = "toss")
data class TossProperties(
    val clientKey: String = "",
    val secretKey: String = "",
    val apiUrl: String = "https://api.tosspayments.com",
) {
    /** secretKey 가 있어야 결제 승인(서버) 가능. */
    fun isConfigured(): Boolean = secretKey.isNotBlank()
}
