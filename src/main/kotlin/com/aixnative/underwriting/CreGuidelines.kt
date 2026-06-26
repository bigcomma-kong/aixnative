package com.aixnative.underwriting

/**
 * CRE 분석 가이드라인·벤치마크 (한국 시장 2026 기준) — 단일 소스.
 * 매입 트랙(스크리닝·언더라이팅) + 신규 트랙(BOV·보유/매각/리파이·개발타당성) 가이드라인을 코드화해
 * 프롬프트에 가이드라인 텍스트로 주입한다. 원칙: 숫자(임계값/밴드)는 코드, 판단·서술은 AI.
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

    // ─── Hold/Sell/Refi 기준 ───
    const val REFI_DSCR = 1.25 // 리파이 시
    const val OPERATION_DSCR = 1.30 // 운영단계
    const val SELL_IRR_SPREAD_BPS = 200.0 // SELL: IRR-Sell > IRR-Hold + 200bps
    const val REFI_IRR_IMPROVE_BPS = 100.0 // REFI: IRR 개선 > 100bps

    // ─── 개발 타당성 기준 ───
    const val MIN_DEV_MARGIN_PCT = 15.0 // Development Margin
    const val MIN_DEV_IRR_PCT = 12.0 // Development IRR 하한 (12~15%)
    const val MATERIAL_INFLATION_MIN_PCT = 3.0
    const val MATERIAL_INFLATION_MAX_PCT = 5.0

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

    // ════════════════════════════════════════════════════════════════════
    // 신규 트랙 가이드라인 텍스트
    // ════════════════════════════════════════════════════════════════════

    /** 매각 BOV 가이드라인 — 할인율 밴드·매각방식·매도자 우선순위·Quick-Hit 리스크 */
    fun bovGuidelineText(assetType: String?): String =
        """
        [매각 BOV 가이드라인 (한국 2026)]
        - 자산유형별 DCF 할인율: 코어오피스 7.5~8.5%, 코어물류 8.0~9.0%, 호텔 9.5~11.0%
        - Exit Cap: 시장 Cap + 25~50bps (보수적)
        - 3-Method 가중: Direct Cap 40% / DCF 30% / Sales Comp 30% (데이터 가용성 따라 정규화)
        - 가격범위: Low(빠른클로징, -할인) / Base(시장가) / High(경쟁입찰)

        [매각 방식 매트릭스]
        - Off-market 수의계약: 3~4개월, 가격 -5~10% (확실성·신속성)
        - Limited 입찰(3~5사): 5~6개월, 시장가 (가격발견+확실성 균형)
        - Open 입찰(10사+): 6~8개월, +5~10% (가격 극대화)
        - 한국 표준 일정: LOI 1~2M → 우선협상 2~3주 → MOU+DD 4~6주 → PSA 2~4주 → Closing 2~4주 = 총 4~6M

        [매도자 우선순위 매트릭스]
        가격최대화(LP IRR) / 확실성(펀드만기·분배) / 속도(시장변동회피) / 임차인유지 / 세무효율 — 우선순위에 따라 방식·가격가이드 조정

        [Quick-Hit 리스크 체크리스트]
        - 등기 권리관계 정리(가압류·가처분), 임차인 estoppel, 부채 조기상환 페널티
        - 양도소득세+VAT 사전계산, 매도자 진술·보장(R&W), 환경·법무 리스크

        [데이터 갭]
        - 거래사례 부재 → Sales Comp 가중 축소, DCF+Direct Cap 확대
        - Stabilized NOI 불확실 → 12M Trailing + Forward 12M 평균
        - 조기상환 페널티 미공개 → 5~10% 가정
        """.trimIndent()

    /** BOV DCF 할인율 기본값 (자산유형별 밴드 중앙값, %) */
    fun bovDefaultDiscountPct(assetType: String?): Double {
        val t = (assetType ?: "").lowercase()
        return when {
            t.contains("hotel") || t.contains("호텔") -> 10.0
            t.contains("logi") || t.contains("물류") -> 8.5
            t.contains("retail") || t.contains("리테일") -> 8.0
            else -> 8.0 // 오피스 기본
        }
    }

    /** BOV Exit Cap 기본값 (자산유형별, %) */
    fun bovDefaultExitCapPct(assetType: String?): Double {
        val t = (assetType ?: "").lowercase()
        return when {
            t.contains("hotel") || t.contains("호텔") -> 7.5
            t.contains("logi") || t.contains("물류") -> 5.25
            t.contains("retail") || t.contains("리테일") -> 5.75
            else -> 4.75 // 오피스 코어
        }
    }

    /** 보유·매각·리파이 결정 가이드라인 */
    fun holdSellRefiGuidelineText(): String =
        """
        [Hold/Sell/Refi 결정 가이드라인]
        - 4-시나리오 비교: Hold / Refi+Hold / Sell-Current / Sell-Stabilized — 각 IRR-to-Date·EM·DSCR
        - 조기상환 비용: PF 1~3%%, 메자닌 5~10%% / 매각 friction 2~4%%
        - DSCR 기준: Refi 시 ≥ %.2fx, 운영단계 ≥ %.2fx

        [NOI Peak 정합성 게이트]
        - 현재 NOI 피크를 Permanent / Timing / One-time 으로 분류 → Permanent 만 forward 에 반영

        [결정 규칙]
        - SELL NOW: IRR-Sell > IRR-Hold + %.0fbps + EM 순위 일치 + 외부 매각신호
        - REFI+HOLD: Cash-out 가능 + DSCR ≥ %.2fx + IRR 개선 > %.0fbps
        - LP vs GP 정합성: IRR 순위와 EM 순위 불일치 시 워터폴(Promote hurdle ±50~100bps) 영향 설명

        [민감도] Exit Cap ±50~100bps / 임대료 ±50bps / 금리 ±100bps
        """.trimIndent().format(REFI_DSCR, OPERATION_DSCR, SELL_IRR_SPREAD_BPS, REFI_DSCR, REFI_IRR_IMPROVE_BPS)

    /** 개발사업 타당성 가이드라인 */
    fun devFeasibilityGuidelineText(assetType: String?): String =
        """
        [개발 타당성 가이드라인 (한국 2026)]
        - 총사업비 구성: 토지(매입+취득세+등기+멸실/보상) + 공사(직공+부대+설계감리+인플레) + 금융비(PF이자+수수료+보증료) + 마케팅/기타 + 우발비 5~10%%
        - 평당 공사비(만원): 오피스PB 850~1,100 / 오피스A 700~900 / 물류A 280~360 / 콜드체인 450~600 / 호텔4-5성 1,500~2,200 / 호텔비즈 900~1,400 / 리테일 800~1,200
        - 자재비 인플레: 연 %.0f~%.0f%% (개발기간 누적, 예: 30개월 → +8~12%%)
        - 신축 Stabilized Exit Cap: 오피스PB 4.2~4.8%%, 물류A 4.7~5.5%%, 호텔4-5성 6.5~8.0%%

        [수익성 기준]
        - Development Margin ≥ %.0f%% / Development IRR %.0f~15%% / Yield-on-Cost ≥ 시장Cap +100bps / Profit on Cost = (Stabilized - 총사업비)

        [PF 구조 검증]
        - Sponsor Equity 25~35%%, Mezzanine 0~15%%, Senior 50~70%%
        - 사업단계 DSCR ≥ 1.20, 운영단계 ≥ %.2fx, LTC ≤ 70%%, LTV(Stabilized) ≤ %.0f%%

        [인허가 일정(기본+버퍼)]
        - 건축심의 3~6M, 환경영향평가 6~12M, 교통영향평가 3~6M, 건축허가 2~4M, 착공 1M, 시공 18~48M, 사용승인 2~3M, 안정화 6~24M
        - 특별: 호텔(관광사업등록+식품접객 +2~4M) / 물류(물류단지지정+화물운수) / 대형오피스(사전환경성+광역교통영향평가)
        - 일정 불확실 시 최악/평균/최선 3-시나리오

        [GO / CONDITIONAL / NO-GO]
        - GO: Margin ≥ %.0f%% + 인허가 경로 명확 + PF 합리(Equity 30%%·DSCR ≥ 1.2) + 리스크 완화가능
        - CONDITIONAL: Margin 10~%.0f%% + 1~2개 인허가 변수
        - NO-GO: Margin < 10%% + 핵심 인허가 불확실 + PF 비합리
        """.trimIndent().format(
            MATERIAL_INFLATION_MIN_PCT, MATERIAL_INFLATION_MAX_PCT,
            MIN_DEV_MARGIN_PCT, MIN_DEV_IRR_PCT, OPERATION_DSCR, MAX_LTV_PCT,
            MIN_DEV_MARGIN_PCT, MIN_DEV_MARGIN_PCT,
        )

    /**
     * 단계별 "적용 기준" 텍스트 — 결과 카드 모달용. 최상단에 기준일 표기.
     * 매입 트랙 + 신규 트랙(BOV/HOLD_SELL/FEASIBILITY) 전부 커버.
     */
    fun guidelineFor(stage: String?, assetType: String?): String {
        val body = when (stage ?: "") {
            "SCREENING" -> screeningGuidelineText(assetType)
            "UNDERWRITING", "IC_MEMO" -> underwritingGuidelineText(assetType)
            "BOV" -> bovGuidelineText(assetType)
            "HOLD_SELL" -> holdSellRefiGuidelineText()
            "FEASIBILITY" -> devFeasibilityGuidelineText(assetType)
            else -> benchmarkText(assetType) // MARKET_STUDY / DEEP_RESEARCH / QUARTERLY 등
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
