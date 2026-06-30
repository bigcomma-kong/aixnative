package com.aixnative.marketfeed

import com.aixnative.billing.RequiresCredit
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
    private val watchService: DealWatchService,
) {
    /** 딜 카드(최신순). page 0-기반 — 과거 딜 더 보기(아카이브). '이 딜 분석하기' 진입점(sourceText) 포함. */
    @GetMapping
    fun list(
        @RequestParam(required = false, defaultValue = "30") limit: Int,
        @RequestParam(required = false, defaultValue = "0") page: Int,
    ): ApiResponse<MarketFeedPage> =
        ApiResponse.ok(service.latest(limit, page))

    /** 최신 마켓 브리핑(AI 다이제스트). 아직 생성 전이면 data=null. */
    @GetMapping("/briefing")
    fun briefing(): ApiResponse<MarketBriefingView?> = ApiResponse.ok(service.latestBriefing())

    /** 지난 브리핑 아카이브 목록(최신순). 무료 조회. */
    @GetMapping("/briefing/history")
    fun briefingHistory(): ApiResponse<List<BriefingHistoryItem>> =
        ApiResponse.ok(service.briefingHistory())

    /** 저장된 브리핑 단건 다시 보기. 무료. */
    @GetMapping("/briefing/{id}")
    fun briefingById(@PathVariable id: Long): ApiResponse<MarketBriefingView> =
        ApiResponse.ok(service.briefingById(id))

    /** 과금 — AI 심층 시장 리포트(Claude). 성공 시 1 크레딧 차감(무료 브리핑과 구분되는 수익 액션). */
    @RequiresCredit
    @PostMapping("/deep-report")
    fun deepReport(@RequestBody(required = false) req: DeepReportRequest?): ApiResponse<MarketDeepReportView> =
        ApiResponse.ok(service.deepReport(req?.focus))

    /** 내가 생성한 지난 심층 리포트 목록(최신순). 무료 조회. */
    @GetMapping("/deep-report/history")
    fun deepReportHistory(): ApiResponse<List<DeepReportHistoryItem>> =
        ApiResponse.ok(service.deepReportHistory())

    /** 저장된 심층 리포트 단건 재조회. 무료(이미 차감된 결과 다시 보기). */
    @GetMapping("/deep-report/{id}")
    fun deepReportById(@PathVariable id: Long): ApiResponse<MarketDeepReportView> =
        ApiResponse.ok(service.deepReportById(id))

    /** 관심 딜(찜) — 내 목록. 무료. */
    @GetMapping("/watch")
    fun watchList(): ApiResponse<List<DealWatchView>> = ApiResponse.ok(watchService.listMine())

    /** 관심 딜 카드 id 집합(피드 ⭐ 상태 표시용). 무료. */
    @GetMapping("/watch/ids")
    fun watchIds(): ApiResponse<List<Long>> = ApiResponse.ok(watchService.myFeedItemIds())

    /** 찜 추가(idempotent). 무료. */
    @PostMapping("/watch")
    fun watchAdd(@RequestBody req: DealWatchRequest): ApiResponse<DealWatchView> =
        ApiResponse.ok(watchService.add(req.feedItemId))

    /** 찜 해제. 무료. */
    @DeleteMapping("/watch/{feedItemId}")
    fun watchRemove(@PathVariable feedItemId: Long): ApiResponse<Map<String, Boolean>> {
        watchService.remove(feedItemId)
        return ApiResponse.ok(mapOf("removed" to true))
    }
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

    /** 수동 수집 — 관리자가 즉시 한 번 수집·적재(+무료 브리핑). purge=자동카드 정리, notify=구독자 메일발송(기본 미발송). */
    @PostMapping("/ingest")
    fun ingest(
        @RequestParam(required = false, defaultValue = "false") purge: Boolean,
        @RequestParam(required = false, defaultValue = "false") notify: Boolean,
    ): ApiResponse<IngestReport> = ApiResponse.ok(ingestService.ingest(purge, notify))
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
    private val newsletter: NewsletterService,
    private val props: MarketFeedProperties,
) {
    @PostMapping("/market-feed")
    fun trigger(
        @RequestHeader(name = "X-Ingest-Token", required = false) token: String?,
        @RequestParam(required = false, defaultValue = "false") purge: Boolean,
        @RequestParam(required = false, defaultValue = "true") notify: Boolean,
    ): ResponseEntity<ApiResponse<IngestReport>> {
        if (!props.ingestEndpointEnabled || token.isNullOrBlank() || token != props.ingestToken) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ApiResponse.fail("수집 트리거가 비활성이거나 토큰이 올바르지 않습니다."))
        }
        return ResponseEntity.ok(ApiResponse.ok(ingestService.ingest(purge, notify)))
    }

    /** 뉴스레터 테스트 발송(서버 트리거, 공유 토큰 보호). 최신 브리핑을 지정 1개 주소로만 보낸다. */
    @PostMapping("/newsletter-test")
    fun newsletterTest(
        @RequestHeader(name = "X-Ingest-Token", required = false) token: String?,
        @RequestParam email: String,
    ): ResponseEntity<ApiResponse<Map<String, Boolean>>> {
        if (!props.ingestEndpointEnabled || token.isNullOrBlank() || token != props.ingestToken) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ApiResponse.fail("트리거가 비활성이거나 토큰이 올바르지 않습니다."))
        }
        val sent = newsletter.sendTest(email.trim())
        return ResponseEntity.ok(ApiResponse.ok(mapOf("sent" to sent)))
    }
}
