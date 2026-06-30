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

/** ProForma 계산 결과 + 시나리오 + 가이드라인 적합성 코드 판정 (무료, AI 미사용). */
data class ProFormaResponse(
    val proForma: ProFormaCalculator.Result,
    val scenarios: List<ProFormaCalculator.Scenario>,
    val guidelineChecks: GuidelineEvaluator.Summary,
    val disclaimer: String,
)

/** 분석 결과 = ProForma(코드 확정) + 선택 단계의 AI 분석. 1 크레딧 소비. */
data class UnderwriteResponse(
    val runId: Long,
    /** 실행된 분석 단계 (AnalysisType.name). */
    val analysisType: String = AnalysisType.UNDERWRITING.name,
    val proForma: ProFormaCalculator.Result,
    val scenarios: List<ProFormaCalculator.Scenario>,
    /** 가이드라인 적합성 코드 판정(임계값 대조). AI 서술의 근거. */
    val guidelineChecks: GuidelineEvaluator.Summary,
    /** AI 가 반환한 구조화 내러티브(JSON). 파싱 실패 시 null 이고 [analysisRaw] 에 원문. */
    val analysis: JsonNode? = null,
    val analysisRaw: String? = null,
    val provider: String,
    val creditBalance: Int,
    val disclaimer: String,
)

/**
 * 중복 분석 사전 확인 결과(과금 없음). 동일 입력으로 [withinMinutes] 분 내 같은 단계를 이미
 * 실행했는지 알려, 프런트가 재실행 전 사용자에게 확인을 받도록 한다.
 */
data class DuplicateCheckResponse(
    val duplicate: Boolean,
    val lastRunId: Long? = null,
    val lastRunAt: Instant? = null,
    val withinMinutes: Long,
)

/** 분석 이력 목록 항목. */
data class RunSummary(
    val id: Long,
    val dealName: String?,
    val tool: String,
    val status: String,
    val createdAt: Instant?,
)

/** 한 딜의 단계별 최신 성공 결과 1건 — 합본 화면(탭)용. result 는 저장된 결과 JSON(= RunResult). */
data class DealStage(
    val analysisType: String,
    val runId: Long,
    val request: JsonNode?,
    val result: JsonNode?,
)

/** 한 딜에 대해 완료된 파이프라인 단계 모음(스크리닝·시장조사·언더라이팅·투심). 단계별 1건(최신 성공). */
data class DealStagesResponse(
    val dealName: String?,
    val stages: List<DealStage>,
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
