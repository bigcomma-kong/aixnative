package com.underwriteai.common.security

/** Authenticated caller, derived from a verified JWT and stored in the security context. */
data class AuthPrincipal(
    val userId: Long,
    val tenantId: Long,
    val email: String,
)
