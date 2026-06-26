package com.aixnative.account

import com.aixnative.billing.CreditService
import com.aixnative.common.security.AuthPrincipal
import com.aixnative.common.security.JwtService
import com.aixnative.common.web.ConflictException
import com.aixnative.common.web.ForbiddenException
import com.aixnative.common.web.UnauthorizedException
import org.springframework.beans.factory.annotation.Value
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
    @Value("\${app.admin-email:}") private val adminEmail: String,
) {

    @Transactional
    fun signup(req: SignupRequest): AuthResponse {
        if (users.existsByEmail(req.email)) {
            throw ConflictException("이미 가입된 이메일입니다.")
        }
        // 지정된 관리자 이메일로 가입하면 ADMIN 권한 부여(app.admin-email).
        val role = if (adminEmail.isNotBlank() && adminEmail.equals(req.email, ignoreCase = true)) {
            UserRole.ADMIN
        } else {
            UserRole.USER
        }
        val tenant = tenants.save(Tenant(name = req.email))
        val tenantId = requireNotNull(tenant.id)
        val user = users.save(
            User(
                tenantId = tenantId,
                email = req.email,
                passwordHash = passwordEncoder.encode(req.password),
                authProvider = AuthProvider.LOCAL,
                role = role,
            ),
        )
        val userId = requireNotNull(user.id)
        val balance = creditService.grantSignupCredits(tenantId, userId)
        val token = jwtService.issue(AuthPrincipal(userId, tenantId, user.email, user.role.name))
        return AuthResponse(token = token, email = user.email, plan = user.plan, role = user.role, creditBalance = balance)
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
        val token = jwtService.issue(AuthPrincipal(userId, user.tenantId, user.email, user.role.name))
        return AuthResponse(token = token, email = user.email, plan = user.plan, role = user.role, creditBalance = balance)
    }
}
