package com.aixnative.underwriting.domain

/**
 * IM 분석 단계(투자 분석 파이프라인). 각 단계는 AI 1회 호출 = 1 크레딧.
 * [tool] 은 ai_tool_run.tool 에 저장되는 식별자, [label] 은 UI 표기.
 */
enum class AnalysisType(val tool: String, val label: String) {
    /** 1차 스크리닝 — 지표 대조 + Red/Green Flag + Go/No-Go. */
    SCREENING("DEAL_SCREENING", "1차 스크리닝"),

    /** 시장조사 — 권역 분석 + 매입 가정 검증 + House View. */
    MARKET_STUDY("MARKET_STUDY", "시장조사"),

    /** 언더라이팅 — ProForma 확정 수치 기반 결론 내러티브. */
    UNDERWRITING("UNDERWRITING_NARRATIVE", "언더라이팅"),

    /** 투심 메모 — 앞 단계 종합 → IC 상정용 메모. */
    IC_MEMO("IC_MEMO", "투심 메모"),
    ;

    companion object {
        /** 보고서 조립·UI 표시 순서(스크리닝 → 시장조사 → 언더라이팅 → 투심). */
        val PIPELINE: List<AnalysisType> = listOf(SCREENING, MARKET_STUDY, UNDERWRITING, IC_MEMO)

        fun fromTool(tool: String): AnalysisType? = entries.firstOrNull { it.tool == tool }
    }
}
