package com.aixnative.underwriting.domain

/**
 * 가이드라인 적합성 — 결정론적 판정. ProForma 코드 산출 수치를 [CreGuidelines] 임계값과 비교해
 * PASS/WARN/FAIL 로 분류한다. 임계값 비교는 "숫자 연산"이므로 코드가 확정하고, AI 는 그 판정을
 * 근거로 서술만 한다(임계값 판단을 AI 에 맡기지 않음 → 일관성·신뢰성 ↑).
 */
object GuidelineEvaluator {

    enum class Status { PASS, WARN, FAIL }

    data class Check(
        val metric: String,
        val actual: String,
        val threshold: String,
        val status: Status,
    )

    data class Summary(val checks: List<Check>, val pass: Int, val warn: Int, val fail: Int)

    /** ProForma 결과를 가이드라인 임계값과 대조한 판정 목록. */
    fun evaluate(input: ProFormaCalculator.Inputs, r: ProFormaCalculator.Result): Summary {
        val minDscr = r.proForma.mapNotNull { it.dscr.takeIf { d -> d > 0 } }.minOrNull() ?: 0.0
        val avgCoc = r.proForma.map { it.cocPct }.takeIf { it.isNotEmpty() }?.average() ?: 0.0

        val checks = listOf(
            band(
                "Levered IRR", r.leveredIrrPct, "%",
                pass = CreGuidelines.MIN_IRR_VALUE_PCT, warn = CreGuidelines.MIN_IRR_CORE_PCT,
                thresholdText = "코어 ${fmt(CreGuidelines.MIN_IRR_CORE_PCT)}+ / 밸류애드 ${fmt(CreGuidelines.MIN_IRR_VALUE_PCT)}+",
            ),
            band(
                "Equity Multiple", r.equityMultiple, "x",
                pass = CreGuidelines.MIN_EM, warn = CreGuidelines.MIN_EM - 0.2,
                thresholdText = "${fmt(CreGuidelines.MIN_EM)}x+",
            ),
            band(
                "최소 DSCR", minDscr, "x",
                pass = CreGuidelines.MIN_DSCR, warn = MIN_DSCR_FLOOR,
                thresholdText = "${fmt(CreGuidelines.MIN_DSCR)}x+ (최저 허용 ${fmt(MIN_DSCR_FLOOR)})",
            ),
            band(
                "평균 Cash-on-Cash", avgCoc, "%",
                pass = CreGuidelines.MIN_COC_PCT, warn = COC_WARN_FLOOR,
                thresholdText = "${fmt(CreGuidelines.MIN_COC_PCT)}%+",
            ),
            // LTV — 낮을수록 좋음(역방향)
            bandReverse(
                "LTV", input.ltvPct, "%",
                pass = CreGuidelines.MAX_LTV_PCT, warn = LTV_WARN_CEILING,
                thresholdText = "${fmt(CreGuidelines.MAX_LTV_PCT)}% 이하",
            ),
            // 보수성 — Going-in Cap < Exit Cap 권장(같거나 높으면 WARN)
            Check(
                metric = "보수성(Going-in<Exit Cap)",
                actual = "Going-in ${fmt(r.goingInCapPct)}% vs Exit ${fmt(r.exitCapPct)}%",
                threshold = "Going-in < Exit 권장",
                status = if (r.goingInCapPct < r.exitCapPct) Status.PASS else Status.WARN,
            ),
        )
        return Summary(
            checks = checks,
            pass = checks.count { it.status == Status.PASS },
            warn = checks.count { it.status == Status.WARN },
            fail = checks.count { it.status == Status.FAIL },
        )
    }

    /** AI 프롬프트 주입용 텍스트 블록(facts ⑦). */
    fun toFactsBlock(summary: Summary): String = buildString {
        append("[⑦ 가이드라인 적합성 — 코드 판정 (기준일 ${CreGuidelines.AS_OF})]\n")
        summary.checks.forEach { c ->
            append("  ${c.metric}: ${c.actual} (기준 ${c.threshold}) → ${c.status}\n")
        }
        append("  종합: PASS ${summary.pass} · WARN ${summary.warn} · FAIL ${summary.fail}")
        append(" — 위 판정은 코드가 임계값과 대조한 확정 결과이므로, 서술 시 이 판정을 근거로 삼고 임의 재판정하지 마세요.")
    }

    // 높을수록 좋은 지표: actual ≥ pass → PASS, ≥ warn → WARN, else FAIL
    private fun band(metric: String, actual: Double, unit: String, pass: Double, warn: Double, thresholdText: String) =
        Check(metric, "${fmt(actual)}$unit", thresholdText, when {
            actual >= pass -> Status.PASS
            actual >= warn -> Status.WARN
            else -> Status.FAIL
        })

    // 낮을수록 좋은 지표: actual ≤ pass → PASS, ≤ warn → WARN, else FAIL
    private fun bandReverse(metric: String, actual: Double, unit: String, pass: Double, warn: Double, thresholdText: String) =
        Check(metric, "${fmt(actual)}$unit", thresholdText, when {
            actual <= pass -> Status.PASS
            actual <= warn -> Status.WARN
            else -> Status.FAIL
        })

    private fun fmt(v: Double): String = if (v == v.toLong().toDouble()) v.toLong().toString() else v.toString()

    // 판정 경계 보조 임계값(가이드라인 본문의 품질검증 밴드 기준)
    private const val MIN_DSCR_FLOOR = 1.10   // underwritingGuidelineText 품질검증 DSCR ≥ 1.10
    private const val COC_WARN_FLOOR = 3.0
    private const val LTV_WARN_CEILING = 70.0 // 개발 PF LTC/LTV 상한 관행
}
