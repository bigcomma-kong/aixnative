package com.aixnative.analytics.web

import com.aixnative.analytics.service.EventService
import com.aixnative.common.web.ApiResponse
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** 프론트 비콘이 보내는 얕은 행동 이벤트 요청. 모두 선택 필드. */
data class EventRequest(
    val event: String,
    val path: String? = null,
    val meta: String? = null,
)

/**
 * 경량 행동 이벤트 수집 — **공개 경로**(익명 방문자 포함, SecurityConfig permitAll).
 * 로그인 상태면 JWT 필터가 채운 TenantContext 로 tenant/user 가 자동 부착된다.
 * 유효성/저장 실패와 무관하게 항상 200 을 반환(프론트 흐름 비차단, 정보 누출 방지).
 */
@RestController
@RequestMapping("/api/events")
class EventController(private val eventService: EventService) {

    @PostMapping
    fun track(@RequestBody req: EventRequest): ApiResponse<Map<String, Boolean>> {
        eventService.record(req.event, req.path, req.meta)
        return ApiResponse.ok(mapOf("ok" to true))
    }
}
