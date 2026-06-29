package com.aixnative.account

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EntityListeners
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.annotation.CreatedDate
import org.springframework.data.jpa.domain.support.AuditingEntityListener
import java.time.Instant

/**
 * One-time email-verification token. Issued at signup, consumed when the user
 * clicks the link. Consumption flips [User.emailVerified] and grants the free
 * signup credits — so unverified accounts hold 0 credits (anti-abuse gate).
 */
@Entity
@Table(name = "email_verification_token")
@EntityListeners(AuditingEntityListener::class)
class EmailVerificationToken(
    @Column(name = "user_id", nullable = false)
    var userId: Long,

    @Column(nullable = false, length = 64)
    var token: String,

    @Column(name = "expires_at", nullable = false)
    var expiresAt: Instant,

    @Column(name = "consumed_at")
    var consumedAt: Instant? = null,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant? = null
}
