package com.aixnative.billing.domain

/**
 * 분석 1건당 크레딧 단가(가치 기반 4단계 차등). 서버 권위 - 과금·표시 모두 이 표가 단일 소스.
 * 가격 조정은 이 map 만 고친다([CreditPack] 패턴과 동일).
 *
 * 키 = 분석유형 id (백엔드 enum 이름 = 프론트 type id, 동일 문자열).
 * 누락 키는 [DEFAULT_COST] 로 폴백(신규 도구가 들어와도 무료가 되지 않게).
 */
object ToolPricing {
    const val DEFAULT_COST = 2

    private val COST: Map<String, Int> = mapOf(
        // 1 - 경량/온램프
        "UNDERWRITING_GUIDE" to 1,
        "SCREENING" to 1,
        // 1 - 자산관리(PM) 계약서 추출(온램프, 저장 프리필용)
        "LEASE_EXTRACT" to 1,
        // 2 - 공고 분석(공매·매각·입찰). 추출은 단일 패스, 비교는 이미 추출된 결과만 다룬다.
        "NOTICE_EXTRACT" to 2,
        "NOTICE_COMPARE" to 2,
        // 2 - 계약서 수정안(레드라인). 검토 결과를 받아 조항 문구만 쓰므로 검토보다 가볍다.
        "CONTRACT_REVISE" to 2,
        // 2 - 표준(공공데이터 주입 단일 패스)
        "MARKET_STUDY" to 2,
        "BUILDING_RESEARCH" to 2,
        "TAX_PRICE_DIAGNOSIS" to 2,
        "PRESALE_BRIEF" to 2,
        "AM_QUARTERLY" to 2,
        "HOLD_SELL_REFI" to 2,
        "COUNTERPARTY_DD" to 2,
        "PRICE_FORECAST" to 2,
        // 3 - 핵심 산출물
        "UNDERWRITING" to 3,
        "DEV_FEASIBILITY" to 3,
        // 3 - 계약서 검토(전문 투입 + 조항별 리스크·공란·정합성). 언더라이팅과 동급 난이도.
        "CONTRACT_REVIEW" to 3,
        // 3 - 계약 묶음 교차검토(문서 사이 정합성). 출력이 문서 수에 비례해 커진다.
        "CONTRACT_SET_COMPARE" to 3,
        // 5 - 프리미엄 심화
        "IC_MEMO" to 5,
        "BOV" to 5,
        "MARKET_RESEARCH_DEEP" to 5,
        "MARKET_DEEP_REPORT" to 5,
        // 5 - 자산관리(PM) AM 제출 보고서(포트폴리오 서술)
        "PM_AM_REPORT" to 5,
    )

    /** 분석유형 id 의 크레딧 단가. 미지정 키는 [DEFAULT_COST]. */
    fun costOf(typeId: String): Int = COST[typeId] ?: DEFAULT_COST

    /** 전체 가격표(프론트 표시용). */
    fun all(): Map<String, Int> = COST
}
