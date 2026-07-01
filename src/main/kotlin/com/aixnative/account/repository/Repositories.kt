package com.aixnative.account.repository

import org.springframework.data.jpa.repository.JpaRepository
import com.aixnative.account.domain.AuthProvider
import com.aixnative.account.domain.EmailVerificationToken
import com.aixnative.account.domain.PasswordResetToken
import com.aixnative.account.domain.Tenant
import com.aixnative.account.domain.User

interface TenantRepository : JpaRepository<Tenant, Long>

interface UserRepository : JpaRepository<User, Long> {
    fun findByEmail(email: String): User?
    fun existsByEmail(email: String): Boolean
    /** 소셜 재로그인 식별 — (제공자, 제공자 고유 id). */
    fun findByAuthProviderAndProviderId(authProvider: AuthProvider, providerId: String): User?
}

interface EmailVerificationTokenRepository : JpaRepository<EmailVerificationToken, Long> {
    fun findByToken(token: String): EmailVerificationToken?
    fun deleteByUserId(userId: Long)
}

interface PasswordResetTokenRepository : JpaRepository<PasswordResetToken, Long> {
    fun findByToken(token: String): PasswordResetToken?
    fun deleteByUserId(userId: Long)
}
