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
    private val emailVerification: EmailVerificationService,
    private val disposableEmailGuard: DisposableEmailGuard,
    @Value("\${app.admin-email:}") private val adminEmail: String,
) {

    /**
     * 가입. 어뷰징 방어로 **무료 크레딧은 가입 즉시가 아니라 이메일 인증 후 지급**한다.
     * - 일반 사용자: 크레딧 0 + 인증 메일 발송(클릭 시 verified + 지급).
     * - 관리자 이메일: 메일 인프라 없이 즉시 인증 처리 + 지급(운영/시드 편의).
     * 로그인은 매끄럽게(가입 직후 JWT 발급) 하되, 가치(크레딧)는 인증 뒤에 열린다.
     */
    @Transactional
    fun signup(req: SignupRequest): AuthResponse {
        disposableEmailGuard.check(req.email)
        if (users.existsByEmail(req.email)) {
            throw ConflictException("이미 가입된 이메일입니다.")
        }
        val isAdmin = isAdminEmail(req.email)
        val role = if (isAdmin) UserRole.ADMIN else UserRole.USER
        val tenant = tenants.save(Tenant(name = req.email))
        val tenantId = requireNotNull(tenant.id)
        val user = users.save(
            User(
                tenantId = tenantId,
                email = req.email,
                passwordHash = passwordEncoder.encode(req.password),
                authProvider = AuthProvider.LOCAL,
                role = role,
                emailVerified = isAdmin, // 관리자는 자동 인증
            ),
        )
        val userId = requireNotNull(user.id)

        val balance = if (isAdmin) {
            creditService.grantSignupCredits(tenantId, userId)
        } else {
            emailVerification.issueAndSend(user) // 인증 메일 발송, 크레딧은 인증 후
            0
        }

        val token = jwtService.issue(AuthPrincipal(userId, tenantId, user.email, user.role.name))
        return AuthResponse(
            token = token, email = user.email, plan = user.plan, role = user.role,
            creditBalance = balance, emailVerified = user.emailVerified,
        )
    }

    /** 현재 사용자가 미인증이면 인증 메일 재발송. */
    @Transactional
    fun resendVerification(userId: Long) = emailVerification.resend(userId)

    @Transactional
    fun login(req: LoginRequest): AuthResponse {
        // Generic message on both branches to avoid account enumeration.
        val invalid = UnauthorizedException("이메일 또는 비밀번호가 올바르지 않습니다.")
        val user = users.findByEmail(req.email) ?: throw invalid
        val hash = user.passwordHash ?: throw invalid // social-only account has no local password
        if (!passwordEncoder.matches(req.password, hash)) throw invalid
        if (user.status != UserStatus.ACTIVE) throw ForbiddenException("비활성화된 계정입니다.")

        // 관리자 이메일이 나중에 지정된 경우, 로그인 시 기존 계정을 ADMIN 으로 승격(멱등).
        if (isAdminEmail(user.email) && user.role != UserRole.ADMIN) {
            user.role = UserRole.ADMIN
        }

        val userId = requireNotNull(user.id)
        val balance = creditService.balance(user.tenantId, userId)
        val token = jwtService.issue(AuthPrincipal(userId, user.tenantId, user.email, user.role.name))
        return AuthResponse(
            token = token, email = user.email, plan = user.plan, role = user.role,
            creditBalance = balance, emailVerified = user.emailVerified,
        )
    }

    /** 설정된 관리자 이메일(app.admin-email)과 대소문자 무시 비교. 미설정이면 항상 false. */
    private fun isAdminEmail(email: String): Boolean =
        adminEmail.isNotBlank() && adminEmail.equals(email, ignoreCase = true)
}
