package com.aixnative.contract

import com.aixnative.contract.domain.ContractClauseParser
import com.aixnative.contract.domain.ContractVersionDiff
import com.aixnative.contract.domain.LeaseFlagRules
import com.aixnative.contract.domain.LoiFlagRules
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Test
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ContractRulesAndDiffTest {

    private val mapper = ObjectMapper()
    private val today = LocalDate.of(2026, 10, 21)

    private fun fields(flags: List<com.aixnative.contract.domain.RuleFlag>) = flags.map { it.field }.toSet()

    @Test
    fun `loi - a clean standard loi raises nothing`() {
        val ex = mapper.readTree(
            """{"purchase_price":{"total_krw":10000000000},
               "payment":{"down_krw":1000000000,"balance_krw":9000000000},
               "validity":{"valid_until":"2026-12-31"},
               "exclusive_negotiation":{"granted":true},
               "termination":{"conditions":"실사 결과 중대한 하자","penalty":"계약금 몰취"},
               "indemnity":{"scope":"진술·보장 위반"}}""",
        )
        assertEquals(emptyList(), LoiFlagRules.evaluate(ex, today))
    }

    @Test
    fun `loi - payment mismatch, thin deposit, expired validity, no exclusivity`() {
        val ex = mapper.readTree(
            """{"purchase_price":{"total_krw":"10,000,000,000원"},
               "payment":{"down_krw":300000000,"balance_krw":9000000000},
               "validity":{"valid_until":"2026.10.01"},
               "exclusive_negotiation":{"granted":"미부여"},
               "termination":{"penalty":"계약금 전액 몰취"}}""",
        )
        val flags = LoiFlagRules.evaluate(ex, today)
        val f = fields(flags)

        assertTrue("payment.interim_krw" in f, "중도금 누락 가능성")
        assertTrue("payment.down_krw" in f, "계약금 3% 는 표준 하회")
        assertTrue(flags.any { it.field == "validity.valid_until" && it.severity == "HIGH" }, "유효기간 경과")
        assertTrue("exclusive_negotiation.granted" in f)
        assertTrue("termination.penalty" in f)
        assertTrue("termination.conditions" in f)
        assertTrue("indemnity.scope" in f)
    }

    @Test
    fun `loi - a refund of the full deposit is not an all-or-nothing penalty`() {
        val ex = mapper.readTree("""{"termination":{"conditions":"실사 하자","penalty":"해제 시 계약금 전액 반환"}}""")
        assertFalse("termination.penalty" in fields(LoiFlagRules.evaluate(ex, today)))
    }

    @Test
    fun `loi - missing price is high and skips ratio checks`() {
        val flags = LoiFlagRules.evaluate(mapper.readTree("""{"payment":{"down_krw":1}}"""), today)
        assertTrue(flags.any { it.field == "purchase_price.total_krw" && it.severity == "HIGH" })
        assertFalse("payment.down_krw" in fields(flags))
    }

    @Test
    fun `lease - flags above-market escalation, long rent free, thin deposit`() {
        val ex = mapper.readTree(
            """{"increase":{"rate_pct_per_year":7.0},"rent_free":{"months":9},
               "termination":{"notice_months":12,"penalty":"잔여기간 임대료 전부 배상"},
               "restoration":{"scope":"전부 원상복구"},
               "rent":{"monthly_krw":10000000,"deposit_krw":30000000}}""",
        )
        val f = fields(LeaseFlagRules.evaluate(ex))
        assertEquals(
            setOf(
                "increase.rate_pct_per_year", "rent_free.months", "termination.notice_months", "termination.penalty",
                "restoration.scope", "renewal", "rent.deposit_krw",
            ),
            f,
        )
    }

    @Test
    fun `lease - market-standard terms only note the missing auto renewal`() {
        val ex = mapper.readTree(
            """{"increase":{"rate_pct_per_year":3},"rent":{"monthly_krw":10000000,"deposit_krw":100000000},"renewal":{"max_terms":1}}""",
        )
        assertEquals(setOf("renewal.auto"), fields(LeaseFlagRules.evaluate(ex)))
    }

    private val v1 = """
        제1조(목적) 본 계약은 매매에 관한 사항을 정한다.
        제2조(매매대금) ① 매매대금은 100억원으로 한다.
        ② 계약금은 10억원으로 한다.
        제3조(잔금) 잔금은 2026년 12월 31일에 지급한다.
    """.trimIndent()

    private val v2 = """
        제1조 (목적) 본 계약은  매매에 관한 사항을 정한다.
        제2조(매매대금) ① 매매대금은 95억원으로 한다.
        ② 계약금은 10억원으로 한다.
        제3조(잔금) 잔금은 2026년 12월 31일에 지급한다.
        제4조(비용) 이전 비용은 매수인이 부담한다.
    """.trimIndent()

    @Test
    fun `clause parser splits articles and circled paragraphs with headings`() {
        val c = ContractClauseParser.parse(v1)
        assertEquals(listOf("제1조", "제2조 제1항", "제2조 제2항", "제3조"), c.keys.toList())
        assertEquals("매매대금", c.getValue("제2조 제1항").heading)
        assertEquals("매매대금은 100억원으로 한다.", c.getValue("제2조 제1항").text)
    }

    @Test
    fun `version diff reports only changed clauses with code-owned text`() {
        val r = ContractVersionDiff.diff(
            listOf(
                ContractVersionDiff.Version("1차안", ContractClauseParser.parse(v1)),
                ContractVersionDiff.Version("회신안", ContractClauseParser.parse(v2)),
            ),
        )
        // 제1조는 공백만 달라 변경 아님(공백 정규화), 제2조 제1항은 수정, 제4조는 신설.
        assertEquals(listOf("제2조 제1항", "제4조"), r.changed.map { it.ref })
        assertEquals(5, r.total)
        assertEquals(3, r.identical)
        assertEquals(null, r.changed[1].texts["1차안"])
        assertEquals("신설", ContractVersionDiff.resolveChangeType("수정", r.changed[1].texts))
        assertEquals("수정", ContractVersionDiff.resolveChangeType("검토중", r.changed[0].texts))
    }

    @Test
    fun `paragraph fallback finds changed, deleted and inserted blocks`() {
        val a = "갑은 을에게 건물을 매도한다.\n대금은 100억원이다.\n잔금일은 연말이다."
        val b = "갑은 을에게 건물을 매도한다.\n대금은 95억원이다.\n잔금일은 연말이다.\n비용은 을이 부담한다."
        val r = ContractVersionDiff.diffParagraphs("A", a, "B", b)

        assertEquals("문단", r.mode)
        assertEquals(2, r.changed.size)
        assertEquals("대금은 100억원이다.", r.changed[0].texts["A"])
        assertEquals("대금은 95억원이다.", r.changed[0].texts["B"])
        assertEquals(null, r.changed[1].texts["A"])
        assertEquals(2, r.identical)
    }

    @Test
    fun `punctuation-only edits count as minor`() {
        assertTrue(ContractVersionDiff.minorOnly(listOf("계약금은 10억원으로 한다.", "계약금은 10억원으로 한다")))
        assertFalse(ContractVersionDiff.minorOnly(listOf("계약금은 10억원", "계약금은 11억원")))
    }
}
