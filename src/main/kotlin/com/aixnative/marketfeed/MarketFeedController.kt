package com.aixnative.marketfeed

import com.aixnative.common.web.ApiResponse
import com.aixnative.marketfeed.ingest.IngestReport
import com.aixnative.marketfeed.ingest.MarketFeedIngestService
import com.aixnative.marketfeed.ingest.MarketFeedProperties
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * 시장 인텔리전스 — 읽기. 인증 사용자 누구나(글로벌 콘텐츠, 테넌트 비스코프).
 */
@RestController
@RequestMapping("/api/market-feed")
class MarketFeedController(
    private val service: MarketFeedService,
) {
    /** 최신 딜 카드. '이 딜 분석하기' 진입점(sourceText)을 포함. */
    @GetMapping
    fun list(@RequestParam(required = false, defaultValue = "30") limit: Int): ApiResponse<List<MarketFeedItemView>> =
        ApiResponse.ok(service.latest(limit))

    /** 최신 마켓 브리핑(AI 다이제스트). 아직 생성 전이면 data=null. */
    @GetMapping("/briefing")
    fun briefing(): ApiResponse<MarketBriefingView?> = ApiResponse.ok(service.latestBriefing())
}

/**
 * 시장 인텔리전스 — 쓰기/수동 트리거. ADMIN 전용(SecurityConfig 의 admin 경로 → hasRole("ADMIN")).
 */
@RestController
@RequestMapping("/api/admin/market-feed")
class MarketFeedAdminController(
    private val service: MarketFeedService,
    private val ingestService: MarketFeedIngestService,
) {
    @PostMapping
    fun create(@Valid @RequestBody req: MarketFeedCreateRequest): ApiResponse<MarketFeedItemView> =
        ApiResponse.ok(service.create(req))

    @DeleteMapping("/{id}")
    fun delete(@PathVariable id: Long): ApiResponse<Map<String, Boolean>> {
        service.delete(id)
        return ApiResponse.ok(mapOf("deleted" to true))
    }

    /** 수동 수집 — 관리자가 즉시 한 번 수집·적재(+무료 브리핑). purge=true 면 자동 카드 정리 후 재수집. */
    @PostMapping("/ingest")
    fun ingest(
        @RequestParam(required = false, defaultValue = "false") purge: Boolean,
    ): ApiResponse<IngestReport> = ApiResponse.ok(ingestService.ingest(purge))
}

/**
 * 시장 인텔리전스 — 자동 수집 트리거(Cloud Scheduler 용). 공개 경로지만 공유 토큰으로 보호.
 * Cloud Run min-instances=0 환경: 스케줄러가 이 엔드포인트를 깨워 수집을 돌린다.
 * 토큰 미설정([MarketFeedProperties.ingestToken] 빈 값)이면 비활성(403).
 */
@RestController
@RequestMapping("/api/ingest")
class MarketFeedIngestController(
    private val ingestService: MarketFeedIngestService,
    private val props: MarketFeedProperties,
) {
    @PostMapping("/market-feed")
    fun trigger(
        @RequestHeader(name = "X-Ingest-Token", required = false) token: String?,
        @RequestParam(required = false, defaultValue = "false") purge: Boolean,
    ): ResponseEntity<ApiResponse<IngestReport>> {
        if (!props.ingestEndpointEnabled || token.isNullOrBlank() || token != props.ingestToken) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ApiResponse.fail("수집 트리거가 비활성이거나 토큰이 올바르지 않습니다."))
        }
        return ResponseEntity.ok(ApiResponse.ok(ingestService.ingest(purge)))
    }
}
