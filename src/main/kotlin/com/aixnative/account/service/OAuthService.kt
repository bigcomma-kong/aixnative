package com.aixnative.account.service

import com.aixnative.account.domain.AuthProvider
import com.aixnative.account.domain.Tenant
import com.aixnative.account.repository.TenantRepository
import com.aixnative.account.domain.User
import com.aixnative.account.repository.UserRepository
import com.aixnative.account.domain.UserRole
import com.aixnative.account.domain.UserStatus
import com.aixnative.billing.service.CreditService
import com.aixnative.common.security.AuthPrincipal
import com.aixnative.common.security.JwtService
import com.aixnative.common.web.BadRequestException
import com.fasterxml.jackson.databind.JsonNode
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.util.LinkedMultiValueMap
import org.springframework.web.client.RestClient
import org.springframework.web.util.UriComponentsBuilder

/** 제공자별 OAuth2 엔드포인트 + 권한범위. */
private data class Endpoints(
    val authorize: String,
    val token: String,
    val userInfo: String,
    val scope: String?,
)

/** 제공자 프로필에서 뽑은 표준 형태. email 은 제공자 미동의 시 null 가능(카카오). */
private data class SocialProfile(
    val providerId: String,
    val email: String?,
    val emailVerified: Boolean,
)

/**
 * 소셜 로그인 핵심: authorize URL 생성 + 콜백 처리(code→토큰→프로필→유저 find-or-create→우리 JWT 발급).
 * 보안: 승인 코드 교환·프로필 조회는 전부 **서버에서** 수행(클라 신뢰 안 함). 소셜 이메일은 검증된 것으로 간주.
 */
@Service
class OAuthService(
    private val users: UserRepository,
    private val tenants: TenantRepository,
    private val creditService: CreditService,
    private val jwtService: JwtService,
    private val props: OAuthProperties,
    private val stateCodec: OAuthStateCodec,
    builder: RestClient.Builder,
    @Value("\${app.admin-email:}") private val adminEmail: String,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val rest: RestClient = builder.build()

    private fun endpoints(p: AuthProvider): Endpoints = when (p) {
        AuthProvider.GOOGLE -> Endpoints(
            authorize = "https://accounts.google.com/o/oauth2/v2/auth",
            token = "https://oauth2.googleapis.com/token",
            userInfo = "https://www.googleapis.com/oauth2/v3/userinfo",
            scope = "openid email profile",
        )
        AuthProvider.KAKAO -> Endpoints(
            authorize = "https://kauth.kakao.com/oauth/authorize",
            token = "https://kauth.kakao.com/oauth/token",
            userInfo = "https://kapi.kakao.com/v2/user/me",
            scope = "account_email", // 이메일 동의항목(앱에서 활성화 필요). 미동의 시 email=null 폴백.
        )
        AuthProvider.NAVER -> Endpoints(
            authorize = "https://nid.naver.com/oauth2.0/authorize",
            token = "https://nid.naver.com/oauth2.0/token",
            userInfo = "https://openapi.naver.com/v1/nid/me",
            scope = null,
        )
        AuthProvider.LOCAL -> throw IllegalArgumentException("LOCAL 은 소셜 제공자가 아닙니다.")
    }

    /** redirect_uri 는 사용자가 접속한 호스트(base) 기준으로 생성 — authorize·token 교환이 동일 값이어야 한다. */
    private fun redirectUri(base: String, p: AuthProvider): String =
        "${base.trimEnd('/')}/api/auth/oauth/${p.name.lowercase()}/callback"

    fun parseProvider(raw: String): AuthProvider? =
        runCatching { AuthProvider.valueOf(raw.uppercase()) }.getOrNull()?.takeIf { it != AuthProvider.LOCAL }

    /** 설정된 제공자 목록(소문자) — 프론트 버튼 노출용. */
    fun configuredProviderIds(): List<String> = props.configuredProviders().map { it.name.lowercase() }

    /** 제공자 인증 페이지로 보낼 authorize URL(state 포함). 미설정 제공자면 예외. base=사용자 접속 호스트. */
    fun authorizeUrl(p: AuthProvider, base: String): String {
        require(props.isConfigured(p)) { "$p 소셜 로그인이 설정되지 않았습니다." }
        val cfg = requireNotNull(props.forProvider(p))
        val e = endpoints(p)
        val b = UriComponentsBuilder.fromUriString(e.authorize)
            .queryParam("response_type", "code")
            .queryParam("client_id", cfg.clientId)
            .queryParam("redirect_uri", redirectUri(base, p))
            .queryParam("state", stateCodec.issue(p.name.lowercase()))
        e.scope?.let { b.queryParam("scope", it) }
        // .encode() 필수 — scope("openid email profile")의 공백·redirect_uri 의 특수문자를 인코딩
        // (build(true)는 인코딩 생략 → raw 공백으로 URI.create 가 터짐).
        return b.encode().build().toUriString()
    }

    /** 콜백 처리 → 우리 JWT 반환. 실패 시 BadRequestException(컨트롤러가 에러 리다이렉트로 변환). base=사용자 접속 호스트. */
    @Transactional
    fun handleCallback(p: AuthProvider, code: String, state: String?, base: String): String {
        if (stateCodec.verify(state) != p.name.lowercase()) {
            throw BadRequestException("잘못된 인증 요청입니다. 다시 시도해 주세요.")
        }
        require(props.isConfigured(p)) { throw BadRequestException("$p 로그인이 설정되지 않았습니다.") }
        val accessToken = exchangeCode(p, code, state, base)
        val profile = fetchProfile(p, accessToken)
        val user = findOrCreate(p, profile)
        val userId = requireNotNull(user.id)
        return jwtService.issue(AuthPrincipal(userId, user.tenantId, user.email, user.role.name))
    }

    // ── code → access_token ─────────────────────────────────────────────
    private fun exchangeCode(p: AuthProvider, code: String, state: String?, base: String): String {
        val cfg = requireNotNull(props.forProvider(p))
        val e = endpoints(p)
        val form = LinkedMultiValueMap<String, String>().apply {
            add("grant_type", "authorization_code")
            add("client_id", cfg.clientId)
            add("redirect_uri", redirectUri(base, p))
            add("code", code)
            if (cfg.clientSecret.isNotBlank()) add("client_secret", cfg.clientSecret)
            // 네이버 토큰요청은 authorize 때와 동일한 state 를 그대로 echo 해야 한다.
            if (p == AuthProvider.NAVER && !state.isNullOrBlank()) add("state", state)
        }
        val res = rest.post().uri(e.token)
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
            .body(form)
            .retrieve()
            .body(JsonNode::class.java)
        val token = res?.path("access_token")?.asText(null)
        if (token.isNullOrBlank()) {
            log.warn("[oauth] {} 토큰 교환 실패: {}", p, res)
            throw BadRequestException("소셜 인증에 실패했습니다.")
        }
        return token
    }

    // ── access_token → 표준 프로필 ──────────────────────────────────────
    private fun fetchProfile(p: AuthProvider, accessToken: String): SocialProfile {
        val e = endpoints(p)
        val json = rest.get().uri(e.userInfo)
            .header(HttpHeaders.AUTHORIZATION, "Bearer $accessToken")
            .retrieve()
            .body(JsonNode::class.java)
            ?: throw BadRequestException("소셜 프로필 조회에 실패했습니다.")
        return when (p) {
            AuthProvider.GOOGLE -> SocialProfile(
                providerId = json.path("sub").asText(),
                email = json.path("email").asText(null)?.takeIf { it.isNotBlank() },
                emailVerified = json.path("email_verified").asBoolean(false),
            )
            AuthProvider.KAKAO -> {
                val acct = json.path("kakao_account")
                SocialProfile(
                    providerId = json.path("id").asText(),
                    email = acct.path("email").asText(null)?.takeIf { it.isNotBlank() },
                    emailVerified = acct.path("is_email_verified").asBoolean(false),
                )
            }
            AuthProvider.NAVER -> {
                val r = json.path("response")
                SocialProfile(
                    providerId = r.path("id").asText(),
                    email = r.path("email").asText(null)?.takeIf { it.isNotBlank() },
                    emailVerified = true, // 네이버는 인증된 이메일 제공
                )
            }
            AuthProvider.LOCAL -> throw IllegalStateException()
        }.also {
            if (it.providerId.isBlank()) throw BadRequestException("소셜 계정 식별에 실패했습니다.")
        }
    }

    // ── find-or-create ──────────────────────────────────────────────────
    private fun findOrCreate(p: AuthProvider, profile: SocialProfile): User {
        // 1) 같은 소셜 계정으로 재로그인.
        users.findByAuthProviderAndProviderId(p, profile.providerId)?.let { return promoteIfAdmin(it) }

        // 2) 이메일이 있으면 기존 계정에 연결(제공자가 검증한 이메일이므로 안전).
        val email = profile.email
        if (email != null) {
            users.findByEmail(email)?.let { existing ->
                existing.authProvider = p
                existing.providerId = profile.providerId
                existing.emailVerified = true
                return promoteIfAdmin(existing)
            }
        }

        // 3) 신규 — 테넌트+유저 생성(소셜 = 이메일 검증됨 → 무료 크레딧 즉시 지급).
        val effectiveEmail = email ?: "${p.name.lowercase()}_${profile.providerId}@social.aixnative"
        val isAdmin = adminEmail.isNotBlank() && adminEmail.equals(effectiveEmail, ignoreCase = true)
        val tenant = tenants.save(Tenant(name = effectiveEmail))
        val tenantId = requireNotNull(tenant.id)
        val user = users.save(
            User(
                tenantId = tenantId,
                email = effectiveEmail,
                passwordHash = null,
                status = UserStatus.ACTIVE,
                authProvider = p,
                providerId = profile.providerId,
                emailVerified = true,
                role = if (isAdmin) UserRole.ADMIN else UserRole.USER,
            ),
        )
        creditService.grantSignupCredits(tenantId, requireNotNull(user.id))
        log.info("[oauth] 신규 소셜 가입 provider={} userId={} email={}", p, user.id, effectiveEmail)
        return user
    }

    /** 관리자 이메일이면 ADMIN 승격(멱등). */
    private fun promoteIfAdmin(user: User): User {
        if (adminEmail.isNotBlank() && adminEmail.equals(user.email, ignoreCase = true) && user.role != UserRole.ADMIN) {
            user.role = UserRole.ADMIN
        }
        return user
    }
}
