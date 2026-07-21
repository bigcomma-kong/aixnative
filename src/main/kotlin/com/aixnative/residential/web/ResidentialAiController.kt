package com.aixnative.residential.web

import com.aixnative.common.web.ApiResponse
import com.aixnative.residential.service.PresaleBriefService
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * 주거 AI(크레딧 과금) - 경로 api/residential 하위는 SecurityConfig 기본 authenticated(무료 api/public 과 분리).
 * TenantContext 로 현재 유저 스코프 과금(CreditGate). 무료 동네 리포트와 달리 Claude 서술은 크레딧 소모.
 */
@RestController
@RequestMapping("/api/residential")
class ResidentialAiController(
    private val presaleBriefService: PresaleBriefService,
) {
    /** AI 분양 브리핑(2크레딧). region 미지정 시 전국 최근 공고 기준. */
    @PostMapping("/presale-brief")
    fun presaleBrief(@RequestParam(required = false) region: String?): ApiResponse<PresaleBriefService.Result> =
        ApiResponse.ok(presaleBriefService.brief(region))
}
