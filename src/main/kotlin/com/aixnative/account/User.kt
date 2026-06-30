package com.aixnative.account

import com.aixnative.billing.Plan
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EntityListeners
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.annotation.CreatedDate
import org.springframework.data.annotation.LastModifiedDate
import org.springframework.data.jpa.domain.support.AuditingEntityListener
import java.time.Instant

enum class UserStatus { ACTIVE, DISABLED }

enum class AuthProvider { LOCAL, GOOGLE, KAKAO, NAVER }

enum class UserRole { USER, ADMIN }

@Entity
@Table(name = "users")
@EntityListeners(AuditingEntityListener::class)
class User(
    @Column(name = "tenant_id", nullable = false)
    var tenantId: Long,

    @Column(nullable = false)
    var email: String,

    /** BCrypt hash. Null for social-only accounts (no local password). */
    @Column(name = "password_hash")
    var passwordHash: String? = null,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var status: UserStatus = UserStatus.ACTIVE,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var plan: Plan = Plan.FREE,

    @Enumerated(EnumType.STRING)
    @Column(name = "auth_provider", nullable = false, length = 20)
    var authProvider: AuthProvider = AuthProvider.LOCAL,

    /** 소셜 계정의 제공자 고유 id(sub). LOCAL 은 null. (provider, providerId) 로 재로그인 식별. */
    @Column(name = "provider_id", length = 100)
    var providerId: String? = null,

    @Column(name = "email_verified", nullable = false)
    var emailVerified: Boolean = false,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var role: UserRole = UserRole.USER,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant? = null

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant? = null
}
