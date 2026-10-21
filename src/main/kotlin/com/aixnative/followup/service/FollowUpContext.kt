package com.aixnative.followup.service

import com.aixnative.followup.domain.FollowUpRole
import com.aixnative.followup.domain.FollowUpTurn

/**
 * 후속 논의 프롬프트 컨텍스트 조립(순수 로직, 무상태).
 *
 * <분석 결과> + <참고 결과>(같은 딜의 다른 단계, 단계별 최신 1건) + <이전 논의>(최근 N턴).
 * 참고 결과를 넣는 이유: 투심 메모에서 "어느 가격대부터 검토 가능한가"를 물으면 민감도 표는
 * 언더라이팅 단계에 있어, 주 결과만으로는 모델이 거절로 흐른다(레거시 운영 실측).
 * 넣는 것은 이미 저장된 결과뿐이라 "새 데이터 조회 없음" 원칙은 그대로다.
 */
object FollowUpContext {

    /** 주 결과 상한(자) - 토큰 폭증 방지. */
    const val SUBJECT_MAX = 8000

    /** 참고 결과 1건당 상한(자) - 핵심 수치는 보통 앞부분(결론·요약)에 있다. */
    const val SIBLING_EACH_MAX = 3500

    /** 참고 결과 전체 상한(자). */
    const val SIBLING_TOTAL_MAX = 10000

    /** 프롬프트에 넣는 이전 턴 수 상한 - 대화가 길어져도 비용이 무한정 늘지 않게. */
    const val MAX_PRIOR_TURNS = 12

    /** 참고 결과로 넣는 단계 순서·라벨(ai_tool_run.tool 기준). 목록에 없는 도구는 참고로 넣지 않는다. */
    val STAGE_LABELS: Map<String, String> = linkedMapOf(
        "DEAL_SCREENING" to "1차 스크리닝",
        "MARKET_STUDY" to "시장조사",
        "UNDERWRITING_NARRATIVE" to "언더라이팅",
        "IC_MEMO" to "투심 메모",
        "BOV_NARRATIVE" to "매각 BOV",
        "PRICE_FORECAST" to "가격 예측",
        "TAX_PRICE_DIAGNOSIS" to "세무·가격 진단",
        "HOLD_SELL_REFI" to "보유·매각·리파이",
        "DEV_FEASIBILITY" to "개발 타당성",
        "MARKET_RESEARCH_DEEP" to "심화 시장리서치",
        "COUNTERPARTY_DD" to "거래상대방 실사",
        "AM_QUARTERLY" to "분기 자산보고",
    )

    /**
     * @param subjectTool 주 결과의 도구(같은 도구는 참고에서 제외 - 이미 주 결과다)
     * @param subjectJson 주 결과 JSON 문자열
     * @param siblings 같은 딜의 단계별 최신 성공 결과(tool → resultJson)
     * @param prior 이전 논의 턴(순서대로)
     */
    fun build(
        subjectTool: String,
        subjectJson: String,
        siblings: Map<String, String>,
        prior: List<FollowUpTurn>,
    ): String {
        val sb = StringBuilder()
        sb.append("<분석 결과>\n").append(cap(subjectJson, SUBJECT_MAX)).append("\n</분석 결과>")

        val ref = siblingBlock(subjectTool, siblings)
        if (ref.isNotEmpty()) sb.append("\n\n<참고 결과>\n").append(ref).append("</참고 결과>")

        val recent = prior.filter { it.role != FollowUpRole.CRITIQUE }.takeLast(MAX_PRIOR_TURNS)
        if (recent.isNotEmpty()) {
            sb.append("\n\n<이전 논의>\n")
            recent.forEach { sb.append("- ").append(roleLabel(it.role)).append(": ").append(it.content).append('\n') }
            sb.append("</이전 논의>")
        }
        return sb.toString()
    }

    private fun siblingBlock(subjectTool: String, siblings: Map<String, String>): String {
        val sb = StringBuilder()
        var budget = SIBLING_TOTAL_MAX
        for ((tool, label) in STAGE_LABELS) {
            if (budget <= 0) break
            if (tool == subjectTool) continue
            val text = siblings[tool]?.takeIf { it.isNotBlank() } ?: continue
            val piece = cap(text, minOf(SIBLING_EACH_MAX, budget))
            sb.append('[').append(label).append("]\n").append(piece).append("\n\n")
            budget -= piece.length
        }
        return sb.toString()
    }

    fun roleLabel(role: FollowUpRole): String = when (role) {
        FollowUpRole.USER -> "사용자 질문"
        FollowUpRole.SUGGEST -> "AI 제안"
        FollowUpRole.AI -> "AI 답변"
        FollowUpRole.CRITIQUE -> "반박 검토"
    }

    fun cap(s: String, max: Int): String = if (s.length > max) s.substring(0, max) + "…" else s
}
