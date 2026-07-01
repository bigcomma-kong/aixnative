package com.aixnative.underwriting

import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * 무인증 공개 보고서 — 공유 토큰으로만 접근(SecurityConfig 의 public 경로 permitAll).
 * 토큰은 추측 불가한 랜덤이며, 발급한 소유자의 딜 단계만 읽기전용으로 렌더한다. 인증·크레딧 불필요.
 */
@RestController
@RequestMapping("/api/public")
class PublicReportController(private val reportService: ReportService) {

    @GetMapping("/report/{token}", produces = [MediaType.TEXT_HTML_VALUE])
    fun report(@PathVariable token: String): ResponseEntity<String> =
        ResponseEntity.ok().contentType(MediaType.TEXT_HTML).body(reportService.buildHtmlByToken(token))
}
