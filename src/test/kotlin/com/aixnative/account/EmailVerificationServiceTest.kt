package com.aixnative.account

import com.aixnative.billing.CreditService
import com.aixnative.common.web.BadRequestException
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.annotation.Transactional
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class EmailVerificationServiceTest(
    @Autowired private val authService: AuthService,
    @Autowired private val emailVerification: EmailVerificationService,
    @Autowired private val tokens: EmailVerificationTokenRepository,
    @Autowired private val users: UserRepository,
    @Autowired private val creditService: CreditService,
) {

    private fun tokenFor(email: String): String {
        val userId = requireNotNull(users.findByEmail(email)?.id)
        return requireNotNull(tokens.findAll().firstOrNull { it.userId == userId }) { "토큰 없음" }.token
    }

    private fun balanceOf(email: String): Int {
        val u = requireNotNull(users.findByEmail(email))
        return creditService.balance(u.tenantId, requireNotNull(u.id))
    }

    @Test
    fun `이메일 인증을 마치면 무료 크레딧이 지급된다`() {
        authService.signup(SignupRequest("erin@example.com", "password123"))
        assertEquals(0, balanceOf("erin@example.com"))

        val outcome = emailVerification.verify(tokenFor("erin@example.com"))

        assertEquals(VerifyOutcome.OK, outcome)
        assertEquals(5, balanceOf("erin@example.com"))
        assertEquals(true, requireNotNull(users.findByEmail("erin@example.com")).emailVerified)
    }

    @Test
    fun `잘못된 토큰은 INVALID 이며 크레딧을 지급하지 않는다`() {
        authService.signup(SignupRequest("frank@example.com", "password123"))
        val outcome = emailVerification.verify("not-a-real-token")
        assertEquals(VerifyOutcome.INVALID, outcome)
        assertEquals(0, balanceOf("frank@example.com"))
    }

    @Test
    fun `같은 토큰 재사용은 ALREADY 이며 크레딧을 중복 지급하지 않는다`() {
        authService.signup(SignupRequest("grace@example.com", "password123"))
        val token = tokenFor("grace@example.com")

        assertEquals(VerifyOutcome.OK, emailVerification.verify(token))
        assertEquals(VerifyOutcome.ALREADY_VERIFIED, emailVerification.verify(token))
        assertEquals(5, balanceOf("grace@example.com")) // 두 번째는 지급 안 됨
    }

    @Test
    fun `일회용 이메일 도메인은 가입이 거부된다`() {
        assertFailsWith<BadRequestException> {
            authService.signup(SignupRequest("farmer@mailinator.com", "password123"))
        }
    }
}
