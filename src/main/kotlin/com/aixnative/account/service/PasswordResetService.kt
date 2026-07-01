package com.aixnative.account.service

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.SecureRandom
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Base64
import com.aixnative.account.domain.PasswordResetToken
import com.aixnative.account.domain.UserStatus
import com.aixnative.account.repository.PasswordResetTokenRepository
import com.aixnative.account.repository.UserRepository

/** Result of consuming a reset token (drives the response message). */
enum class ResetOutcome { OK, EXPIRED, INVALID }

/**
 * '비밀번호 찾기' lifecycle. [requestReset] mails a single-use link to the registered
 * address; [reset] validates the token and replaces the password hash. Requesting a
 * reset never reveals whether an email exists (anti-enumeration) — the controller
 * always returns the same response regardless of outcome.
 */
@Service
class PasswordResetService(
    private val tokens: PasswordResetTokenRepository,
    private val users: UserRepository,
    private val passwordEncoder: PasswordEncoder,
    private val emailService: EmailService,
    @Value("\${app.base-url:http://localhost:8080}") private val baseUrl: String,
    @Value("\${app.password-reset.token-ttl-hours:2}") private val ttlHours: Long,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val rng = SecureRandom()

    /**
     * Issue a reset token for the account at [email] and mail the link. Silently
     * no-ops for unknown emails or social-only accounts (no local password to reset),
     * so the caller's response is identical either way.
     */
    @Transactional
    fun requestReset(email: String) {
        val user = users.findByEmail(email) ?: return
        if (user.passwordHash == null) return // 소셜 전용 계정 — 재설정할 로컬 비번 없음
        if (user.status != UserStatus.ACTIVE) return
        val userId = requireNotNull(user.id)

        val token = newToken()
        tokens.save(
            PasswordResetToken(
                userId = userId,
                token = token,
                expiresAt = Instant.now().plus(ttlHours, ChronoUnit.HOURS),
            ),
        )
        // SPA 가 ?reset=<token> 으로 부팅 시 재설정 화면을 띄운다.
        val url = "${baseUrl.trimEnd('/')}/?reset=$token"
        emailService.sendPasswordReset(user.email, url)
        log.info("[reset] 비밀번호 재설정 링크 발송: userId={}", userId)
    }

    /** Consume a token and set the new password. Single-use ([consumedAt]). */
    @Transactional
    fun reset(token: String, newPassword: String): ResetOutcome {
        val row = tokens.findByToken(token) ?: return ResetOutcome.INVALID
        if (row.consumedAt != null) return ResetOutcome.INVALID
        if (row.expiresAt.isBefore(Instant.now())) return ResetOutcome.EXPIRED

        val user = users.findById(row.userId).orElse(null) ?: return ResetOutcome.INVALID
        row.consumedAt = Instant.now()
        user.passwordHash = passwordEncoder.encode(newPassword)
        log.info("[reset] 비밀번호 재설정 완료: userId={}", user.id)
        return ResetOutcome.OK
    }

    private fun newToken(): String {
        val bytes = ByteArray(32)
        rng.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
}
