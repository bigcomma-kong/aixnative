package com.underwriteai.account

import com.underwriteai.billing.CreditService
import com.underwriteai.common.security.AuthPrincipal
import com.underwriteai.common.security.JwtService
import com.underwriteai.common.web.ConflictException
import com.underwriteai.common.web.ForbiddenException
import com.underwriteai.common.web.UnauthorizedException
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Email/password signup + login. On signup a tenant is created (1 user = 1
 * tenant) and the free credit grant is recorded. Returns a stateless JWT.
 *
 * Social OAuth (Google/Kakao) and email verification are structured but not
 * wired in this phase — they activate once new keys are provisioned.
 */
@Service
class AuthService(
    private val tenants: TenantRepository,
    private val users: UserRepository,
    private val passwordEncoder: PasswordEncoder,
    private val jwtService: JwtService,
    private val creditService: CreditService,
) {

    @Transactional
    fun signup(req: SignupRequest): AuthResponse {
        if (users.existsByEmail(req.email)) {
            throw ConflictException("이미 가입된 이메일입니다.")
        }
        val tenant = tenants.save(Tenant(name = req.email))
        val tenantId = requireNotNull(tenant.id)
        val user = users.save(
            User(
                tenantId = tenantId,
                email = req.email,
                passwordHash = passwordEncoder.encode(req.password),
                authProvider = AuthProvider.LOCAL,
            ),
        )
        val userId = requireNotNull(user.id)
        val balance = creditService.grantSignupCredits(tenantId, userId)
        val token = jwtService.issue(AuthPrincipal(userId, tenantId, user.email))
        return AuthResponse(token = token, email = user.email, plan = user.plan, creditBalance = balance)
    }

    @Transactional(readOnly = true)
    fun login(req: LoginRequest): AuthResponse {
        // Generic message on both branches to avoid account enumeration.
        val invalid = UnauthorizedException("이메일 또는 비밀번호가 올바르지 않습니다.")
        val user = users.findByEmail(req.email) ?: throw invalid
        val hash = user.passwordHash ?: throw invalid // social-only account has no local password
        if (!passwordEncoder.matches(req.password, hash)) throw invalid
        if (user.status != UserStatus.ACTIVE) throw ForbiddenException("비활성화된 계정입니다.")

        val userId = requireNotNull(user.id)
        val balance = creditService.balance(user.tenantId, userId)
        val token = jwtService.issue(AuthPrincipal(userId, user.tenantId, user.email))
        return AuthResponse(token = token, email = user.email, plan = user.plan, creditBalance = balance)
    }
}
