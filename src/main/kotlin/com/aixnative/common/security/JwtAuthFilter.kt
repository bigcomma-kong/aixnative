package com.aixnative.common.security

import com.aixnative.common.tenant.TenantContext
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

/**
 * Reads the Bearer token, verifies it, and populates both the Spring Security
 * context and [TenantContext] for the duration of the request. The tenant
 * context is always cleared afterwards to avoid leakage across pooled threads.
 */
@Component
class JwtAuthFilter(private val jwtService: JwtService) : OncePerRequestFilter() {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val token = resolveToken(request)
        if (token != null) {
            try {
                val principal = jwtService.parse(token)
                val authorities = listOf(SimpleGrantedAuthority("ROLE_${principal.role}"))
                val auth = UsernamePasswordAuthenticationToken(principal, null, authorities)
                SecurityContextHolder.getContext().authentication = auth
                TenantContext.set(
                    TenantContext.Current(principal.tenantId, principal.userId, principal.email, principal.role),
                )
            } catch (ex: Exception) {
                // Invalid/expired token → leave the request anonymous; protected
                // endpoints reject it downstream. Do not leak details to the client.
                log.debug("JWT 검증 실패: {}", ex.message)
            }
        }
        try {
            filterChain.doFilter(request, response)
        } finally {
            TenantContext.clear()
        }
    }

    private fun resolveToken(request: HttpServletRequest): String? {
        val header = request.getHeader("Authorization") ?: return null
        return if (header.startsWith(BEARER_PREFIX)) header.substring(BEARER_PREFIX.length).trim() else null
    }

    companion object {
        private const val BEARER_PREFIX = "Bearer "
    }
}
