package com.aixnative.common.security

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * JWT signing config. Secret is injected from env (never hardcoded).
 * HS256 requires a key of at least 256 bits (32 bytes).
 */
@ConfigurationProperties(prefix = "security.jwt")
data class JwtProperties(
    val secret: String,
    val accessTtlMinutes: Long = 120,
)
