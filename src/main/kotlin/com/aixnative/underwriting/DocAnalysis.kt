package com.aixnative.underwriting

import com.fasterxml.jackson.databind.JsonNode
import jakarta.validation.Valid

/**
 * 문서/텍스트 기반 분석 단계(매입 트랙 추가분 + 신규 트랙 B~E).
 * 구조화 ProForma 입력이 아니라 자유 텍스트(자산·운영·토지 설명 등)를 근거로 한다.
 * [stage] 는 CreGuidelines.guidelineFor 의 단계 키, [outputKind] 는 프론트 렌더 힌트.
 */
enum class DocAnalysisType(
    val tool: String,
    val label: String,
    val stage: String,
    val outputKind: String,
) {
    /** 언더라이팅 입력가이드 — 권장 가정 선제안(recommend{}). */
    UNDERWRITING_GUIDE("UNDERWRITING_GUIDE", "언더라이팅 입력가이드", "UNDERWRITING", "recommend"),

    /** 건물 검색 — 공개 벤치마크 기반 예비 IM(im_markdown). */
    BUILDING_RESEARCH("BUILDING_RESEARCH", "건물 검색(예비 IM)", "MARKET_STUDY", "markdown"),

    /** 세무·가격 진단 — 합법 절세 포인트 + 매입가 적정성(guides[]). */
    TAX_PRICE_DIAGNOSIS("TAX_PRICE_DIAGNOSIS", "세무·가격 진단", "UNDERWRITING", "guides"),

    /** 매각 BOV — 평가·가격범위·매각방식(sections). */
    BOV("BOV_NARRATIVE", "매각 BOV", "BOV", "sections"),

    /** 분기 자산보고 — 운영 실적·KPI·Variance(sections). */
    AM_QUARTERLY("AM_QUARTERLY", "분기 자산보고", "QUARTERLY", "sections"),

    /** 보유·매각·리파이 결정 — 4-시나리오 + 결정규칙(sections). */
    HOLD_SELL_REFI("HOLD_SELL_REFI", "보유·매각·리파이", "HOLD_SELL", "sections"),

    /** 개발 타당성 — 사업비·마진·인허가·PF(sections). */
    DEV_FEASIBILITY("DEV_FEASIBILITY", "개발 타당성", "FEASIBILITY", "sections"),

    /** 심화 시장리서치 — 권역·매크로·하우스뷰(sections). */
    MARKET_RESEARCH_DEEP("MARKET_RESEARCH_DEEP", "심화 시장리서치", "DEEP_RESEARCH", "sections"),

    /** 거래상대방 실사 — 사업자상태·제재·기업정보·규모(sections). 입력=사업자번호(+상호). */
    COUNTERPARTY_DD("COUNTERPARTY_DD", "거래상대방 실사", "DD", "sections"),

    /** 매입·매각 가격 예측 — 소득환원+거래사례 밴드(sections). 입력=NOI/연면적/시장Cap(+위치). */
    PRICE_FORECAST("PRICE_FORECAST", "매입·매각 가격 예측", "UNDERWRITING", "sections"),
    ;

    companion object {
        fun fromTool(tool: String): DocAnalysisType? = entries.firstOrNull { it.tool == tool }
    }
}

/**
 * 문서/텍스트 기반 분석 요청. [documentText] = 자산·운영·토지 등 분석 대상 자유 텍스트.
 * BOV·DEV_FEASIBILITY 단계는 구조화 숫자 입력([bov]/[dev])을 받아 코드로 핵심 수치를 확정하고
 * 그 결과를 <DATA> 에 주입한다(정밀화). 그 외 단계는 documentText 가 필수.
 * 검증은 단계별로 서비스에서 수행한다(union 이라 bean validation 으로 표현 어려움).
 */
data class DocAnalyzeRequest(
    val dealName: String? = null,
    val assetType: String? = null,
    val location: String? = null,
    val documentText: String? = null,
    @field:Valid val bov: BovInput? = null,
    @field:Valid val dev: DevFeasibilityInput? = null,
    /** 가격 예측(PRICE_FORECAST) 입력 — NOI·연면적·시장Cap(선택). 위치는 location 사용. */
    @field:Valid val forecast: PriceForecastInput? = null,
    /** 거래상대방 실사(COUNTERPARTY_DD) 입력 — 사업자번호(필수)·상호(선택). */
    val bizNo: String? = null,
    val counterpartyName: String? = null,
    /** 필지 주소(번지 포함, 예: "서울 강남구 역삼동 736-1") — 공시지가·용도지역 조회용(선택). */
    val parcelAddress: String? = null,
)

/** 매각 BOV 3-Method 평가 입력. 할인율·Exit Cap 미입력 시 자산유형 기본값(CreGuidelines)으로 보정. */
data class BovInput(
    val noiEok: Double,
    val marketCapPct: Double,
    val discountRatePct: Double? = null,
    val exitCapPct: Double? = null,
    val holdYears: Int = 5,
    val rentGrowthPct: Double = 3.0,
    val salesCompValueEok: Double = 0.0,
)

/**
 * 가격 예측 입력. NOI+시장Cap → 소득환원, 연면적+실거래 → 거래사례. 둘 중 하나만 있어도 산출(신뢰도 차등).
 * 시장Cap 미입력 시 자산유형 기본값(CreGuidelines)으로 보정. 거래사례 평당가는 위치(location)로 자동 조회.
 */
data class PriceForecastInput(
    val noiEok: Double? = null,
    val marketCapPct: Double? = null,
    val areaPyeong: Double? = null,
)

/** 개발 타당성 수익성 입력. GDV = 분양수입(있으면) 또는 Stabilized Value(NOI/ExitCap). */
data class DevFeasibilityInput(
    val landCostEok: Double,
    val constructionCostEok: Double,
    val financingCostEok: Double = 0.0,
    val otherCostEok: Double = 0.0,
    val contingencyPct: Double = 5.0,
    val stabilizedNoiEok: Double = 0.0,
    val exitCapPct: Double = 0.0,
    val salesRevenueEok: Double = 0.0,
)

/**
 * 문서 기반 분석 응답 — ProForma 없는 단계 공용(analysis 는 단계별 스키마 JSON).
 * [calc] 는 BOV/DEV 단계의 코드 확정 수치(결정론적 계산 결과). 그 외 단계는 null.
 */
data class DocAnalyzeResponse(
    val runId: Long,
    val analysisType: String,
    val analysis: JsonNode? = null,
    val analysisRaw: String? = null,
    val calc: JsonNode? = null,
    /** 분석에 주입된 실측 시장데이터(공공 API). 프론트에서 "실측·확정" 카드로 노출. 없으면 빈 리스트. */
    val marketFacts: List<MarketFact> = emptyList(),
    val provider: String,
    val creditBalance: Int,
    val disclaimer: String,
)

/** 사용자에게 보여줄 실측 시장 사실 한 건. source=출처 헤더, detail=값(요약). */
data class MarketFact(
    val source: String,
    val detail: String,
)
