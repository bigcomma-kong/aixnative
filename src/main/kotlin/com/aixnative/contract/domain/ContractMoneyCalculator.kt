package com.aixnative.contract.domain

/**
 * 계약서 본문의 원 단위 금액을 코드가 실측해 프롬프트에 사실로 못 박는다([ContractTermCalculator] 의 금액판).
 *
 * 왜 코드가 계산하나 - 사내 레거시 운영에서 `85,900,000,000원`(859억원)이 검토 결과에 `85,900억원`(100배)으로 실렸다.
 * "억원 정수로 쓰라"와 "스스로 환산하지 마라"가 원 단위 원문에서 충돌하자 모델이 뒤 여섯 자리를 잘라냈다.
 * 법무 검토 문서에 금액이 100배로 실리는 것은 둘 수 없으므로 실측값을 주입하고, 어긴 표기는 [restoreTruncated] 로 되돌린다.
 */
object ContractMoneyCalculator {

    /** 프롬프트 비대화 방지 - 큰 금액부터 이 개수까지만. */
    private const val MAX_FACTS = 8
    private const val EOK = 100_000_000L
    private const val MAN = 10_000L

    /** 의미 있는 금액 하한 - 조항 번호·면적·날짜 오인 방지. */
    private const val MIN_AMOUNT = 1_000_000L

    /**
     * 원 단위 금액 표기(₩85,900,000,000 / 85,900,000,000원 / 금 859,000,000원정). 천단위 콤마가 있는 표기만 잡는다 -
     * 콤마 없는 긴 숫자열은 사업자번호·계좌번호일 수 있어, 오탐으로 틀린 실측을 넣느니 못 잡는 편이 낫다.
     * '금' 접두사에 lookbehind 가 필요하다 - 없으면 "위약금 3,000,000원" 의 '금'을 먹어 항목명이 '위약'으로 깨진다.
     */
    private val KRW_COMMA = Regex(
        "(?:(₩|KRW\\s*)(\\d{1,3}(?:,\\d{3})+)" +
            "|(?:(?<![가-힣])금\\s*)?(\\d{1,3}(?:,\\d{3})+)\\s*(?:원정|원|KRW))",
    )

    /** 금액 앞 항목명 - 아는 금액 항목으로 끝날 때만 인정(잘린 조각이 라벨로 붙는 것 방지). */
    private val LABEL_TAIL = Regex(
        "((?:[가-힣]{1,4}분\\s)?[가-힣]{0,6}(?:매매대금|계약금액|계약금|중도금|잔금|보증금|차임|임대료|관리비|" +
            "위약금|지체상금|용역대금|공사대금|대금|보수|수수료|대여금|원금|총액|금액|부가가치세|부가세))" +
            "\\s*[은는이가:]?\\s*$",
    )

    /** AI 서술의 '만원' 표기 - 30억4,687만원 · 304,687만원. 숫자 중간에서 시작하지 않게 앞자리를 막는다. */
    private val MAN_WON = Regex(
        "(?<![\\d,])(?:(\\d{1,3}(?:,\\d{3})+|\\d+)\\s*억\\s*)?(\\d{1,3}(?:,\\d{3})+|\\d+)\\s*만\\s*원",
    )

    data class MoneyFact(val label: String, val won: Long) {
        /**
         * 서술용 축약 - 딱 떨어질 때만, 아니면 null. 반올림하면 천만원 단위가 소리 없이 사라지고,
         * 소수점 억·억만 혼용은 표기 규약이 금지하므로 남는 선택은 원 단위 원문이다.
         */
        val shortText: String?
            get() = when {
                won % EOK == 0L -> "%,d억원".format(won / EOK)
                won < EOK && won % MAN == 0L -> "%,d만원".format(won / MAN)
                else -> null
            }

        /** "859억원(₩85,900,000,000)" - 축약이 불가능하면 원문 숫자만. */
        val fullText: String
            get() {
                val wonText = "₩%,d".format(won)
                return shortText?.let { "$it($wonText)" } ?: wonText
            }
    }

    /** 본문의 원 단위 금액(큰 것부터, 중복 제거, 항목명은 첫 등장). */
    fun extract(text: String?): List<MoneyFact> {
        if (text.isNullOrBlank()) return emptyList()
        val found = LinkedHashMap<Long, String>()
        for (m in KRW_COMMA.findAll(text)) {
            val digits = m.groups[2]?.value ?: m.groups[3]?.value ?: continue
            val won = parseWon(digits)
            if (won < MIN_AMOUNT) continue
            found.putIfAbsent(won, labelBefore(text, m.range.first))
        }
        return found.map { (won, label) -> MoneyFact(label, won) }
            .sortedByDescending { it.won }
            .take(MAX_FACTS)
    }

    /** 프롬프트 주입용 실측 블록. 금액을 못 찾으면 빈 문자열 - 없는 사실을 지어내지 않는다. */
    fun factBlock(text: String?): String {
        val facts = extract(text)
        if (facts.isEmpty()) return ""
        val sb = StringBuilder("\n[금액 실측 - 코드가 계산한 값, 그대로 인용할 것]\n")
        facts.forEach { f ->
            sb.append("- ")
            if (f.label.isNotEmpty()) sb.append(f.label).append(": ")
            sb.append(f.fullText).append('\n')
        }
        sb.append("위 값 외의 금액을 억원 단위로 새로 환산하지 말 것. 실측에 없는 금액은 계약서에 적힌 표기 그대로 인용한다.\n")
        return sb.toString()
    }

    /**
     * AI 가 원 단위 금액을 '30억4,687만원'처럼 만원 아래를 버려 쓴 표기를 원문 숫자로 되돌린다.
     * 그 값이 실측 금액을 만원 아래에서 버린 값과 같고, 그런 실측이 **딱 하나**일 때만 바꾼다
     * (실측에 없거나 후보가 둘 이상이면 무엇이 맞는지 코드도 모르므로 손대지 않는다).
     */
    fun restoreTruncated(s: String?, facts: List<MoneyFact>): String? {
        if (s.isNullOrEmpty() || facts.isEmpty() || '만' !in s) return s
        return MAN_WON.replace(s) { m ->
            val eok = m.groups[1]?.value?.let(::parseWon) ?: 0L
            val man = parseWon(m.groups[2]?.value ?: "0")
            uniqueTruncatedMatch(eok * EOK + man * MAN, facts)?.let { "%,d원".format(it.won) } ?: m.value
        }
    }

    private fun uniqueTruncatedMatch(value: Long, facts: List<MoneyFact>): MoneyFact? {
        if (value <= 0) return null
        val hits = facts.filter { f ->
            val truncatesTo = f.won / MAN * MAN == value
            val bannedForm = f.won % MAN != 0L || f.won >= EOK
            truncatesTo && bannedForm
        }
        return hits.singleOrNull()
    }

    private fun parseWon(commaNumber: String): Long = commaNumber.replace(",", "").toLongOrNull() ?: 0L

    /** 금액 바로 앞 24자 안에서 항목명을 줍는다(줄바꿈 뒤부터만). */
    private fun labelBefore(text: String, moneyStart: Int): String {
        var before = text.substring(maxOf(0, moneyStart - 24), moneyStart)
        val nl = maxOf(before.lastIndexOf('\n'), before.lastIndexOf('\r'))
        if (nl >= 0) before = before.substring(nl + 1)
        return LABEL_TAIL.find(before)?.groupValues?.get(1)?.trim().orEmpty()
    }
}
