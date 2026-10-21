package com.aixnative.contract

import com.aixnative.contract.domain.ContractMoneyCalculator
import com.aixnative.contract.domain.ContractTermCalculator
import org.junit.jupiter.api.Test
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 사내 레거시 운영 사고 사례로 검증된 계약 금액·기간 실측(포팅 후 동작 동일성 확인). */
class ContractCalculatorsTest {

    private val saleText = "제3조(매매대금) 매매대금은 금 85,900,000,000원(건물분 부가가치세 3,046,873,000원 별도)으로 한다."

    @Test
    fun `money - building vat keeps its qualifier and raw digits`() {
        val facts = ContractMoneyCalculator.extract(saleText)

        assertEquals(listOf(85_900_000_000L, 3_046_873_000L), facts.map { it.won })
        assertEquals("매매대금", facts[0].label)
        assertEquals("건물분 부가가치세", facts[1].label)
        assertEquals("859억원(₩85,900,000,000)", facts[0].fullText)
        assertEquals("₩3,046,873,000", facts[1].fullText)
    }

    @Test
    fun `money - label does not swallow a conjunction`() {
        val facts = ContractMoneyCalculator.extract("위약금 3,000,000원 및 지체상금 12,500,000원")
        assertEquals(listOf("지체상금", "위약금"), facts.map { it.label })
    }

    @Test
    fun `money - truncated man-won forms are restored to raw digits`() {
        val facts = ContractMoneyCalculator.extract(saleText)

        assertEquals(
            "계약 금액은 859억원(부가가치세 별도 3,046,873,000원)이다.",
            ContractMoneyCalculator.restoreTruncated("계약 금액은 859억원(부가가치세 별도 30억4,687만원)이다.", facts),
        )
        assertEquals("3,046,873,000원", ContractMoneyCalculator.restoreTruncated("30억 4,687만 원", facts))
        assertEquals("3,046,873,000원", ContractMoneyCalculator.restoreTruncated("304,687만원", facts))
    }

    @Test
    fun `money - unknown, allowed and ambiguous forms are left alone`() {
        val facts = ContractMoneyCalculator.extract("보증금 50,000,000원, 매매대금 85,900,000,000원")
        assertEquals("보증금 5,000만원", ContractMoneyCalculator.restoreTruncated("보증금 5,000만원", facts))
        assertEquals("관리비 1억2,000만원", ContractMoneyCalculator.restoreTruncated("관리비 1억2,000만원", facts))

        val twin = ContractMoneyCalculator.extract("갑 3,046,873,000원 을 3,046,875,000원")
        assertEquals("30억4,687만원", ContractMoneyCalculator.restoreTruncated("30억4,687만원", twin))
    }

    @Test
    fun `money - no amounts means no fact block`() {
        assertEquals("", ContractMoneyCalculator.factBlock("제1조 목적 - 금액 없음"))
        assertTrue(ContractMoneyCalculator.factBlock(saleText).contains("859억원(₩85,900,000,000)"))
    }

    @Test
    fun `term - the false-positive incident is exactly 62 months`() {
        val f = ContractTermCalculator.of(LocalDate.of(2026, 8, 1), LocalDate.of(2031, 9, 30), 62)

        assertEquals(62, f.months)
        assertEquals(0, f.days)
        assertEquals(1887, f.totalDays)
        assertTrue(f.matchesStated)
        assertEquals("62개월", f.termText)
    }

    @Test
    fun `term - inclusive end date and leap february`() {
        assertEquals("12개월", ContractTermCalculator.of(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), null).termText)
        assertEquals("12개월 1일", ContractTermCalculator.of(LocalDate.of(2026, 1, 1), LocalDate.of(2027, 1, 1), null).termText)
        val feb = ContractTermCalculator.of(LocalDate.of(2028, 2, 1), LocalDate.of(2028, 2, 29), null)
        assertEquals(1, feb.months)
        assertEquals(29, feb.totalDays)
    }

    @Test
    fun `term - extracts korean ranges and flags stated mismatches`() {
        val match = ContractTermCalculator.extract("임대차기간은 2026년 8월 1일부터 2031년 9월 30일까지(62개월)로 한다.").single()
        assertEquals(62, match.statedMonths)
        assertTrue(match.matchesStated)

        val mismatch = ContractTermCalculator.extract("임대차기간은 2026년 8월 1일부터 2031년 9월 30일까지 (60개월) 로 한다.").single()
        assertEquals(62, mismatch.months)
        assertFalse(mismatch.matchesStated)

        assertEquals(1, ContractTermCalculator.extract("계약기간 2026.08.01 ~ 2027.07.31").size)
        assertEquals(1, ContractTermCalculator.extract("기간: 2026-08-01 부터 2027-07-31 까지").size)
    }

    @Test
    fun `term - years and months are summed, unrelated or invalid dates are ignored`() {
        val f = ContractTermCalculator.extract("기간은 2026년 8월 1일부터 2031년 9월 30일까지(5년 2개월)").single()
        assertEquals(62, f.statedMonths)

        val far = "계약일은 2026년 8월 1일이다. " + "가".repeat(80) + " 준공 예정일은 2031년 9월 30일이다."
        assertTrue(ContractTermCalculator.extract(far).isEmpty())
        assertTrue(ContractTermCalculator.extract("2026년 13월 45일부터 2027년 2월 30일까지").isEmpty())
        assertTrue(ContractTermCalculator.extract(null).isEmpty())
    }

    @Test
    fun `term - a notice period after the end date is not read as the stated term`() {
        val f = ContractTermCalculator.extract("기간은 2026년 8월 1일부터 2031년 9월 30일까지로 한다. 임차인은 만료 6개월 전까지 통지한다.").single()
        assertEquals(null, f.statedMonths)
        assertFalse(ContractTermCalculator.factBlock("기간은 2026년 8월 1일부터 2031년 9월 30일까지로 한다. 만료 6개월 전 통지").contains("불일치"))
    }

    @Test
    fun `term - signing date and start date are not paired as a term`() {
        assertTrue(ContractTermCalculator.extract("계약일 2026년 1월 10일. 임대차 개시일은 2026년 3월 1일로 한다.").isEmpty())
        // 연결어 없이 "개시일은 … 종료일은 …" 으로 쓰는 실제 문구는 여전히 잡는다.
        assertEquals(1, ContractTermCalculator.extract("개시일은 2026년 8월 1일, 종료일은 2031년 9월 30일(62개월)").size)
    }

    @Test
    fun `term - fact block tells the model not to flag matches`() {
        val matched = ContractTermCalculator.factBlock("기간은 2026년 8월 1일부터 2031년 9월 30일까지(62개월)")
        val mismatched = ContractTermCalculator.factBlock("기간은 2026년 8월 1일부터 2031년 9월 30일까지(60개월)")

        assertTrue(matched.contains("실측 62개월") && matched.contains("1,887일") && matched.contains("지적하지 말 것"))
        assertTrue(mismatched.contains("불일치") && mismatched.contains("inconsistencies"))
        assertEquals("", ContractTermCalculator.factBlock("날짜 없음"))
    }

    @Test
    fun `term - iso fallback validates order`() {
        assertEquals(62, ContractTermCalculator.ofIso("2026-08-01", "2031-09-30")?.months)
        assertEquals(null, ContractTermCalculator.ofIso("2031-09-30", "2026-08-01"))
        assertEquals(null, ContractTermCalculator.ofIso(null, "2026-08-01"))
    }
}
