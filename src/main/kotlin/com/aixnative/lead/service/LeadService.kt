package com.aixnative.lead.service

import com.aixnative.account.service.DisposableEmailGuard
import com.aixnative.common.web.BadRequestException
import com.aixnative.lead.domain.Lead
import com.aixnative.lead.repository.LeadRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 비회원 리드 캡처. 공개 도구에서 수집한 이메일을 저장한다(가입 유도용).
 * 검증: 형식 + 일회용 도메인 차단(가입과 동일 정책 재사용). (email, source) 중복은 무시.
 */
@Service
class LeadService(
    private val repository: LeadRepository,
    private val disposableEmailGuard: DisposableEmailGuard,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val emailRegex = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")

    @Transactional
    fun capture(rawEmail: String?, rawSource: String?, marketingOptIn: Boolean) {
        val email = rawEmail?.trim()?.lowercase().orEmpty()
        if (!emailRegex.matches(email)) throw BadRequestException("올바른 이메일 주소를 입력해 주세요.")
        disposableEmailGuard.check(email)

        val source = rawSource?.trim()?.ifBlank { null }?.take(60) ?: DEFAULT_SOURCE
        if (repository.existsByEmailAndSource(email, source)) return // 이미 수집됨 — 조용히 통과

        repository.save(Lead(email = email, source = source, marketingOptIn = marketingOptIn))
        log.info("[lead] captured source={} optIn={}", source, marketingOptIn)
    }

    private companion object {
        const val DEFAULT_SOURCE = "FREE_PROFORMA"
    }
}
