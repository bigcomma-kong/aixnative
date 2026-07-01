package com.aixnative.underwriting.domain
import com.aixnative.underwriting.web.UnderwriteRequest

/**
 * ProForma 계산 결과(확정 수치)를 언더라이팅 내러티브 프롬프트의 <FACTS> 블록으로 직렬화.
 * 결정론적 텍스트 — AI 는 이 수치만 근거로 서술한다(수치 창작 금지).
 */
object FactsFormatter {

    fun toFacts(input: ProFormaCalculator.Inputs, r: ProFormaCalculator.Result, scenarios: List<ProFormaCalculator.Scenario>): String {
        val sb = StringBuilder()
        sb.append("[① 매입구조] 매입가 ${input.askingPriceEok}억 · 취득부대 ${input.acqCostPct}% → 총투자비 ${r.totalInvestEok}억, ")
            .append("LTV ${input.ltvPct}% → 대출 ${r.debtEok}억 / Equity ${r.equityEok}억, 금리 ${input.loanRatePct}% → 연이자 ${r.annualInterestEok}억\n")

        sb.append("[② Year-by-Year 운영] (NOI/이자/CapEx/Levered CF/DSCR/CoC%, 임대성장 ${input.rentGrowthPct}%)\n")
        for (row in r.proForma) {
            sb.append("  Y${row.year}: NOI ${row.noi} · 이자 ${row.interest} · CapEx ${row.capex} · LeveredCF ${row.leveredCf} · DSCR ${row.dscr} · CoC ${row.cocPct}%\n")
        }

        sb.append("[③ Exit 가정] Exit Cap ${r.exitCapPct}% · forward NOI ${r.exitNoiEok}억 → 매각가 ${r.exitValueEok}억, ")
            .append("매각비용 ${input.saleCostPct}% → 순매각 ${r.netSaleEok}억, 대출상환 후 Exit Equity ${r.exitEquityEok}억\n")

        sb.append("[④ 수익지표] Levered IRR ${r.leveredIrrPct}% · Unlevered IRR ${r.unleveredIrrPct}% · Equity Multiple ${r.equityMultiple}x · ")
            .append("Going-in Cap ${r.goingInCapPct}% · Yield-on-Cost ${r.yieldOnCostPct}%\n")

        sb.append("[⑤ 민감도 — Exit Cap 변동]\n")
        for (s in r.exitCapSensitivity) {
            sb.append("  Exit Cap ${s.exitCapPct}%: 순매각 ${s.saleValueEok}억 · IRR ${s.leveredIrrPct}% · EM ${s.em}x\n")
        }

        sb.append("[⑥ 시나리오 — 하방/기준/상방]\n")
        for (s in scenarios) {
            sb.append("  ${s.name}: 임대성장 ${s.rentGrowthPct}% · Exit Cap ${s.exitCapPct}% → IRR ${s.leveredIrrPct}% · EM ${s.equityMultiple}x · 최소 DSCR ${s.minDscr} · 매각가 ${s.exitValueEok}억\n")
        }

        sb.append(GuidelineEvaluator.toFactsBlock(GuidelineEvaluator.evaluate(input, r)))

        return sb.toString().trimEnd()
    }

    /**
     * 자산/매입 가정 요약 텍스트. 스크리닝·시장조사·투심 메모의 <DOCUMENT>/<ASSET>/<FACTS> 컨텍스트로 사용.
     * 구조화 입력 + 코드 계산 지표(Going-in Cap 등)를 함께 제공한다.
     */
    fun toAssetFacts(req: UnderwriteRequest, r: ProFormaCalculator.Result): String {
        val sb = StringBuilder()
        sb.append("[자산] 유형 ${req.assetType ?: "(미지정)"}")
        req.location?.takeIf { it.isNotBlank() }?.let { sb.append(" · 위치 $it") }
        req.dealName?.takeIf { it.isNotBlank() }?.let { sb.append(" · 딜명 $it") }
        sb.append("\n")

        sb.append("[매입 가정] 매입가 ${req.askingPriceEok}억 · NOI ${req.noiEok}억 · ")
            .append("Going-in Cap ${r.goingInCapPct}% · LTV ${req.ltvPct}% · 금리 ${req.loanRatePct}% · ")
            .append("보유 ${req.holdYears}년 · Exit Cap ${req.exitCapPct}% · 임대성장 ${req.rentGrowthPct}%\n")

        sb.append("[코드 산출 지표] Levered IRR ${r.leveredIrrPct}% · Equity Multiple ${r.equityMultiple}x · ")
            .append("Yield-on-Cost ${r.yieldOnCostPct}%\n")

        val gl = GuidelineEvaluator.evaluate(req.toInputs(), r)
        sb.append("[가이드라인 적합성 — 코드 판정] PASS ${gl.pass} · WARN ${gl.warn} · FAIL ${gl.fail}")
            .append(gl.checks.filter { it.status != GuidelineEvaluator.Status.PASS }
                .joinToString("", prefix = "\n") { "  · ${it.metric} ${it.actual} → ${it.status}\n" })

        req.notes?.takeIf { it.isNotBlank() }?.let { sb.append("[메모]\n").append(it).append("\n") }

        return sb.toString().trimEnd()
    }
}
