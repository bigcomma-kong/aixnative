package com.aixnative.admin.web

import com.aixnative.admin.service.AdminDigestService
import com.aixnative.admin.service.DigestReport
import com.aixnative.common.web.ApiResponse
import com.aixnative.marketfeed.service.MarketFeedProperties
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * 관리자 일일 다이제스트 배치 트리거(Cloud Scheduler 용). 공개 ingest 경로지만 공유 토큰으로 보호.
 * 인증 토큰은 기존 마켓피드 수집 토큰([MarketFeedProperties.ingestToken])을 재사용(신규 시크릿 불필요).
 * 토큰 미설정/불일치면 403.
 *
 * Cloud Scheduler 예: 매일 08:00 KST →
 *   POST {base}/api/ingest/admin-digest   Header: X-Ingest-Token: {MARKETFEED_INGEST_TOKEN}
 */
@RestController
@RequestMapping("/api/ingest")
class AdminDigestController(
    private val digestService: AdminDigestService,
    private val marketFeedProps: MarketFeedProperties,
) {
    @PostMapping("/admin-digest")
    fun trigger(
        @RequestHeader(name = "X-Ingest-Token", required = false) token: String?,
    ): ResponseEntity<ApiResponse<DigestReport>> {
        if (!marketFeedProps.ingestEndpointEnabled || token.isNullOrBlank() || token != marketFeedProps.ingestToken) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ApiResponse.fail("다이제스트 트리거가 비활성이거나 토큰이 올바르지 않습니다."))
        }
        return ResponseEntity.ok(ApiResponse.ok(digestService.sendDailyDigest()))
    }
}
