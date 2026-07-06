package com.aixnative.property.web

import com.aixnative.common.web.ApiResponse
import com.aixnative.marketfeed.service.MarketFeedProperties
import com.aixnative.property.service.LeaseReminderService
import com.aixnative.property.service.PropertyProperties
import com.aixnative.property.service.ReminderRunReport
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * 임대차 리마인더 배치 트리거(Cloud Scheduler 용). 공개 ingest 경로지만 공유 토큰으로 보호.
 * 인증 토큰은 기존 마켓피드 수집 토큰([MarketFeedProperties.ingestToken])을 재사용(신규 시크릿 불필요).
 * 토큰 미설정이거나 리마인더 비활성이면 403.
 */
@RestController
@RequestMapping("/api/ingest")
class LeaseReminderController(
    private val reminderService: LeaseReminderService,
    private val marketFeedProps: MarketFeedProperties,
    private val propertyProps: PropertyProperties,
) {
    @PostMapping("/lease-reminders")
    fun trigger(
        @RequestHeader(name = "X-Ingest-Token", required = false) token: String?,
    ): ResponseEntity<ApiResponse<ReminderRunReport>> {
        val enabled = propertyProps.reminderEnabled && marketFeedProps.ingestEndpointEnabled
        if (!enabled || token.isNullOrBlank() || token != marketFeedProps.ingestToken) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ApiResponse.fail("리마인더 트리거가 비활성이거나 토큰이 올바르지 않습니다."))
        }
        return ResponseEntity.ok(ApiResponse.ok(reminderService.runReminders()))
    }
}
