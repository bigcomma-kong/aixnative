package com.aixnative.account.service

import com.aixnative.common.security.JwtProperties
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * OAuth `state` 파라미터 — 무상태 HMAC 서명 토큰(CSRF·재생 방어).
 * payload = "provider|exp|nonce", 서명 = HMAC-SHA256(JWT 시크릿). 콜백에서 서명·만료 검증.
 * (무상태 SPA 라 브라우저 바인딩까지는 못 하지만, 위조·만료 재생은 차단.)
 */
@Component
class OAuthStateCodec(jwtProps: JwtProperties) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val key = SecretKeySpec(jwtProps.secret.toByteArray(Charsets.UTF_8), "HmacSHA256")
    private val random = SecureRandom()
    private val b64 = Base64.getUrlEncoder().withoutPadding()
    private val b64d = Base64.getUrlDecoder()

    /** 제공자별 state 발급(10분 유효). */
    fun issue(provider: String): String {
        val nonce = ByteArray(12).also { random.nextBytes(it) }
        val payload = "$provider|${System.currentTimeMillis() + TTL_MS}|${b64.encodeToString(nonce)}"
        return b64.encodeToString(payload.toByteArray(Charsets.UTF_8)) + "." + sign(payload)
    }

    /** 검증 성공 시 provider 문자열 반환, 실패 시 null. */
    fun verify(state: String?): String? {
        if (state.isNullOrBlank()) return null
        val dot = state.indexOf('.')
        if (dot <= 0) return null
        return runCatching {
            val payload = String(b64d.decode(state.substring(0, dot)), Charsets.UTF_8)
            val sig = state.substring(dot + 1)
            if (!constantTimeEquals(sign(payload), sig)) return null
            val parts = payload.split("|")
            if (parts.size != 3) return null
            if (parts[1].toLong() < System.currentTimeMillis()) return null // 만료
            parts[0]
        }.getOrElse {
            log.debug("[oauth] state 검증 실패", it)
            null
        }
    }

    private fun sign(payload: String): String {
        val mac = Mac.getInstance("HmacSHA256").apply { init(key) }
        return b64.encodeToString(mac.doFinal(payload.toByteArray(Charsets.UTF_8)))
    }

    private fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var r = 0
        for (i in a.indices) r = r or (a[i].code xor b[i].code)
        return r == 0
    }

    companion object {
        private const val TTL_MS = 600_000L // 10분
    }
}
