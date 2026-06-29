package com.aixnative.account

import com.aixnative.billing.Plan
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

data class SignupRequest(
    @field:Email(message = "유효한 이메일이 아닙니다.")
    @field:NotBlank(message = "이메일은 필수입니다.")
    val email: String,

    @field:NotBlank(message = "비밀번호는 필수입니다.")
    @field:Size(min = 8, max = 72, message = "비밀번호는 8자 이상이어야 합니다.")
    val password: String,
)

data class LoginRequest(
    @field:Email(message = "유효한 이메일이 아닙니다.")
    @field:NotBlank(message = "이메일은 필수입니다.")
    val email: String,

    @field:NotBlank(message = "비밀번호는 필수입니다.")
    @field:Size(max = 200, message = "비밀번호가 너무 깁니다.")
    val password: String,
)

data class AuthResponse(
    val token: String,
    val email: String,
    val plan: Plan,
    val role: UserRole,
    val creditBalance: Int,
    val emailVerified: Boolean,
)

data class MeResponse(
    val userId: Long,
    val tenantId: Long,
    val email: String,
    val role: String,
    val creditBalance: Int,
    val emailVerified: Boolean,
)
