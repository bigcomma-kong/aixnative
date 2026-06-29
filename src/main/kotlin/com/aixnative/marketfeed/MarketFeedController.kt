package com.aixnative.marketfeed

import com.aixnative.common.web.ApiResponse
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * 시장 인텔리전스 피드 — 읽기. 인증 사용자 누구나(글로벌 콘텐츠, 테넌트 비스코프).
 */
@RestController
@RequestMapping("/api/market-feed")
class MarketFeedController(
    private val service: MarketFeedService,
) {
    /** 최신 피드 카드. '이 딜 분석하기' 진입점(sourceText)을 포함. */
    @GetMapping
    fun list(@RequestParam(required = false, defaultValue = "30") limit: Int): ApiResponse<List<MarketFeedItemView>> =
        ApiResponse.ok(service.latest(limit))
}

/**
 * 시장 인텔리전스 피드 — 쓰기. ADMIN 전용(SecurityConfig 의 admin 경로 → hasRole("ADMIN")).
 */
@RestController
@RequestMapping("/api/admin/market-feed")
class MarketFeedAdminController(
    private val service: MarketFeedService,
) {
    @PostMapping
    fun create(@Valid @RequestBody req: MarketFeedCreateRequest): ApiResponse<MarketFeedItemView> =
        ApiResponse.ok(service.create(req))

    @DeleteMapping("/{id}")
    fun delete(@PathVariable id: Long): ApiResponse<Map<String, Boolean>> {
        service.delete(id)
        return ApiResponse.ok(mapOf("deleted" to true))
    }
}
