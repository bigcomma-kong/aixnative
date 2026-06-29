package com.aixnative.common.web

import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.util.concurrent.ConcurrentHashMap

/**
 * Per-IP fixed-window rate limit on account creation. Light defense against
 * signup flooding / free-credit farming and reset-mail spam — `POST /api/auth/signup`,
 * `/api/auth/resend-verification`, and `/api/auth/forgot-password` are throttled;
 * everything else passes through.
 *
 * In-memory per instance (Cloud Run min=0/max=4) — good enough for v1; a shared
 * store (Redis) can replace the map if horizontal abuse appears.
 */
@Component
class SignupRateLimitFilter(
    private val objectMapper: ObjectMapper,
    @Value("\${app.signup-rate-limit.max-per-hour:5}") private val maxPerHour: Int,
) : OncePerRequestFilter() {

    private val log = LoggerFactory.getLogger(javaClass)
    private val windowMs = 60 * 60 * 1000L
    private val buckets = ConcurrentHashMap<String, Window>()

    private class Window(@Volatile var start: Long, @Volatile var count: Int)

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        if (!isThrottled(request)) {
            chain.doFilter(request, response)
            return
        }
        val ip = clientIp(request)
        val now = System.currentTimeMillis()
        val allowed = allow(ip, now)
        if (!allowed) {
            log.warn("[rate-limit] 가입 시도 한도 초과: ip={} ({}/h)", ip, maxPerHour)
            writeTooMany(response)
            return
        }
        chain.doFilter(request, response)
    }

    private fun isThrottled(req: HttpServletRequest): Boolean {
        if (req.method != "POST") return false
        val p = req.requestURI
        return p == "/api/auth/signup" ||
            p == "/api/auth/resend-verification" ||
            p == "/api/auth/forgot-password"
    }

    private fun allow(ip: String, now: Long): Boolean {
        val w = requireNotNull(
            buckets.compute(ip) { _, cur ->
                if (cur == null || now - cur.start >= windowMs) Window(now, 1)
                else { cur.count += 1; cur }
            },
        )
        // 가끔 오래된 버킷 정리(메모리 누수 방지) — 가벼운 확률적 청소.
        if (buckets.size > 10_000) buckets.entries.removeIf { now - it.value.start >= windowMs }
        return w.count <= maxPerHour
    }

    private fun clientIp(req: HttpServletRequest): String {
        val xff = req.getHeader("X-Forwarded-For")
        if (!xff.isNullOrBlank()) return xff.split(",").first().trim()
        return req.remoteAddr ?: "unknown"
    }

    private fun writeTooMany(res: HttpServletResponse) {
        res.status = HttpStatus.TOO_MANY_REQUESTS.value()
        res.contentType = MediaType.APPLICATION_JSON_VALUE
        res.characterEncoding = "UTF-8"
        val body: ApiResponse<Any?> = ApiResponse.fail("가입 시도가 너무 많습니다. 잠시 후 다시 시도해 주세요.")
        res.writer.write(objectMapper.writeValueAsString(body))
    }
}
