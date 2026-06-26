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

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class AuthServiceTest(
    @Autowired private val authService: AuthService,
) {

    @Test
    fun `signup creates tenant, grants free credits, and returns a token`() {
        val res = authService.signup(SignupRequest("alice@example.com", "password123"))

        assertTrue(res.token.isNotBlank())
        assertEquals("alice@example.com", res.email)
        assertEquals(5, res.creditBalance) // free-signup-credits = 5 in test profile
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
