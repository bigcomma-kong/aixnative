package com.aixnative.marketfeed

import com.aixnative.common.tenant.TenantContext
import com.aixnative.common.web.ApiResponse
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * 마켓 브리핑 메일 구독(무료). 구독/상태는 인증 사용자(본인 이메일), 해지는 공개 토큰 링크.
 */
@RestController
@RequestMapping("/api/newsletter")
class NewsletterController(
    private val newsletter: NewsletterService,
) {
    @PostMapping("/subscribe")
    fun subscribe(): ApiResponse<Map<String, Boolean>> {
        newsletter.subscribe(TenantContext.require().email)
        return ApiResponse.ok(mapOf("subscribed" to true))
    }

    @DeleteMapping("/subscribe")
    fun unsubscribe(): ApiResponse<Map<String, Boolean>> {
        newsletter.unsubscribeByEmail(TenantContext.require().email)
        return ApiResponse.ok(mapOf("subscribed" to false))
    }

    @GetMapping("/status")
    fun status(): ApiResponse<Map<String, Boolean>> =
        ApiResponse.ok(mapOf("subscribed" to newsletter.isSubscribed(TenantContext.require().email)))

    /** 메일 푸터 1클릭 해지 — 공개 경로. 작은 브랜드 HTML 페이지 반환. */
    @GetMapping("/unsubscribe", produces = [MediaType.TEXT_HTML_VALUE])
    fun unsubscribeByToken(@RequestParam token: String): ResponseEntity<String> {
        val ok = newsletter.unsubscribeByToken(token)
        val (status, title, msg) = if (ok) {
            Triple(HttpStatus.OK, "구독 해지 완료", "더 이상 시장 브리핑 메일을 보내지 않습니다. 언제든 앱에서 다시 구독할 수 있습니다.")
        } else {
            Triple(HttpStatus.BAD_REQUEST, "유효하지 않은 링크", "해지 링크가 올바르지 않습니다. 앱에서 직접 구독을 해지해 주세요.")
        }
        return ResponseEntity.status(status).contentType(MediaType.TEXT_HTML).body(page(title, msg, ok))
    }

    private fun page(title: String, message: String, ok: Boolean): String {
        val accent = if (ok) "#3b3bdc" else "#c0392b"
        return """
            <!doctype html><html lang="ko"><head><meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <title>$title - aixnative</title>
            <style>
              body{margin:0;min-height:100vh;display:grid;place-items:center;background:#f7f8fb;
                font-family:-apple-system,'Segoe UI','Malgun Gothic',sans-serif;color:#1d2240}
              .card{max-width:420px;margin:1.5rem;padding:2.4rem 2rem;background:#fff;border:1px solid #e7e9f2;
                border-radius:20px;box-shadow:0 24px 60px -24px rgba(30,40,90,.25);text-align:center}
              h1{font-size:1.3rem;margin:0 0 .6rem;color:$accent}
              p{color:#5a6080;line-height:1.6;margin:0 0 1.4rem}
              a{display:inline-block;padding:.7rem 1.4rem;border-radius:999px;background:$accent;color:#fff;
                text-decoration:none;font-weight:600}
            </style></head><body>
              <div class="card"><h1>$title</h1><p>$message</p><a href="/">aixnative 로 이동</a></div>
            </body></html>
        """.trimIndent()
    }
}
