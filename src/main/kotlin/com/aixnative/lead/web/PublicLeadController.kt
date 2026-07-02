package com.aixnative.lead.web

import com.aixnative.common.web.ApiResponse
import com.aixnative.lead.service.LeadService
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** 비회원 리드 캡처 요청. */
data class LeadRequest(
    val email: String? = null,
    /** 유입 도구(미지정 시 FREE_PROFORMA). */
    val source: String? = null,
    val marketingOptIn: Boolean = false,
)

/**
 * 무인증 공개 리드 캡처 — 공개 도구에서 이메일 수집(가입 유도).
 * api/public 하위는 SecurityConfig 에서 permitAll.
 */
@RestController
@RequestMapping("/api/public")
class PublicLeadController(private val leadService: LeadService) {

    @PostMapping("/lead")
    fun capture(@RequestBody req: LeadRequest): ApiResponse<Map<String, Boolean>> {
        leadService.capture(req.email, req.source, req.marketingOptIn)
        return ApiResponse.ok(mapOf("captured" to true))
    }
}
