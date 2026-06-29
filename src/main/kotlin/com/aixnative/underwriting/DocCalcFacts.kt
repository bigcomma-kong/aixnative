package com.aixnative.underwriting

import com.aixnative.integration.marketdata.CompStats

/**
 * BOV·개발타당성·가격예측의 코드 확정 수치(결정론적 계산)를 프롬프트 <DATA> 에 주입할 한국어 facts
 * 블록으로 변환한다. AI 는 이 수치를 인용만 하고 창작하지 않는다(환각 차단, 평가수치 신뢰).
 * 사용자 자유 텍스트(documentText)가 있으면 facts 뒤에 [추가 컨텍스트] 로 덧붙인다.
 */
object DocCalcFacts {

    /** 가격 예측(소득환원+거래사례) 결과 → facts. 매입/매각 밴드·신뢰도 포함. */
    fun priceFacts(
        input: PriceForecastInput,
        marketCapPct: Double,
        stats: CompStats?,
        result: PriceEstimator.Result,
        userText: String?,
    ): String {
        val sb = StringBuilder()
        sb.appendLine("[코드 산출 가격 예측 — 확정 수치(억원/%), 창작 금지]")
        sb.appendLine("· 입력: NOI ${input.noiEok ?: "미입력"} · 시장 Cap ${marketCapPct}% · 연면적 ${input.areaPyeong ?: "미입력"}평")
        if (stats != null) sb.appendLine("· 실거래 중위 평당가: ${stats.medianPyeongManwon}만원/평 (상업 실거래 표본 ${stats.count}건)")
        else sb.appendLine("· 실거래 평당가: 위치 미해석/사례 없음 → 거래사례법 미사용")
        result.incomeValueEok?.let { sb.appendLine("· 소득환원가(NOI/Cap): $it") }
        result.compValueEok?.let { sb.appendLine("· 거래사례가(중위 평당가×연면적): $it") }
        sb.appendLine("· 추정가(가중): ${result.estimateEok} · Implied Cap ${result.impliedCapPct}%")
        sb.appendLine("· 적정 매입가 밴드: ${result.buyLowEok} ~ ${result.buyHighEok} (추정가 이하 입찰)")
        sb.appendLine("· 예상 매각가 밴드: ${result.sellLowEok} ~ ${result.sellHighEok}")
        sb.appendLine("· 신뢰도(코드): ${result.confidence} — 표본·방법 수 기준")
        appendUserText(sb, userText)
        return sb.toString().trimEnd()
    }

    /** BOV 3-Method 평가 결과 → facts. 사용된 가정(할인율·Exit Cap 보정값 포함)도 함께 표기. */
    fun bovFacts(input: BovInput, used: BovValuator.Inputs, result: BovValuator.Result, userText: String?): String {
        val sb = StringBuilder()
        sb.appendLine("[코드 산출 3-Method 평가 — 확정 수치(억원/%), 창작 금지]")
        sb.appendLine("· 입력 가정: 안정화 NOI ${used.noiEok}, 시장 Cap ${used.marketCapPct}%, " +
            "DCF 할인율 ${used.discountRatePct}%, Exit Cap ${used.exitCapPct}%, " +
            "보유 ${used.holdYears}년, 임대성장률 ${used.rentGrowthPct}%" +
            if (used.salesCompValueEok > 0) ", 비교거래가 ${used.salesCompValueEok}" else " (비교거래 미입력→Sales Comp 제외)")
        sb.appendLine("· Method 1 Direct Cap: ${result.directCapValueEok}")
        sb.appendLine("· Method 2 DCF(무차입): ${result.dcfValueEok}")
        sb.appendLine("· Method 3 Sales Comp: ${if (result.salesCompValueEok > 0) result.salesCompValueEok.toString() else "미사용"}")
        sb.appendLine("· Blended BOV(가중 .4/.3/.3, 가용분 정규화): ${result.bovValueEok}")
        sb.appendLine("· 가격 범위 Low/High(±7.5%): ${result.lowEok} ~ ${result.highEok}")
        sb.appendLine("· Implied Cap(NOI/BOV): ${result.impliedCapPct}%")
        appendUserText(sb, userText)
        return sb.toString().trimEnd()
    }

    /** 개발 타당성 수익성 결과 → facts. 가이드라인 임계(마진·YoC) 충족 여부 단서 포함. */
    fun devFacts(input: DevFeasibilityInput, result: DevFeasibilityCalculator.Result, userText: String?): String {
        val sb = StringBuilder()
        sb.appendLine("[코드 산출 개발 수익성 — 확정 수치(억원/%), 창작 금지]")
        sb.appendLine("· 사업비 구성: 토지 ${input.landCostEok} + 공사 ${input.constructionCostEok} + " +
            "금융 ${input.financingCostEok} + 기타 ${input.otherCostEok} = 기초 ${result.baseCostEok}")
        sb.appendLine("· 우발비(${input.contingencyPct}%): ${result.contingencyEok} → 총사업비 ${result.totalProjectCostEok}")
        if (result.stabilizedValueEok > 0) {
            sb.appendLine("· Stabilized Value(Year1 NOI ${input.stabilizedNoiEok} / Exit Cap ${input.exitCapPct}%): ${result.stabilizedValueEok}")
        }
        val gdvSrc = if (input.salesRevenueEok > 0) "분양수입" else "Stabilized Value"
        sb.appendLine("· 자산가치(GDV, $gdvSrc 기준): ${result.grossDevelopmentValueEok}")
        sb.appendLine("· 개발이익(GDV−총사업비): ${result.developmentProfitEok}")
        sb.appendLine("· Development Margin(=Profit on Cost): ${result.profitMarginPct}% " +
            "(가이드라인 ≥${CreGuidelines.MIN_DEV_MARGIN_PCT}% → 코드 판정 ${result.marginVerdict})")
        if (result.yieldOnCostPct > 0) sb.appendLine("· Yield-on-Cost(NOI/총사업비): ${result.yieldOnCostPct}%")
        sb.appendLine("· 민감도(마진%/개발이익):")
        result.sensitivity.forEach { sb.appendLine("    - ${it.label}: ${it.profitMarginPct}% / ${it.developmentProfitEok}") }
        appendUserText(sb, userText)
        return sb.toString().trimEnd()
    }

    private fun appendUserText(sb: StringBuilder, userText: String?) {
        userText?.takeIf { it.isNotBlank() }?.let {
            sb.appendLine()
            sb.appendLine("[추가 컨텍스트 — 사용자 입력]")
            sb.appendLine(it.trim())
        }
    }
}
