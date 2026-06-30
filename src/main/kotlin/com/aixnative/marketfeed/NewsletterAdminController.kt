package com.aixnative.marketfeed

import com.aixnative.common.web.ApiResponse
import com.aixnative.common.web.BadRequestException
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/** 테스트 발송 요청. */
data class NewsletterTestSendRequest(val email: String)

/**
 * 관리자 — 뉴스레터 구독자/발송 관리 + 미리보기/테스트 발송. ADMIN 전용(SecurityConfig 의 admin 경로 가드).
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

    /** 최신 브리핑 뉴스레터 HTML 미리보기(발송 없음). 프론트가 Bearer 헤더로 받아 새 창에 렌더. */
    @GetMapping("/preview", produces = [MediaType.TEXT_HTML_VALUE])
    fun preview(): ResponseEntity<String> {
        val html = newsletter.previewHtml()
            ?: return ResponseEntity.ok().contentType(MediaType.TEXT_HTML).body(NO_BRIEFING_HTML)
        return ResponseEntity.ok().contentType(MediaType.TEXT_HTML).body(html)
    }

    /** 지정 주소로 테스트 발송(구독 여부 무관). */
    @PostMapping("/test-send")
    fun testSend(@RequestBody req: NewsletterTestSendRequest): ApiResponse<Map<String, Boolean>> {
        val email = req.email.trim()
        if (email.isBlank() || !email.contains('@')) throw BadRequestException("유효한 이메일을 입력하세요.")
        if (!newsletter.sendTest(email)) throw BadRequestException("발송할 브리핑이 없습니다. 먼저 '지금 수집'을 실행하세요.")
        return ApiResponse.ok(mapOf("sent" to true))
    }

    private companion object {
        const val NO_BRIEFING_HTML =
            "<p style='font-family:sans-serif;padding:2rem;color:#5a6080'>아직 생성된 브리핑이 없습니다. " +
                "관리자 화면에서 '지금 수집'을 먼저 실행하세요.</p>"
    }
}
