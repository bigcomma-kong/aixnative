package com.aixnative.marketfeed

import com.aixnative.common.web.ApiResponse
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * 관리자 — 뉴스레터 구독자/발송 관리. ADMIN 전용(SecurityConfig 의 admin 경로 가드).
 */
@RestController
@RequestMapping("/api/admin/newsletter")
class NewsletterAdminController(
    private val newsletter: NewsletterService,
) {
    /** 구독자 목록 + 활성 수. */
    @GetMapping("/subscribers")
    fun subscribers(): ApiResponse<List<NewsSubscriberView>> =
        ApiResponse.ok(newsletter.listSubscribers(), meta = mapOf("active" to newsletter.activeCount()))

    /** 최근 발송 로그(누구에게/언제/성공여부). */
    @GetMapping("/send-log")
    fun sendLog(@RequestParam(required = false, defaultValue = "100") limit: Int): ApiResponse<List<NewsletterSendLogView>> =
        ApiResponse.ok(newsletter.recentSendLog(limit))
}
