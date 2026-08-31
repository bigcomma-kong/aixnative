package com.aixnative.notice.domain

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

/** 공고 도구의 `ai_tool_run.tool` 코드. [com.aixnative.billing.domain.ToolPricing] 키와 같은 문자열. */
object NoticeTools {
    const val EXTRACT = "NOTICE_EXTRACT"
    const val COMPARE = "NOTICE_COMPARE"
}

/** 공고 원문 최소 길이. */
const val MIN_NOTICE_TEXT_LEN = 30

/** 비교 대상 건수 범위. */
const val NOTICE_COMPARE_MIN = 2
const val NOTICE_COMPARE_MAX = 4

/**
 * 공고 추출 요청. 원문은 업로드 추출(`/api/documents/extract`) 결과나 붙여넣기로 채운다.
 * 국내 공고문은 .hwp 비중이 높아 업로드 경로가 실질적인 기본값이다.
 */
data class NoticeExtractRequest(
    val dealId: Long? = null,
    val dealName: String? = null,
    @field:NotBlank(message = "공고문 원문을 입력하거나 파일에서 불러와 주세요.")
    @field:Size(min = MIN_NOTICE_TEXT_LEN, message = "공고문 원문을 ${MIN_NOTICE_TEXT_LEN}자 이상 입력해 주세요.")
    val text: String = "",
    val sourceFileName: String? = null,
    /** 월 임대료(원) - 알고 있으면 총수익률을 코드가 계산해 준다. 공고문엔 대개 없다. */
    val monthlyRentKrw: Long? = null,
)

/** 공고 2~4건 비교 요청 - 원문이 아니라 추출 결과 runId 로 참조(테넌트 스코프 자동 보장). */
data class NoticeCompareRequest(
    val runIds: List<Long> = emptyList(),
)
