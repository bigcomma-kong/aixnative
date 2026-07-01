package com.aixnative.account.domain

import com.aixnative.billing.domain.Plan
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

    /** 약관·개인정보 처리방침 동의 일시(PIPA 동의 캡처). 레거시 가입은 null. */
    @Column(name = "terms_agreed_at")
    var termsAgreedAt: Instant? = null,

    /** 동의한 약관 버전(추적). */
    @Column(name = "terms_version", length = 20)
    var termsVersion: String? = null,

    /** 마케팅·이메일 수신 동의(선택). */
    @Column(name = "marketing_opt_in", nullable = false)
    var marketingOptIn: Boolean = false,
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
