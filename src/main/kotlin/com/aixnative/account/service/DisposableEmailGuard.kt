package com.aixnative.account.service

import com.aixnative.common.web.BadRequestException
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * Rejects signups from known disposable/temporary email domains — a cheap first
 * line against free-credit farming. The list (classpath
 * `disposable-email-domains.txt`) is intentionally non-exhaustive; combined with
 * email verification it raises the cost of mass account creation without being
 * heavy-handed on real users.
 */
@Component
class DisposableEmailGuard {
    private val log = LoggerFactory.getLogger(javaClass)
    private val domains: Set<String> = load()

    /** @throws BadRequestException when the email domain is on the blocklist. */
    fun check(email: String) {
        val domain = email.substringAfterLast('@', "").trim().lowercase()
        if (domain.isNotEmpty() && domains.contains(domain)) {
            throw BadRequestException("일회용 이메일 주소로는 가입할 수 없습니다. 상시 사용하는 이메일을 입력해 주세요.")
        }
    }

    private fun load(): Set<String> {
        val stream = javaClass.getResourceAsStream("/disposable-email-domains.txt")
        if (stream == null) {
            log.warn("[signup] disposable-email-domains.txt 리소스 없음 — 1회용 이메일 차단 비활성")
            return emptySet()
        }
        val set = stream.bufferedReader().useLines { lines ->
            lines.map { it.trim().lowercase() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
                .toSet()
        }
        log.info("[signup] 1회용 이메일 도메인 {}개 로드", set.size)
        return set
    }
}
