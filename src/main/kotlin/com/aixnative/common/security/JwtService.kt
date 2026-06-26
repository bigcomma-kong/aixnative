package com.aixnative.common.security

import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import org.springframework.stereotype.Service
import java.util.Date
import javax.crypto.SecretKey

/**
 * Issues and verifies stateless access tokens. Claims: subject=userId,
 * `tid`=tenantId, `email`. No server-side session.
 */
@Service
class JwtService(props: JwtProperties) {

    private val key: SecretKey

    init {
        val bytes = props.secret.toByteArray(Charsets.UTF_8)
        // HS256 requires a >= 256-bit (32-byte) key. Fail fast on a weak/missing secret
        // rather than silently signing with something forgeable.
        require(bytes.size >= 32) {
            "security.jwt.secret must be at least 32 bytes (set JWT_SECRET in the environment)."
        }
        key = Keys.hmacShaKeyFor(bytes)
    }

    private val accessTtlMs: Long = props.accessTtlMinutes * 60_000

    fun issue(principal: AuthPrincipal, now: Long = System.currentTimeMillis()): String =
        Jwts.builder()
            .subject(principal.userId.toString())
            .claim(CLAIM_TENANT, principal.tenantId)
            .claim(CLAIM_EMAIL, principal.email)
            .claim(CLAIM_ROLE, principal.role)
            .issuedAt(Date(now))
            .expiration(Date(now + accessTtlMs))
            .signWith(key)
            .compact()

    /** Verifies signature + expiry and reconstructs the principal. Throws on invalid token. */
    fun parse(token: String): AuthPrincipal {
        val claims = Jwts.parser().verifyWith(key).build()
            .parseSignedClaims(token).payload
        return AuthPrincipal(
            userId = claims.subject.toLong(),
            tenantId = (claims[CLAIM_TENANT] as Number).toLong(),
            email = claims[CLAIM_EMAIL] as String,
            role = (claims[CLAIM_ROLE] as? String) ?: "USER",
        )
    }

    companion object {
        private const val CLAIM_TENANT = "tid"
        private const val CLAIM_EMAIL = "email"
        private const val CLAIM_ROLE = "role"
    }
}
