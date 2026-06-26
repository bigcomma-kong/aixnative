package com.aixnative.underwriting

/**
 * CRE 분석 가이드라인·벤치마크 (한국 시장 2026 기준) — 언더라이팅·스크리닝 부분.
 * 원칙: 숫자(임계값/밴드)는 코드, 판단·서술은 AI. 가이드라인 텍스트로 프롬프트에 주입.
 * (BOV·Hold/Sell·개발타당성 가이드는 Phase 4 인접기능에서 추가 이식.)
 */
object CreGuidelines {

    /** 기준 수치 기준일 — 결과 카드 "적용 기준" 표시 + AI 프롬프트 노출. 시장 변동 시 갱신. */
    const val AS_OF = "2026 상반기"

    // ─── 투자 가이드라인 (기본값) ───
    const val MIN_IRR_CORE_PCT = 8.0
    const val MIN_IRR_VALUE_PCT = 12.0
    const val MIN_EM = 1.5
    const val MAX_LTV_PCT = 65.0
    const val MIN_DSCR = 1.25
    const val MIN_COC_PCT = 5.0

    // ─── 스크리닝 플래그 임계값 ───
    const val WALT_YELLOW_YRS = 2.0
    const val WALT_DEALBREAKER_YRS = 1.0
    const val TOP1_YELLOW_PCT = 50.0
    const val NEW_SUPPLY_YELLOW_PCT = 30.0
    const val OPEX_NORMAL_OFFICE_PCT = 30.0
    const val OPEX_NORMAL_LOGI_PCT = 15.0
    const val BUILDING_AGE_YELLOW_YRS = 25
    const val PRICE_PREMIUM_DEALBREAKER_PCT = 20.0
    const val LOSS_TO_LEASE_UPSIDE_PCT = 10.0

    // ─── 언더라이팅 기본 가정 ───
    const val ACQ_TAX_PCT = 4.6
    const val REGISTRATION_PCT = 0.5
    const val SALE_COST_MIN_PCT = 2.0
    const val SALE_COST_MAX_PCT = 3.0

    /** 스크리닝 프롬프트에 주입할 가이드라인·벤치마크·플래그 기준 */
    fun screeningGuidelineText(assetType: String?): String =
        """
        [투자 가이드라인]
        - 목표 IRR: 코어 %.1f%%+ / 밸류애드 %.1f%%+
        - 목표 Equity Multiple: %.1fx (5년 보유)
        - 최대 LTV: %.0f%%, 최소 DSCR: %.2fx, 최소 평균 Cash-on-Cash: %.1f%%

        [자산유형별 시장 벤치마크 (2026 한국)]
        %s
        - 호가 Cap Rate 가 시장 대비 -50bps 이상 낮으면 프리미엄 정당성 확인 필요

        [Dealbreaker (verdict=NO_GO 신호)]
        - 토양오염/석면 등 환경 리스크, 등기 권리분쟁(가압류·가처분)
        - WALT < %.0f년 + 핵심 임차인 만기 임박 + 갱신 불확실
        - 호가가 시장가 대비 +%.0f%% 이상

        [Yellow Flag (주의 — CONDITIONAL 가능)]
        - WALT < %.0f년 / Top1 임차 집중 ≥ %.0f%% / 향후 신규공급 ≥ %.0f%%
        - OpEx 비율 비정상(오피스 ≥ %.0f%%, 물류 ≥ %.0f%%)
        - 준공 ≥ %d년 + CapEx 미계상

        [Green w/ Note (긍정·업사이드)]
        - Loss-to-Lease ≥ %.0f%% → 갱신 시 임대료 인상 여력(업사이드)
        - Cap Rate 가 시장평균 +30bps 이상(거래부재 시 기회 가능)

        [데이터 갭 핸들링]
        - IM 에 없는 수치는 null. 추정은 "(추정)" 표기하고 confidence 낮춤.
        - 시장 벤치마크 사용 시 시점 명시. 6개월 이상 노후 데이터는 confidence=LOW.
        """.trimIndent().format(
            MIN_IRR_CORE_PCT, MIN_IRR_VALUE_PCT, MIN_EM, MAX_LTV_PCT,
            MIN_DSCR, MIN_COC_PCT, benchmarkByAsset(assetType),
            WALT_DEALBREAKER_YRS, PRICE_PREMIUM_DEALBREAKER_PCT,
            WALT_YELLOW_YRS, TOP1_YELLOW_PCT, NEW_SUPPLY_YELLOW_PCT,
            OPEX_NORMAL_OFFICE_PCT, OPEX_NORMAL_LOGI_PCT, BUILDING_AGE_YELLOW_YRS,
            LOSS_TO_LEASE_UPSIDE_PCT,
        )

    /** 언더라이팅 프롬프트/검증용 가이드라인 텍스트 */
    fun underwritingGuidelineText(assetType: String?): String =
        """
        [수익지표 기준]
        - Levered IRR: 코어 %.1f%%+ / 밸류애드 %.1f%%+
        - Equity Multiple %.1fx+ (5년), DSCR %.2fx+, LTV %.0f%% 이하, Cash-on-Cash %.1f%%+
        - 보수성: Going-in Cap < Exit Cap 권장

        [기본 가정 밴드 (자산유형별)]
        - GPR(임대료) 인상률: 오피스 2.5~3.5%%, 물류 3.0~4.0%%, 호텔 RevPAR 3~5%%
        - OpEx 인상률: 3.0~4.0%% (인건비 강세)
        - Exit Cap: 코어오피스 4.5~5.0%%, 물류 5.0~5.5%%, 호텔 7.0~8.0%%
        - 취득부대비용: 취득세 %.1f%% + 등기 %.1f%%, 매각비용 %.0f~%.0f%%

        [민감도 권장]
        - 필수: Exit Cap ±50bps, 임대료 ±50bps, 금리 ±100bps
        - 선택: 공실 ±200bps, CapEx ±20%%, 보유기간 3/5/7년

        [품질검증]
        - DSCR ≥ 1.10, LTV ≤ %.0f%%, Going-in Cap < Exit Cap, Cap Rate 2~15%% 범위

        [벤치마크] %s
        """.trimIndent().format(
            MIN_IRR_CORE_PCT, MIN_IRR_VALUE_PCT, MIN_EM, MIN_DSCR,
            MAX_LTV_PCT, MIN_COC_PCT, ACQ_TAX_PCT, REGISTRATION_PCT,
            SALE_COST_MIN_PCT, SALE_COST_MAX_PCT, MAX_LTV_PCT, benchmarkByAsset(assetType),
        )

    /**
     * 단계별 "적용 기준" 텍스트 — 결과 카드 모달용. 최상단에 기준일 표기.
     * (Phase 2: SCREENING / UNDERWRITING 만. 나머지 단계는 Phase 4.)
     */
    fun guidelineFor(stage: String?, assetType: String?): String {
        val body = when (stage ?: "") {
            "SCREENING" -> screeningGuidelineText(assetType)
            "UNDERWRITING", "IC_MEMO" -> underwritingGuidelineText(assetType)
            else -> benchmarkText(assetType)
        }
        return "[기준일: $AS_OF · 시장 변동 시 갱신 필요]\n\n$body"
    }

    /** 시장 벤치마크 + 정책 기준 요약 */
    fun benchmarkText(assetType: String?): String =
        """
        [자산유형별 시장 벤치마크 (2026 한국)]
        %s

        [정책 기준]
        - 목표 IRR: 코어 %.1f%%+ / 밸류애드 %.1f%%+, EM %.1fx+
        - 최대 LTV %.0f%%, 최소 DSCR %.2fx, 최소 CoC %.1f%%
        """.trimIndent().format(
            benchmarkByAsset(assetType), MIN_IRR_CORE_PCT, MIN_IRR_VALUE_PCT,
            MIN_EM, MAX_LTV_PCT, MIN_DSCR, MIN_COC_PCT,
        )

    private fun benchmarkByAsset(assetType: String?): String {
        val t = (assetType ?: "").lowercase()
        return when {
            t.contains("office") || t.contains("오피스") ->
                "- 오피스 PB급 Cap 4.0~4.8% / A급 4.8~5.5%, 서울코어 평당 1,200~2,500만원, Occ 88~90%+"
            t.contains("logi") || t.contains("물류") ->
                "- 물류 Class A 수도권 Cap 4.5~5.5%, 평당 350~500만원, Occ 90%+"
            t.contains("hotel") || t.contains("호텔") ->
                "- 호텔 4-5성 서울 Cap 6.0~7.5%, ADR 25만원+, Occ 72%+"
            t.contains("retail") || t.contains("리테일") ->
                "- 리테일 Cap 5.0~6.5% (입지 편차 큼), 핵심상권 우선"
            else ->
                "- 오피스 4.0~5.5% / 물류 4.5~6.0% / 호텔 6.0~8.5% (자산유형 확인 필요)"
        }
    }
}
