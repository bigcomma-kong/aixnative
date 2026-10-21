package com.aixnative.contract.domain

import com.fasterxml.jackson.databind.JsonNode
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * 규칙 엔진이 낸 리스크 플래그. AI 판단이 아니라 코드 규칙의 결과라 같은 입력이면 항상 같은 결과를 낸다.
 * @param field 점 표기 필드 경로(예: payment.down_krw)
 */
data class RuleFlag(val severity: String, val field: String, val message: String)

/**
 * 매입의향서(LOI) 리스크 규칙. AI 가 추출한 핵심 조항 JSON 을 받아 거래 통념 대비 위반을 플래그로 낸다.
 * 추출 누락(null)은 안전하게 통과하거나 LOW/MEDIUM 으로만 표시한다. 임계값은 운영하며 조정한다.
 */
object LoiFlagRules {

    private val DOWN_PAYMENT_PCT_LOW = BigDecimal("5.0")
    private val DOWN_PAYMENT_PCT_HIGH = BigDecimal("20.0")
    private const val VALIDITY_SOON_DAYS = 7L

    /** 매매대금 합계와 분납 합의 허용 오차(매입가 대비 %). */
    private val PAYMENT_SUM_TOLERANCE_PCT = BigDecimal("1.0")

    fun evaluate(ex: JsonNode?, today: LocalDate = LocalDate.now(java.time.ZoneId.of("Asia/Seoul"))): List<RuleFlag> {
        if (ex == null || !ex.isObject) return emptyList()
        val out = mutableListOf<RuleFlag>()
        val total = decimal(ex.at("/purchase_price/total_krw"))
        val down = decimal(ex.at("/payment/down_krw"))

        if (total == null || total.signum() <= 0) {
            out += RuleFlag("HIGH", "purchase_price.total_krw", "매입가(총액) 미기재 또는 0 - 핵심 거래조건 누락")
        } else {
            checkPaymentSum(ex, total, down, out)
            if (down != null) checkDownPaymentRatio(total, down, out)
        }
        checkValidity(ex, today, out)
        checkExclusiveNegotiation(ex, out)
        checkTermination(ex, out)
        if (text(ex.at("/indemnity/scope")) == null) {
            out += RuleFlag("LOW", "indemnity.scope", "면책 범위 미기재 - 진술·보장 위반 시 구제수단 확인 필요")
        }
        return out
    }

    private fun checkPaymentSum(ex: JsonNode, total: BigDecimal, down: BigDecimal?, out: MutableList<RuleFlag>) {
        val balance = decimal(ex.at("/payment/balance_krw"))
        if (down == null || balance == null) return // 분납 명세 불완전 - 합계 검증 보류
        val interim = decimal(ex.at("/payment/interim_krw"))
        val sum = down + balance + (interim ?: BigDecimal.ZERO)
        val tolerance = total.multiply(PAYMENT_SUM_TOLERANCE_PCT).divide(BigDecimal(100), 0, RoundingMode.HALF_UP)
        if ((sum - total).abs() <= tolerance) return
        // 중도금이 비어 있고 합계가 모자라면 추출 누락일 수 있다 - 단정(HIGH) 대신 확인 요청(MEDIUM).
        if (interim == null && sum < total) {
            out += RuleFlag("MEDIUM", "payment.interim_krw", "계약금+잔금이 매입가보다 %,d원 적음 - 중도금 기재 여부 확인".format((total - sum).toLong()))
        } else {
            out += RuleFlag("HIGH", "payment", "계약금+중도금+잔금 합(%,d원)이 매입가(%,d원)와 불일치".format(sum.toLong(), total.toLong()))
        }
    }

    private fun checkDownPaymentRatio(total: BigDecimal, down: BigDecimal, out: MutableList<RuleFlag>) {
        val pct = down.multiply(BigDecimal(100)).divide(total, 1, RoundingMode.HALF_UP)
        when {
            pct < DOWN_PAYMENT_PCT_LOW ->
                out += RuleFlag("MEDIUM", "payment.down_krw", "계약금 비율 $pct% - 표준(5~20%) 하회, 매도인 이행 담보 약함")
            pct > DOWN_PAYMENT_PCT_HIGH ->
                out += RuleFlag("MEDIUM", "payment.down_krw", "계약금 비율 $pct% - 표준(5~20%) 상회, 매수인 위약 리스크 과중")
        }
    }

    private fun checkValidity(ex: JsonNode, today: LocalDate, out: MutableList<RuleFlag>) {
        val raw = text(ex.at("/validity/valid_until"))
        if (raw == null) {
            out += RuleFlag("MEDIUM", "validity.valid_until", "의향서 유효기간 미기재 - 효력 존속기간 불명확")
            return
        }
        val until = parseDate(raw) ?: return // 형식 불명 - 플래그 없이 통과
        when {
            until.isBefore(today) ->
                out += RuleFlag("HIGH", "validity.valid_until", "의향서 유효기간($until)이 이미 경과 - 효력 만료")
            ChronoUnit.DAYS.between(today, until) <= VALIDITY_SOON_DAYS ->
                out += RuleFlag("MEDIUM", "validity.valid_until", "의향서 유효기간($until) 임박 - ${VALIDITY_SOON_DAYS}일 이내")
        }
    }

    private fun checkExclusiveNegotiation(ex: JsonNode, out: MutableList<RuleFlag>) {
        val eng = ex.get("exclusive_negotiation")
        if (eng == null || eng.isNull) {
            out += RuleFlag("LOW", "exclusive_negotiation", "우선협상권 조항 부재 - 협상 안정성 확인 권장")
            return
        }
        val granted = eng.get("granted")
        val notGranted = when {
            granted == null || granted.isNull -> false
            granted.isBoolean -> !granted.asBoolean()
            else -> granted.asText().let { it.contains("없") || it.contains("미부여") || it.trim().equals("false", true) }
        }
        if (notGranted) out += RuleFlag("MEDIUM", "exclusive_negotiation.granted", "우선협상권 미부여 - 경쟁 매수인 등장 리스크")
    }

    private fun checkTermination(ex: JsonNode, out: MutableList<RuleFlag>) {
        val term = ex.get("termination")
        if (term == null || term.isNull) {
            out += RuleFlag("MEDIUM", "termination", "해제·면책 조항 미기재 - 거래 무산 시 책임관계 불명확")
            return
        }
        if (text(term.get("conditions")) == null) {
            out += RuleFlag("LOW", "termination.conditions", "해제 조건 불명확 - 구체화 권장")
        }
        if (isAllOrNothing(text(term.get("penalty")))) {
            out += RuleFlag("HIGH", "termination.penalty", "위약 \"전부/전액\" 명시 - 과도한 위약 조항")
        }
    }
}

/**
 * 임대차계약 이상치 규칙(오피스·리테일 통념 기준). AI 가 추출한 12조항 JSON 을 받아 표준 대비 위배를 플래그로 낸다.
 */
object LeaseFlagRules {

    private val RENT_INCREASE_HIGH = BigDecimal("5.0")
    private const val RENT_FREE_MEDIUM = 6
    private const val NOTICE_MONTHS_HIGH = 6
    private const val DEPOSIT_MIN_MONTHS = 6

    fun evaluate(ex: JsonNode?): List<RuleFlag> {
        if (ex == null || !ex.isObject) return emptyList()
        val out = mutableListOf<RuleFlag>()

        decimal(ex.at("/increase/rate_pct_per_year"))?.takeIf { it > RENT_INCREASE_HIGH }?.let {
            out += RuleFlag("HIGH", "increase.rate_pct_per_year", "임대료 인상률 $it%/년 - 시장 표준(3~5%) 초과")
        }
        decimal(ex.at("/rent_free/months"))?.toInt()?.takeIf { it > RENT_FREE_MEDIUM }?.let {
            out += RuleFlag("MEDIUM", "rent_free.months", "장기 렌트프리 ${it}개월 - 실효임대료 재계산 필요")
        }
        decimal(ex.at("/termination/notice_months"))?.toInt()?.takeIf { it > NOTICE_MONTHS_HIGH }?.let {
            out += RuleFlag("HIGH", "termination.notice_months", "해지 통보 ${it}개월 - 과도한 락인")
        }
        if (isAllOrNothing(text(ex.at("/termination/penalty")))) {
            out += RuleFlag("HIGH", "termination.penalty", "위약 \"전부/전액\" 명시 - 과도한 위약 조항")
        }
        text(ex.at("/restoration/scope"))?.takeIf { it.contains("전부") || it.contains("원상복구의무 전체") }?.let {
            out += RuleFlag("MEDIUM", "restoration.scope", "원상복구 \"전부\" 명시 - 임차인 부담 과중 가능성, 예외 조항 확인 필요")
        }
        checkRenewal(ex, out)
        checkDepositRatio(ex, out)
        return out
    }

    private fun checkRenewal(ex: JsonNode, out: MutableList<RuleFlag>) {
        val renewal = ex.get("renewal")
        if (renewal == null || renewal.isNull) {
            out += RuleFlag("LOW", "renewal", "갱신 조항 부재 - 협상 권장")
            return
        }
        val auto = renewal.get("auto")
        if (auto == null || auto.isNull) {
            out += RuleFlag("LOW", "renewal.auto", "자동갱신 명시 없음 - 만기 통보 절차 확인 필요")
        }
    }

    private fun checkDepositRatio(ex: JsonNode, out: MutableList<RuleFlag>) {
        val deposit = decimal(ex.at("/rent/deposit_krw")) ?: return
        val monthly = decimal(ex.at("/rent/monthly_krw"))?.takeIf { it.signum() > 0 } ?: return
        val ratio = deposit.divide(monthly, 2, RoundingMode.HALF_UP)
        if (ratio.toInt() < DEPOSIT_MIN_MONTHS) {
            out += RuleFlag("HIGH", "rent.deposit_krw", "보증금 / 월임대료 = ${ratio}개월분 - 시장 표준(최소 6개월) 미달")
        }
    }
}

/**
 * 위약금이 "전부/전액" 몰취·배상인가. 단어만 보면 "보증금 전액 반환" 같은 정상 문구까지 걸리므로
 * 몰취·배상·지급·위약 같은 제재 동사가 가까이 붙은 경우만 인정한다.
 */
private val ALL_OR_NOTHING = Regex("(전액|전부)[^.,;]{0,8}(몰취|몰수|배상|지급|귀속|위약)|(몰취|몰수|배상|귀속)[^.,;]{0,8}(전액|전부)")

private fun isAllOrNothing(s: String?): Boolean = s != null && ALL_OR_NOTHING.containsMatchIn(s)

/** 숫자 노드 또는 "1,000,000원" 같은 문자열을 BigDecimal 로. 해석 불가면 null. */
private fun decimal(n: JsonNode?): BigDecimal? = when {
    n == null || n.isNull || n.isMissingNode -> null
    n.isNumber -> n.decimalValue()
    n.isTextual -> n.asText().replace(",", "").replace("원", "").trim().takeIf { it.isNotEmpty() }?.toBigDecimalOrNull()
    else -> null
}

private fun text(n: JsonNode?): String? =
    n?.takeIf { !it.isNull && !it.isMissingNode }?.asText()?.trim()?.takeIf { it.isNotEmpty() }

/** YYYY-MM-DD 우선, 점·슬래시 표기도 허용. 실패하면 null(규칙은 형식 불명 시 통과). */
private fun parseDate(s: String): LocalDate? {
    val t = s.trim()
    return runCatching { LocalDate.parse(t) }.getOrNull()
        ?: runCatching { LocalDate.parse(t.replace('.', '-').replace('/', '-').replace(Regex("\\s"), "")) }.getOrNull()
}
