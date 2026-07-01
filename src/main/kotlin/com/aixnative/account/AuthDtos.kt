package com.aixnative.account

import com.aixnative.billing.Plan
import jakarta.validation.constraints.AssertTrue
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

    /** 약관·개인정보 처리방침 필수 동의(미동의 시 가입 차단). */
    @field:AssertTrue(message = "약관 및 개인정보 처리방침에 동의해야 가입할 수 있습니다.")
    val agreedTerms: Boolean = false,

    /** 마케팅·이메일 수신 동의(선택). */
    val marketingOptIn: Boolean = false,
)

data class LoginRequest(
    @field:Email(message = "유효한 이메일이 아닙니다.")
    @field:NotBlank(message = "이메일은 필수입니다.")
    val email: String,

    @field:NotBlank(message = "비밀번호는 필수입니다.")
    @field:Size(max = 200, message = "비밀번호가 너무 깁니다.")
    val password: String,
)

data class ForgotPasswordRequest(
    @field:Email(message = "유효한 이메일이 아닙니다.")
    @field:NotBlank(message = "이메일은 필수입니다.")
    val email: String,
)

data class ResetPasswordRequest(
    @field:NotBlank(message = "토큰이 없습니다.")
    val token: String,

    @field:NotBlank(message = "비밀번호는 필수입니다.")
    @field:Size(min = 8, max = 72, message = "비밀번호는 8자 이상이어야 합니다.")
    val newPassword: String,
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
