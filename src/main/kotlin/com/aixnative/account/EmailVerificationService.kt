package com.aixnative.account

import com.aixnative.billing.CreditService
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.SecureRandom
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Base64

/** Result of consuming a verification token (drives the response page/message). */
enum class VerifyOutcome { OK, ALREADY_VERIFIED, EXPIRED, INVALID }

/**
 * Email-verification lifecycle. Issuing a token sends the link; consuming it
 * flips [User.emailVerified] and grants the free signup credits exactly once
 * (the [User.emailVerified] flag is the idempotency guard, so re-clicks / resends
 * never double-grant).
 */
@Service
class EmailVerificationService(
    private val tokens: EmailVerificationTokenRepository,
    private val users: UserRepository,
    private val creditService: CreditService,
    private val emailService: EmailService,
    @Value("\${app.base-url:http://localhost:8080}") private val baseUrl: String,
    @Value("\${app.verification.token-ttl-hours:24}") private val ttlHours: Long,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val rng = SecureRandom()

    /** Issue a fresh token for [user] and send the verification email. */
    @Transactional
    fun issueAndSend(user: User) {
        val userId = requireNotNull(user.id) { "user must be persisted before issuing a token" }
        val token = newToken()
        tokens.save(
            EmailVerificationToken(
                userId = userId,
                token = token,
                expiresAt = Instant.now().plus(ttlHours, ChronoUnit.HOURS),
            ),
        )
        val url = "${baseUrl.trimEnd('/')}/api/auth/verify?token=$token"
        emailService.sendVerification(user.email, url)
    }

    /** Consume a token: verify the user and grant credits once. */
    @Transactional
    fun verify(token: String): VerifyOutcome {
        val row = tokens.findByToken(token) ?: return VerifyOutcome.INVALID
        if (row.consumedAt != null) return VerifyOutcome.ALREADY_VERIFIED
        if (row.expiresAt.isBefore(Instant.now())) return VerifyOutcome.EXPIRED

        val user = users.findById(row.userId).orElse(null) ?: return VerifyOutcome.INVALID
        row.consumedAt = Instant.now()

        if (user.emailVerified) return VerifyOutcome.ALREADY_VERIFIED
        user.emailVerified = true
        creditService.grantSignupCredits(user.tenantId, requireNotNull(user.id))
        log.info("[verify] 이메일 인증 완료 + 무료 크레딧 지급: userId={}", user.id)
        return VerifyOutcome.OK
    }

    /** Re-send a verification link for an unverified user (no-op if already verified). */
    @Transactional
    fun resend(userId: Long) {
        val user = users.findById(userId).orElse(null) ?: return
        if (user.emailVerified) return
        issueAndSend(user)
    }

    private fun newToken(): String {
        val bytes = ByteArray(32)
        rng.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
}
