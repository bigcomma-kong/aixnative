package com.aixnative.contract.domain

import java.time.DateTimeException
import java.time.LocalDate
import java.time.Period
import java.time.temporal.ChronoUnit

/**
 * 계약서 본문의 기간 표기를 코드로 실측한다.
 *
 * LLM 은 날짜 산술에서 자주 틀린다(실제 사고: "2026-08-01 ~ 2031-09-30 = 61개월 1일" → 정답 62개월, 없는 모순을
 * inconsistencies 에 올렸다). 그래서 기간은 여기서 계산해 프롬프트에 사실로 주입하고 AI 는 인용만 하게 한다.
 *
 * 산정 관례 - 임대차·용역 기간은 **초일 산입, 만료일 포함**: 시작일 + N개월 - 1일 = 만료일.
 */
object ContractTermCalculator {

    private const val MAX_FACTS = 8

    /** 두 날짜 사이 거리 상한 - 이보다 멀면 같은 기간 표현으로 보지 않는다. */
    private const val RANGE_GAP_LIMIT = 60

    /** 종료일 뒤 "(62개월)" 을 찾는 범위. */
    private const val MONTH_LOOKAHEAD = 80

    /** 2026년 8월 1일 / 2026.08.01 / 2026-8-1. */
    private val DATE = Regex("(\\d{4})\\s*[년.\\-/]\\s*(\\d{1,2})\\s*[월.\\-/]\\s*(\\d{1,2})\\s*일?")

    /**
     * 종료일 **바로 뒤**의 기간 표기 - "(62개월)"·"까지(총 5년 2개월)"·"까지 62개월간". 연·월을 함께 잡는다
     * (뒤의 "2개월"만 읽으면 62개월을 2개월로 오인). 종료일에서 떨어진 "만료 6개월 전" 같은 통지 기간을 기간 표기로
     * 오인하면 없는 불일치를 만들어 AI 에 "지적하라"고 지시하게 되므로, 앞에 다른 낱말이 끼면 인정하지 않는다.
     */
    private val STATED_TERM = Regex(
        "^\\s*(?:일)?\\s*(?:까지)?\\s*(?:로|으로)?\\s*[(（]?\\s*(?:총|약)?\\s*(?:(\\d{1,2})\\s*년)?\\s*(?:(\\d{1,3})\\s*개\\s*월)?",
    )

    /**
     * 두 날짜 사이에 와야 하는 연결어. "개시·시작·기산"은 시작일 **앞**에 오는 말이라 넣지 않는다 -
     * 넣으면 "계약일 2026-01-10 … 임대차 개시일은 2026-03-01" 처럼 계약일→개시일을 기간으로 묶는다.
     */
    private val RANGE_LINK = Regex("(부터|까지|~|∼|-|–|에서|종료|만료|만기|말일)")

    data class TermFact(
        val start: LocalDate,
        val end: LocalDate,
        /** 계약서에 적힌 개월 수(없으면 null). */
        val statedMonths: Int?,
        val months: Int,
        /** 잔여 일수 - 0 이면 딱 떨어지는 개월 수. */
        val days: Int,
        val totalDays: Long,
    ) {
        /** 계약서 표기와 실측이 일치하는가 - 불일치일 때만 진짜 모순이다. */
        val matchesStated: Boolean get() = statedMonths != null && statedMonths == months && days == 0

        val termText: String get() = if (days == 0) "${months}개월" else "${months}개월 ${days}일"
    }

    /** 본문에서 "시작일 ~ 종료일 (N개월)" 형태를 찾아 실측한다(같은 기간은 한 번만). */
    fun extract(text: String?): List<TermFact> {
        if (text.isNullOrBlank()) return emptyList()
        val matches = DATE.findAll(text).mapNotNull { m ->
            toDate(m.groupValues[1], m.groupValues[2], m.groupValues[3])?.let { it to m.range }
        }.toList()

        val found = LinkedHashMap<String, TermFact>()
        for (i in 0 until matches.size - 1) {
            if (found.size >= MAX_FACTS) break
            val (start, startRange) = matches[i]
            val (end, endRange) = matches[i + 1]
            if (!end.isAfter(start)) continue
            val between = text.substring(startRange.last + 1, endRange.first)
            if (between.length > RANGE_GAP_LIMIT || !RANGE_LINK.containsMatchIn(between)) continue
            found.putIfAbsent("$start~$end", of(start, end, statedMonths(text, endRange.last + 1)))
        }
        return found.values.toList()
    }

    /** 시작일·종료일로 기간 실측(초일 산입, 만료일 포함). */
    fun of(start: LocalDate, end: LocalDate, statedMonths: Int?): TermFact {
        val endExclusive = end.plusDays(1)
        val p = Period.between(start, endExclusive)
        return TermFact(start, end, statedMonths, p.years * 12 + p.months, p.days, ChronoUnit.DAYS.between(start, endExclusive))
    }

    /** ISO 날짜 두 개로 실측 - AI 가 추출한 효력일·만료일 사후 검산용 안전망. 파싱 불가·역순이면 null. */
    fun ofIso(startIso: String?, endIso: String?): TermFact? {
        val s = parseIso(startIso) ?: return null
        val e = parseIso(endIso) ?: return null
        return if (e.isAfter(s)) of(s, e, null) else null
    }

    /**
     * 프롬프트 실측 블록. 표기와 실측이 어긋난 건만 "불일치"로 표시해, 일치하는 기간을 모순으로 오탐하지 않게 한다.
     * 기간 표기를 못 찾으면 빈 문자열.
     */
    fun factBlock(text: String?): String {
        val facts = extract(text)
        if (facts.isEmpty()) return ""
        val sb = StringBuilder("\n## 기간 실측 (코드 계산값 - 이 값만 인용할 것)\n")
        sb.append("산정 관례: 초일 산입·만료일 포함. 즉 시작일 + N개월 - 1일 = 만료일.\n")
        facts.forEach { f ->
            sb.append("- ").append(f.start).append(" ~ ").append(f.end)
                .append(" → 실측 ").append(f.termText)
                .append(" (총 ").append("%,d".format(f.totalDays)).append("일)")
            when {
                f.statedMonths == null -> sb.append(". 계약서에 개월 수 표기 없음.")
                f.matchesStated -> sb.append(". 계약서 표기 '").append(f.statedMonths)
                    .append("개월' 과 **일치** → 기간 불일치로 지적하지 말 것. ")
                    .append("일치를 확인했다는 사실도 inconsistencies 에 적지 말 것 - 검증 통과는 결함이 아니다.")
                else -> sb.append(". 계약서 표기 '").append(f.statedMonths)
                    .append("개월' 과 **불일치** → inconsistencies 에 실측값을 근거로 지적할 것.")
            }
            sb.append('\n')
        }
        return sb.toString()
    }

    private fun parseIso(raw: String?): LocalDate? {
        if (raw.isNullOrBlank()) return null
        val m = DATE.find(raw.trim()) ?: return null
        return toDate(m.groupValues[1], m.groupValues[2], m.groupValues[3])
    }

    /** 종료일 직후 "(62개월)"·"까지(총 5년 2개월)"를 개월 수로 환산(연·월 함께면 합산). 붙어 있지 않으면 null. */
    private fun statedMonths(text: String, fromIndex: Int): Int? {
        val to = minOf(text.length, fromIndex + MONTH_LOOKAHEAD)
        if (fromIndex >= to) return null
        val m = STATED_TERM.find(text.substring(fromIndex, to)) ?: return null
        val years = m.groups[1]?.value
        val months = m.groups[2]?.value
        if (years == null && months == null) return null
        return ((years?.toInt() ?: 0) * 12 + (months?.toInt() ?: 0)).takeIf { it > 0 }
    }

    private fun toDate(y: String, mo: String, d: String): LocalDate? = try {
        LocalDate.of(y.toInt(), mo.toInt(), d.toInt())
    } catch (e: NumberFormatException) {
        null
    } catch (e: DateTimeException) {
        null // 2026.13.45 같은 오기
    }
}
