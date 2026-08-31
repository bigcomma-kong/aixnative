package com.aixnative.social.web

import com.aixnative.common.web.ApiResponse
import com.aixnative.social.service.AsyncIngestRunner
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
    private val asyncRunner: AsyncIngestRunner,
) {
    /** 게시물 목록(최신순). */
    @GetMapping
    fun list(): ApiResponse<List<SocialPostView>> = ApiResponse.ok(service.listAll())

    /**
     * 수동 수집·생성(관리자). 수집이 분 단위라 **비동기**로 시작하고 즉시 반환(브라우저 524 회피).
     * 목록은 프론트가 폴링으로 갱신. 항상 승인 대기(검토용) - 자동 게시 안 함.
     */
    @PostMapping("/ingest")
    fun ingest(): ApiResponse<Map<String, Any>> {
        val started = asyncRunner.trigger(autoPublish = false)
        return ApiResponse.ok(
            mapOf(
                "started" to started,
                "message" to if (started) {
                    "수집을 시작했습니다. 백그라운드로 생성 중이며 목록이 자동 새로고침됩니다(1~3분)."
                } else {
                    "이미 수집이 진행 중입니다. 잠시 후 새로고침하세요."
                },
            ),
        )
    }

    @PostMapping("/{id}/approve")
    fun approve(@PathVariable id: Long): ApiResponse<SocialPostView> = ApiResponse.ok(service.approve(id))

    @PostMapping("/{id}/reject")
    fun reject(@PathVariable id: Long): ApiResponse<SocialPostView> = ApiResponse.ok(service.reject(id))

    @PostMapping("/{id}/publish")
    fun publish(@PathVariable id: Long): ApiResponse<SocialPostView> = ApiResponse.ok(service.publish(id))

    /**
     * STORY 게시물 이미지 재생성(관리자) - 새 이미지 엔진 결과를 새 소재 없이 확인·비교용.
     * 장면당 최악 60초대 x 장면 수 + Node 렌더라 **비동기**로 시작하고 즉시 반환한다(Cloud Run 300s 회피).
     * 완료는 프론트가 목록 폴링으로 확인(이미지 URL 에 버전 쿼리가 붙어 캐시가 갱신된다).
     */
    @PostMapping("/{id}/regenerate-images")
    fun regenerateImages(@PathVariable id: Long): ApiResponse<Map<String, Any>> {
        service.assertRegenerable(id) // 잘못된 요청은 백그라운드로 넘기지 않고 즉시 400
        val started = asyncRunner.triggerRegenerate(id)
        return ApiResponse.ok(
            mapOf(
                "started" to started,
                "message" to if (started) {
                    "이미지를 다시 만들고 있습니다. 완료되면 목록이 자동 갱신됩니다(1~3분)."
                } else {
                    "이미 재생성이 진행 중입니다. 잠시 후 새로고침하세요."
                },
            ),
        )
    }

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
    private val asyncRunner: AsyncIngestRunner,
    private val props: SocialProperties,
) {
    @PostMapping("/social-post")
    fun trigger(
        @RequestHeader(name = "X-Ingest-Token", required = false) token: String?,
    ): ResponseEntity<ApiResponse<Map<String, Any>>> {
        if (!props.ingestEndpointEnabled || token.isNullOrBlank() || token != props.ingestToken) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ApiResponse.fail("수집 트리거가 비활성이거나 토큰이 올바르지 않습니다."))
        }
        // 스케줄러 경로 - 비동기 시작(즉시 반환). social.auto-publish=true 면 승인 없이 완전 자동 게시.
        val started = asyncRunner.trigger(autoPublish = props.autoPublish)
        return ResponseEntity.ok(ApiResponse.ok(mapOf("started" to started)))
    }
}
