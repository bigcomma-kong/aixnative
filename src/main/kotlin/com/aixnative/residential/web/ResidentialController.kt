package com.aixnative.residential.web

import com.aixnative.common.web.ApiResponse
import com.aixnative.residential.domain.LocationReport
import com.aixnative.residential.service.LocationReportService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * 주거·입지 무료 API(Phase 1) - 무료 입지 리포트(유입 top-of-funnel).
 * 경로 api/public 하위는 SecurityConfig 에서 공개(비인증) - 로그인 없이 체험 → 가입 유도.
 * 심화·딜분석은 별도 인증·크레딧 경로에서 제공(Phase 2+).
 */
@RestController
@RequestMapping("/api/public/residential")
class ResidentialController(
    private val locationReportService: LocationReportService,
) {
    /** 주소/지역 → 무료 입지 리포트. 예: /api/public/residential/location-report?query=서울 강남구 역삼동 */
    @GetMapping("/location-report")
    fun locationReport(@RequestParam query: String): ApiResponse<LocationReport> =
        ApiResponse.ok(locationReportService.report(query.trim()))
}
