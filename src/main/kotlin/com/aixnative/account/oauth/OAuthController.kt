package com.aixnative.account.oauth

import com.aixnative.common.web.ApiResponse
import com.aixnative.common.web.BadRequestException
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * 소셜 로그인(구글/카카오/네이버) 진입·콜백. 전부 auth 공개 경로.
 * authorize/callback 은 브라우저 리다이렉트(302). 콜백 성공 시 SPA 로 `#token=` 을 실어 보낸다
 * (해시 프래그먼트 — 서버 로그/Referer 에 토큰이 남지 않음).
 *
 * redirect_uri·복귀 URL 은 **사용자가 접속한 호스트**(X-Forwarded-* 헤더, Cloud Run/도메인 매핑)를
 * 기준으로 만든다 → aixnative.com·www·run.app 어디로 와도 같은 호스트로 일관 동작.
 */
@RestController
@RequestMapping("/api/auth/oauth")
class OAuthController(
    private val oauth: OAuthService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** 설정된 소셜 제공자 목록(소문자). 프론트가 어떤 버튼을 그릴지 결정. */
    @GetMapping("/providers")
    fun providers(): ApiResponse<List<String>> = ApiResponse.ok(oauth.configuredProviderIds())

    /** 제공자 인증 페이지로 리다이렉트(302). */
    @GetMapping("/{provider}/authorize")
    fun authorize(@PathVariable provider: String, req: HttpServletRequest): ResponseEntity<Void> {
        val p = oauth.parseProvider(provider) ?: throw BadRequestException("지원하지 않는 로그인입니다.")
        return redirect(oauth.authorizeUrl(p, baseOf(req)))
    }

    /** 제공자 콜백 — code→토큰→유저→우리 JWT. 성공: SPA `/#token=`, 실패: `/#oauth_error=`. */
    @GetMapping("/{provider}/callback")
    fun callback(
        @PathVariable provider: String,
        @RequestParam(required = false) code: String?,
        @RequestParam(required = false) state: String?,
        @RequestParam(required = false) error: String?,
        req: HttpServletRequest,
    ): ResponseEntity<Void> {
        val base = baseOf(req)
        val p = oauth.parseProvider(provider) ?: return redirect("$base/#oauth_error=" + enc("지원하지 않는 로그인입니다."))
        if (error != null || code.isNullOrBlank()) {
            return redirect("$base/#oauth_error=" + enc("로그인이 취소되었습니다."))
        }
        return try {
            val token = oauth.handleCallback(p, code, state, base)
            redirect("$base/#token=" + enc(token))
        } catch (e: Exception) {
            log.warn("[oauth] {} 콜백 처리 실패: {}", p, e.message)
            val msg = (e as? BadRequestException)?.message ?: "소셜 로그인에 실패했습니다."
            redirect("$base/#oauth_error=" + enc(msg))
        }
    }

    /** 사용자가 접속한 스킴+호스트. Cloud Run/프록시는 X-Forwarded-* 로 원 호스트를 전달. */
    private fun baseOf(req: HttpServletRequest): String {
        val proto = req.getHeader("X-Forwarded-Proto")?.substringBefore(',')?.trim()?.ifBlank { null } ?: req.scheme
        val host = req.getHeader("X-Forwarded-Host")?.substringBefore(',')?.trim()?.ifBlank { null }
            ?: req.getHeader("Host")?.ifBlank { null }
            ?: req.serverName
        return "$proto://$host"
    }

    private fun redirect(url: String): ResponseEntity<Void> =
        ResponseEntity.status(HttpStatus.FOUND).location(URI.create(url)).build()

    private fun enc(s: String): String = URLEncoder.encode(s, StandardCharsets.UTF_8)
}
