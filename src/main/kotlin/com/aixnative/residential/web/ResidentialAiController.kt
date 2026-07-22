package com.aixnative.residential.web

import com.aixnative.common.web.ApiResponse
import com.aixnative.residential.service.PresaleBriefService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * 주거 AI(크레딧 과금) - 경로 api/residential 하위는 SecurityConfig 기본 authenticated(무료 api/public 과 분리).
 * TenantContext 로 현재 유저 스코프 과금·저장(CreditGate + 브리핑 영속).
 */
@RestController
@RequestMapping("/api/residential")
class ResidentialAiController(
    private val presaleBriefService: PresaleBriefService,
) {
    /** AI 동네 브리핑(2크레딧) - 해당 주소의 실측 시장 컨텍스트로 지역 맞춤 브리핑. 성공 시 저장. */
    @PostMapping("/brief")
    fun brief(@RequestParam query: String): ApiResponse<PresaleBriefService.Result> =
        ApiResponse.ok(presaleBriefService.brief(query))

    /** 마이페이지 - 저장된 내 브리핑 목록(최신순). */
    @GetMapping("/briefs")
    fun myBriefs(): ApiResponse<List<PresaleBriefService.Saved>> =
        ApiResponse.ok(presaleBriefService.myBriefs())
}
