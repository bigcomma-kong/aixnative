package com.aixnative.notice.web

import com.fasterxml.jackson.databind.JsonNode

/**
 * 공고 추출 응답.
 *
 * [extraction] 의 `derived` 블록(평단가·기준가·총수익률)은 **코드가 계산한 값**이다 - AI 산출이 아니다.
 * 화면은 그 구분을 표시해 사용자가 어디까지 믿을 수 있는지 알게 한다.
 */
data class NoticeExtractResponse(
    val runId: Long,
    val dealId: Long,
    val docName: String?,
    val extraction: JsonNode?,
    val analysisRaw: String?,
    val provider: String,
    val creditBalance: Int,
    val disclaimer: String,
)

/** 공고 비교 응답 - 표·우선순위가 담긴 마크다운. */
data class NoticeCompareResponse(
    val runId: Long,
    val dealId: Long,
    val markdown: String,
    val count: Int,
    val provider: String,
    val creditBalance: Int,
    val disclaimer: String,
)
