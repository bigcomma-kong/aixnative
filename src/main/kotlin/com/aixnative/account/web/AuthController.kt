package com.aixnative.account.web

import com.aixnative.billing.service.CreditService
import com.aixnative.common.tenant.TenantContext
import com.aixnative.common.web.ApiResponse
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import com.aixnative.account.repository.UserRepository
import com.aixnative.account.service.AuthService
import com.aixnative.account.service.EmailVerificationService
import com.aixnative.account.service.PasswordResetService
import com.aixnative.account.service.ResetOutcome
import com.aixnative.account.service.VerifyOutcome

@RestController
@RequestMapping("/api/auth")
open class AuthController(
    private val authService: AuthService,
    private val creditService: CreditService,
    private val users: UserRepository,
    private val emailVerification: EmailVerificationService,
    private val passwordReset: PasswordResetService,
) {

    @PostMapping("/signup")
    fun signup(@Valid @RequestBody req: SignupRequest): ResponseEntity<ApiResponse<AuthResponse>> =
        ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(authService.signup(req)))

    @PostMapping("/login")
    fun login(@Valid @RequestBody req: LoginRequest): ApiResponse<AuthResponse> =
        ApiResponse.ok(authService.login(req))

    /** Current caller + live credit balance. Requires a valid Bearer token. */
    @GetMapping("/me")
    fun me(): ApiResponse<MeResponse> {
        val current = TenantContext.require()
        val balance = creditService.balance(current.tenantId, current.userId)
        val emailVerified = users.findById(current.userId).map { it.emailVerified }.orElse(false)
        return ApiResponse.ok(
            MeResponse(
                userId = current.userId,
                tenantId = current.tenantId,
                email = current.email,
                role = current.role,
                creditBalance = balance,
                emailVerified = emailVerified,
            ),
        )
    }

    /** Re-send the verification email for the (authenticated, unverified) caller. */
    @PostMapping("/resend-verification")
    fun resendVerification(): ApiResponse<Map<String, Boolean>> {
        val current = TenantContext.require()
        authService.resendVerification(current.userId)
        return ApiResponse.ok(mapOf("sent" to true))
    }

    /**
     * Request a password-reset link. Always returns the same success response —
     * never reveals whether the email exists (anti-enumeration). Public + throttled.
     */
    @PostMapping("/forgot-password")
    fun forgotPassword(@Valid @RequestBody req: ForgotPasswordRequest): ApiResponse<Map<String, Boolean>> {
        passwordReset.requestReset(req.email)
        return ApiResponse.ok(mapOf("sent" to true))
    }

    /** Consume a reset token and set a new password. Public auth endpoint. */
    @PostMapping("/reset-password")
    fun resetPassword(@Valid @RequestBody req: ResetPasswordRequest): ApiResponse<Map<String, Boolean>> {
        return when (passwordReset.reset(req.token, req.newPassword)) {
            ResetOutcome.OK -> ApiResponse.ok(mapOf("reset" to true))
            ResetOutcome.EXPIRED ->
                throw com.aixnative.common.web.BadRequestException("재설정 링크가 만료되었습니다. 다시 요청해 주세요.")
            ResetOutcome.INVALID ->
                throw com.aixnative.common.web.BadRequestException("유효하지 않은 재설정 링크입니다. 다시 요청해 주세요.")
        }
    }

    /**
     * Verification link target (clicked from the email, opened in a browser).
     * Returns a small branded HTML page rather than JSON. Public auth endpoint.
     */
    @GetMapping("/verify", produces = [MediaType.TEXT_HTML_VALUE])
    fun verify(@RequestParam token: String): ResponseEntity<String> {
        val outcome = emailVerification.verify(token)
        val (status, title, message) = when (outcome) {
            VerifyOutcome.OK ->
                Triple(HttpStatus.OK, "이메일 인증 완료", "인증이 완료되어 무료 크레딧이 지급되었습니다. 앱으로 돌아가 로그인하세요.")
            VerifyOutcome.ALREADY_VERIFIED ->
                Triple(HttpStatus.OK, "이미 인증됨", "이미 인증된 계정입니다. 그대로 로그인해 사용하세요.")
            VerifyOutcome.EXPIRED ->
                Triple(HttpStatus.GONE, "링크 만료", "인증 링크가 만료되었습니다. 앱에서 인증 메일을 다시 보내 주세요.")
            VerifyOutcome.INVALID ->
                Triple(HttpStatus.BAD_REQUEST, "유효하지 않은 링크", "인증 링크가 올바르지 않습니다. 앱에서 인증 메일을 다시 보내 주세요.")
        }
        return ResponseEntity.status(status)
            .contentType(MediaType.TEXT_HTML)
            .body(verifyPage(title, message, outcome == VerifyOutcome.OK || outcome == VerifyOutcome.ALREADY_VERIFIED))
    }

    private fun verifyPage(title: String, message: String, ok: Boolean): String {
        val accent = if (ok) "#3b3bdc" else "#c0392b"
        val accentTint = accent + "1a"
        val mark = if (ok) "OK" else "!"
        return """
            <!doctype html><html lang="ko"><head><meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <title>$title - AixNative</title>
            <style>
              body{margin:0;min-height:100vh;display:grid;place-items:center;background:#f7f8fb;
                font-family:-apple-system,'Segoe UI','Malgun Gothic',sans-serif;color:#1d2240}
              .card{max-width:420px;margin:1.5rem;padding:2.4rem 2rem;background:#fff;border:1px solid #e7e9f2;
                border-radius:20px;box-shadow:0 24px 60px -24px rgba(30,40,90,.25);text-align:center}
              .badge{width:56px;height:56px;border-radius:16px;margin:0 auto 1.1rem;display:grid;place-items:center;
                background:$accentTint;color:$accent;font-size:1.4rem;font-weight:700}
              h1{font-size:1.35rem;margin:0 0 .6rem;letter-spacing:-.02em}
              p{color:#5a6080;line-height:1.6;margin:0 0 1.4rem}
              a{display:inline-block;padding:.7rem 1.4rem;border-radius:999px;background:$accent;color:#fff;
                text-decoration:none;font-weight:600}
            </style></head><body>
              <div class="card">
                <div class="badge">$mark</div>
                <h1>$title</h1>
                <p>$message</p>
                <a href="/">aixnative 로 이동</a>
              </div>
            </body></html>
        """.trimIndent()
    }
}
