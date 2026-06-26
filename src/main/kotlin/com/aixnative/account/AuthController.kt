package com.aixnative.account

import com.aixnative.billing.CreditService
import com.aixnative.common.tenant.TenantContext
import com.aixnative.common.web.ApiResponse
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/auth")
class AuthController(
    private val authService: AuthService,
    private val creditService: CreditService,
) {

    @PostMapping("/signup")
    fun signup(@Valid @RequestBody req: SignupRequest): ResponseEntity<ApiResponse<AuthResponse>> =
        ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(authService.signup(req)))

    @PostMapping("/login")
    fun login(@Valid @RequestBody req: LoginRequest): ApiResponse<AuthResponse> =
        ApiResponse.ok(authService.login(req))

    /** Current caller + live credit balance. Requires a valid Bearer token. */
    @GetMapping("/me")
    fun me(): ApiResponse<MeResponse> {
        val current = TenantContext.require()
        val balance = creditService.balance(current.tenantId, current.userId)
        return ApiResponse.ok(
            MeResponse(
                userId = current.userId,
                tenantId = current.tenantId,
                email = current.email,
                role = current.role,
                creditBalance = balance,
            ),
        )
    }
}
