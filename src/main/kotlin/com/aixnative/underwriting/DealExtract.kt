package com.aixnative.underwriting

import jakarta.validation.constraints.NotBlank

/**
 * 딜 기반 진입 — 기사/딜 텍스트 → 구조화 추출. 펀드매니저가 매각 기사·딜 요약을 붙여넣으면
 * AI 가 빌딩·위치·당사자·가격·임차구조를 뽑아 분석 폼을 프리필한다(hero 진입점).
 * 추출은 진입 깔때기라 무료(인증·AI설정 게이트). 실제 분석(가격예측·언더라이팅)이 과금 단위.
 */
data class DealExtractRequest(
    @field:NotBlank(message = "분석할 기사/딜 텍스트를 입력하세요.")
    val text: String? = null,
)

/** AI 가 추출한 구조화 딜. 모르는 값은 null(추정 금지). 금액=억, 면적=평, 비율=%. */
data class DealExtract(
    val dealName: String? = null,        // 딜 식별명(예: "신한카드 을지로 본사 매각")
    val buildingName: String? = null,    // 건물명(예: "파인에비뉴 A동")
    val assetType: String? = null,       // 오피스 | 물류 | 호텔 | 리테일
    val location: String? = null,        // 권역/시군구(예: "서울 중구 을지로")
    val parcelAddress: String? = null,   // 번지 포함 주소(있으면 공시지가 조회용)
    val seller: String? = null,          // 매도자
    val buyer: String? = null,           // 매수자(확정 시)
    val preferredBidder: String? = null, // 우선협상대상자
    val dealPriceEok: Double? = null,    // 거래/예상가(억)
    val noiEok: Double? = null,          // NOI(억)
    val areaPyeong: Double? = null,      // 연면적(평)
    val marketCapPct: Double? = null,    // 언급된 Cap(%)
    val tenantSummary: String? = null,   // 임차구조 요약(예: "신한카드 sale-leaseback")
    val summary: String? = null,         // 한 줄 딜 요약
    val confidence: String? = null,      // HIGH | MEDIUM | LOW (추출 확신도)
)

/** 추출 응답 — 파싱 성공 시 [extract], 실패 시 [raw] 원문. */
data class DealExtractResponse(
    val extract: DealExtract? = null,
    val raw: String? = null,
    val provider: String,
)
