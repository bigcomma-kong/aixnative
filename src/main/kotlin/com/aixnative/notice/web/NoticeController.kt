package com.aixnative.notice.web

import com.aixnative.billing.service.RequiresCredit
import com.aixnative.common.web.ApiResponse
import com.aixnative.notice.domain.NoticeCompareRequest
import com.aixnative.notice.domain.NoticeExtractRequest
import com.aixnative.notice.service.NoticeExtractionService
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * 공매·매각·입찰 공고 분석. 파일은 `POST /api/documents/extract` 로 먼저 텍스트를 뽑아 넘긴다
 * (국내 공고문은 .hwp 비중이 높아 업로드 경로가 사실상 기본값이다).
 */
@RestController
@RequestMapping("/api/notices")
class NoticeController(
    private val service: NoticeExtractionService,
) {
    /** 공고문 1건 → 정형 추출 + 코드 산출 평단가·수익률 + 응찰 리스크. */
    @RequiresCredit
    @PostMapping("/extract")
    fun extract(@Valid @RequestBody req: NoticeExtractRequest): ApiResponse<NoticeExtractResponse> =
        ApiResponse.ok(service.extract(req))

    /** 추출 결과 2~4건 비교(매력도·우선순위, 마크다운). */
    @RequiresCredit
    @PostMapping("/compare")
    fun compare(@RequestBody req: NoticeCompareRequest): ApiResponse<NoticeCompareResponse> =
        ApiResponse.ok(service.compare(req))
}
