package com.aixnative.account

import com.aixnative.common.web.ConflictException
import com.aixnative.common.web.UnauthorizedException
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.annotation.Transactional
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import com.aixnative.account.domain.UserRole
import com.aixnative.account.service.AuthService
import com.aixnative.account.web.LoginRequest
import com.aixnative.account.web.SignupRequest

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class AuthServiceTest(
    @Autowired private val authService: AuthService,
) {

    @Test
    fun `signup returns a token but 0 credits until email is verified`() {
        val res = authService.signup(SignupRequest("alice@example.com", "password123"))

        assertTrue(res.token.isNotBlank())
        assertEquals("alice@example.com", res.email)
        assertEquals(false, res.emailVerified)
        assertEquals(0, res.creditBalance) // 무료 크레딧은 이메일 인증 후 지급
    }

    @Test
    fun `signup with an existing email is rejected`() {
        authService.signup(SignupRequest("bob@example.com", "password123"))
        assertFailsWith<ConflictException> {
            authService.signup(SignupRequest("bob@example.com", "password123"))
        }
    }

    @Test
    fun `login with correct credentials returns a token`() {
        authService.signup(SignupRequest("carol@example.com", "password123"))
        val res = authService.login(LoginRequest("carol@example.com", "password123"))
        assertTrue(res.token.isNotBlank())
        assertEquals(0, res.creditBalance) // 미인증 상태 — 크레딧 0
    }

    @Test
    fun `admin 이메일 가입은 즉시 인증되고 무료 크레딧을 받는다`() {
        val res = authService.signup(SignupRequest("admin@aixnative.com", "password123"))
        assertEquals(true, res.emailVerified)
        assertEquals(5, res.creditBalance)
    }

    @Test
    fun `login with wrong password is unauthorized`() {
        authService.signup(SignupRequest("dave@example.com", "password123"))
        assertFailsWith<UnauthorizedException> {
            authService.login(LoginRequest("dave@example.com", "wrong-password"))
        }
    }

    @Test
    fun `login for unknown email is unauthorized`() {
        assertFailsWith<UnauthorizedException> {
            authService.login(LoginRequest("nobody@example.com", "password123"))
        }
    }

    @Test
    fun `admin 이메일로 가입하면 ADMIN 권한`() {
        val res = authService.signup(SignupRequest("admin@aixnative.com", "password123"))
        assertEquals(UserRole.ADMIN, res.role)
    }

    @Test
    fun `일반 이메일은 USER 권한`() {
        val res = authService.signup(SignupRequest("normal@example.com", "password123"))
        assertEquals(UserRole.USER, res.role)
    }
}
