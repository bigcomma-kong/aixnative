package com.aixnative.social.web

import com.aixnative.common.web.ApiResponse
import com.aixnative.social.domain.SocialIngestReport
import com.aixnative.social.service.SocialPostService
import com.aixnative.social.service.SocialProperties
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * 공감랭킹 소셜 게시물 - 관리자 콘솔(승인 워크플로우). ADMIN 전용
 * (SecurityConfig 의 api/admin 경로 → hasRole("ADMIN")).
 */
@RestController
@RequestMapping("/api/admin/social")
class SocialAdminController(
    private val service: SocialPostService,
) {
    /** 게시물 목록(최신순). */
    @GetMapping
    fun list(): ApiResponse<List<SocialPostView>> = ApiResponse.ok(service.listAll())

    /** 수동 수집·생성(관리자 즉시 1회). 항상 승인 대기(검토용) - 자동 게시 안 함. */
    @PostMapping("/ingest")
    fun ingest(): ApiResponse<SocialIngestReport> = ApiResponse.ok(service.ingest(autoPublish = false))

    @PostMapping("/{id}/approve")
    fun approve(@PathVariable id: Long): ApiResponse<SocialPostView> = ApiResponse.ok(service.approve(id))

    @PostMapping("/{id}/reject")
    fun reject(@PathVariable id: Long): ApiResponse<SocialPostView> = ApiResponse.ok(service.reject(id))

    @PostMapping("/{id}/publish")
    fun publish(@PathVariable id: Long): ApiResponse<SocialPostView> = ApiResponse.ok(service.publish(id))

    @DeleteMapping("/{id}")
    fun delete(@PathVariable id: Long): ApiResponse<Map<String, Boolean>> {
        service.delete(id)
        return ApiResponse.ok(mapOf("deleted" to true))
    }
}

/**
 * 소셜 자동 수집 트리거(Cloud Scheduler 용). 공개 경로지만 공유 토큰(X-Ingest-Token)으로 보호.
 * 토큰 미설정 시 비활성(403). api/ingest 경로는 SecurityConfig 에서 이미 permitAll.
 */
@RestController
@RequestMapping("/api/ingest")
class SocialIngestController(
    private val service: SocialPostService,
    private val props: SocialProperties,
) {
    @PostMapping("/social-post")
    fun trigger(
        @RequestHeader(name = "X-Ingest-Token", required = false) token: String?,
    ): ResponseEntity<ApiResponse<SocialIngestReport>> {
        if (!props.ingestEndpointEnabled || token.isNullOrBlank() || token != props.ingestToken) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ApiResponse.fail("수집 트리거가 비활성이거나 토큰이 올바르지 않습니다."))
        }
        // 스케줄러 경로 - social.auto-publish=true 면 승인 없이 완전 자동 게시.
        return ResponseEntity.ok(ApiResponse.ok(service.ingest(autoPublish = props.autoPublish)))
    }
}
