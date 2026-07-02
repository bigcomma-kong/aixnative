package com.aixnative.underwriting.web

import com.aixnative.common.web.ApiResponse
import com.aixnative.underwriting.service.UnderwritingService
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * 무인증 공개 ProForma 계산기 — 리드마그넷(가입 없이 IRR/EM/DSCR 체험).
 * 순수 계산(AI·크레딧·DB·테넌트 미사용)이라 익명 노출이 안전하다. 저장하지 않는다.
 * api/public 하위는 SecurityConfig 에서 permitAll.
 */
@RestController
@RequestMapping("/api/public")
class PublicCalcController(private val service: UnderwritingService) {

    @PostMapping("/proforma")
    fun proForma(@Valid @RequestBody req: UnderwriteRequest): ApiResponse<ProFormaResponse> =
        ApiResponse.ok(service.proForma(req))
}
