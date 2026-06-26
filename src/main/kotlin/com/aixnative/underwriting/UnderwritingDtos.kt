package com.aixnative.underwriting

import com.fasterxml.jackson.databind.JsonNode
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Positive
import jakarta.validation.constraints.PositiveOrZero
import java.time.Instant

/**
 * 언더라이팅 입력. 단위: 억원·%·년. ProForma 는 순수 계산이라 외부 API 불필요.
 * 선택 항목은 자산유형 통상값 기본 적용.
 */
data class UnderwriteRequest(
    val dealName: String? = null,
    val assetType: String? = null,
    /** 권역/입지 (예: 서울 GBD, 판교). 시장조사·스크리닝 컨텍스트. 선택. */
    val location: String? = null,
    /** 자유 메모(IM 요약·임대 현황·특이사항 등). 분석 컨텍스트로 전달. 선택. */
    val notes: String? = null,

    @field:NotNull(message = "매입가는 필수입니다.")
    @field:Positive(message = "매입가는 0보다 커야 합니다.")
    val askingPriceEok: Double? = null,

    @field:NotNull(message = "NOI는 필수입니다.")
    @field:Positive(message = "NOI는 0보다 커야 합니다.")
    val noiEok: Double? = null,

    @field:NotNull(message = "LTV는 필수입니다.")
    @field:PositiveOrZero(message = "LTV는 0 이상이어야 합니다.")
    val ltvPct: Double? = null,

    @field:NotNull(message = "대출금리는 필수입니다.")
    @field:PositiveOrZero
    val loanRatePct: Double? = null,

    @field:NotNull(message = "Exit Cap은 필수입니다.")
    @field:Positive(message = "Exit Cap은 0보다 커야 합니다.")
    val exitCapPct: Double? = null,

    val holdYears: Int = 5,
    val rentGrowthPct: Double = 3.0,
    val acqCostPct: Double = CreGuidelines.ACQ_TAX_PCT + CreGuidelines.REGISTRATION_PCT,
    val saleCostPct: Double = CreGuidelines.SALE_COST_MIN_PCT,
    val capexPerYearEok: Double = 0.0,
) {
    /** 검증된 요청을 계산 입력으로 변환 (null 은 @Valid 단계에서 차단됨). */
    fun toInputs(): ProFormaCalculator.Inputs = ProFormaCalculator.Inputs(
        askingPriceEok = askingPriceEok!!,
        noiEok = noiEok!!,
        ltvPct = ltvPct!!,
        loanRatePct = loanRatePct!!,
        holdYears = holdYears,
        rentGrowthPct = rentGrowthPct,
        exitCapPct = exitCapPct!!,
        acqCostPct = acqCostPct,
        saleCostPct = saleCostPct,
        capexPerYearEok = capexPerYearEok,
    )
}

/** ProForma 계산 결과 + 시나리오 (무료, AI 미사용). */
data class ProFormaResponse(
    val proForma: ProFormaCalculator.Result,
    val scenarios: List<ProFormaCalculator.Scenario>,
    val disclaimer: String,
)

/** 분석 결과 = ProForma(코드 확정) + 선택 단계의 AI 분석. 1 크레딧 소비. */
data class UnderwriteResponse(
    val runId: Long,
    /** 실행된 분석 단계 (AnalysisType.name). */
    val analysisType: String = AnalysisType.UNDERWRITING.name,
    val proForma: ProFormaCalculator.Result,
    val scenarios: List<ProFormaCalculator.Scenario>,
    /** AI 가 반환한 구조화 내러티브(JSON). 파싱 실패 시 null 이고 [analysisRaw] 에 원문. */
    val analysis: JsonNode? = null,
    val analysisRaw: String? = null,
    val provider: String,
    val creditBalance: Int,
    val disclaimer: String,
)

/** 분석 이력 목록 항목. */
data class RunSummary(
    val id: Long,
    val dealName: String?,
    val tool: String,
    val status: String,
    val createdAt: Instant?,
)

/** 분석 이력 상세 — 저장된 입력/결과 JSON 포함. */
data class RunDetail(
    val id: Long,
    val dealName: String?,
    val tool: String,
    val status: String,
    val createdAt: Instant?,
    val request: JsonNode?,
    val result: JsonNode?,
)
