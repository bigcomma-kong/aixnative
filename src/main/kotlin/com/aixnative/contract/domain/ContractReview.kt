package com.aixnative.contract.domain

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

/**
 * 검토 관점 - 누구 편에서 볼 것인가. 같은 조항도 임대인에게 유리하면 임차인에게 불리하므로,
 * 관점을 정하지 않으면 "양쪽 다 조심하세요" 수준의 쓸모없는 총평이 나온다.
 */
enum class ReviewPerspective(val label: String) {
    NEUTRAL("중립"),
    LESSOR("임대인"),
    LESSEE("임차인"),
    BUYER("매수인"),
    SELLER("매도인"),
    ;

    companion object {
        fun of(raw: String?): ReviewPerspective =
            entries.firstOrNull { it.name.equals(raw, ignoreCase = true) || it.label == raw } ?: NEUTRAL
    }
}

/** 계약 도구의 `ai_tool_run.tool` 코드. [com.aixnative.billing.domain.ToolPricing] 키와 같은 문자열. */
object ContractTools {
    const val REVIEW = "CONTRACT_REVIEW"
    const val REVISE = "CONTRACT_REVISE"
    const val SET_COMPARE = "CONTRACT_SET_COMPARE"
}

/** 텍스트 직접 입력 최소 길이 - 이보다 짧으면 검토할 내용이 없다. */
const val MIN_CONTRACT_TEXT_LEN = 30

/** 묶음 교차검토 대상 건수 범위. */
const val SET_COMPARE_MIN = 2
const val SET_COMPARE_MAX = 4

/**
 * 계약서 검토 요청. 원문([text])은 클라이언트가 업로드 추출(`/api/documents/extract`) 결과나
 * 붙여넣기로 채운다 - 서버는 원본 파일을 보관하지 않는다.
 */
data class ContractReviewRequest(
    /** 딜 식별자(PK). 없으면 새 딜(self-anchor)로 생성된다. */
    val dealId: Long? = null,
    val dealName: String? = null,
    @field:NotBlank(message = "계약서 원문을 입력하거나 파일에서 불러와 주세요.")
    @field:Size(min = MIN_CONTRACT_TEXT_LEN, message = "계약서 원문을 ${MIN_CONTRACT_TEXT_LEN}자 이상 입력해 주세요.")
    val text: String = "",
    /** 검토 관점. 미지정이면 중립. */
    val perspective: String? = null,
    /** 표시·감사용 원본 파일명(선택). */
    val sourceFileName: String? = null,
)

/** 수정안 요청 - 원문을 다시 받지 않고 **검토 결과 runId** 로 참조한다(테넌트 스코프가 자동 보장). */
data class ContractReviseRequest(
    val runId: Long,
    val perspective: String? = null,
)

/** 묶음 교차검토 요청 - 같은 딜에 묶인 검토 결과 2~4건. */
data class ContractSetCompareRequest(
    val runIds: List<Long> = emptyList(),
)
