package com.aixnative.account.oauth

import com.aixnative.account.AuthProvider
import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * 소셜 로그인(구글/카카오/네이버) 설정. 제공자별 client-id/secret 는 전부 env/Secret Manager
 * (신규 발급, 절대 커밋 금지). 미설정 제공자는 graceful — 해당 버튼이 프론트에서 자동 숨김.
 */
@ConfigurationProperties(prefix = "oauth")
data class OAuthProperties(
    val google: Provider = Provider(),
    val kakao: Provider = Provider(),
    val naver: Provider = Provider(),
) {
    data class Provider(
        val clientId: String = "",
        val clientSecret: String = "",
    ) {
        /** 활성 여부. requireSecret=false(카카오)는 client-id 만으로 활성(secret 은 선택). */
        fun isConfigured(requireSecret: Boolean): Boolean =
            clientId.isNotBlank() && (!requireSecret || clientSecret.isNotBlank())
    }

    fun forProvider(p: AuthProvider): Provider? = when (p) {
        AuthProvider.GOOGLE -> google
        AuthProvider.KAKAO -> kakao
        AuthProvider.NAVER -> naver
        AuthProvider.LOCAL -> null
    }

    /** 카카오는 secret(보안키)이 선택 — client-id 만으로 동작. 구글/네이버는 secret 필수. */
    fun requiresSecret(p: AuthProvider): Boolean = p != AuthProvider.KAKAO

    fun isConfigured(p: AuthProvider): Boolean =
        forProvider(p)?.isConfigured(requiresSecret(p)) == true

    /** 설정된(버튼 노출) 소셜 제공자 목록 — 프론트가 어떤 버튼을 그릴지 결정. */
    fun configuredProviders(): List<AuthProvider> =
        listOf(AuthProvider.GOOGLE, AuthProvider.KAKAO, AuthProvider.NAVER).filter { isConfigured(it) }
}
