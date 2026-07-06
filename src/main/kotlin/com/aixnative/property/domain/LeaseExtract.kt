package com.aixnative.property.domain

/**
 * AI 가 임대차 계약서에서 추출한 구조화 필드. 모르는 값은 null(추정·창작 금지).
 * 금액=만원, 면적=평, 비율=%, 날짜=ISO(yyyy-MM-dd). 사용자가 검토·수정 후 [Lease] 로 저장한다.
 * 계약서 프리필용이라 정확·보수적이어야 한다(틀린 값은 잘못된 관리로 전파).
 */
data class LeaseExtract(
    val tenantName: String? = null,        // 임차인명
    val unitLabel: String? = null,         // 층/호
    val areaPyeong: Double? = null,        // 임대면적(평)
    val monthlyRentManwon: Double? = null, // 월 임대료(만원)
    val depositManwon: Double? = null,     // 보증금(만원)
    val mgmtFeeManwon: Double? = null,     // 월 관리비(만원)
    val leaseStartDate: String? = null,    // yyyy-MM-dd
    val leaseEndDate: String? = null,      // yyyy-MM-dd
    val rentFreeMonths: Int? = null,       // 렌트프리(개월)
    val escalationPct: Double? = null,     // 임대료 인상률(%)
    val nextEscalationDate: String? = null,// yyyy-MM-dd
    val notes: String? = null,             // 특약·비고 요약
    val confidence: String? = null,        // HIGH | MEDIUM | LOW (추출 확신도)
)
