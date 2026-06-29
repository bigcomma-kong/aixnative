package com.aixnative.integration.bizhealth

/**
 * 거래상대방(매도자·임차인) 실사 결과 — 공공 데이터 4종의 결정론적 사실.
 * 각 블록은 독립 graceful(개별 실패 시 available=false). AI 는 이 사실을 근거로 리스크만 서술.
 */
data class BizHealthResult(
    val bizNo: String?,
    val name: String?,
    val status: BizStatus,
    val sanctions: SanctionResult,
    val corp: CorpInfo,
    val pension: PensionInfo,
)

/** 국세청 사업자등록상태 — 계속/휴업/폐업·과세유형. */
data class BizStatus(
    val available: Boolean,
    val status: String? = null,
    val taxType: String? = null,
    val closedDate: String? = null,
)

/** 조달청 부정당제재 — 유효 제재 목록. */
data class SanctionResult(
    val available: Boolean,
    val count: Int = 0,
    val items: List<Sanction> = emptyList(),
)

data class Sanction(val from: String, val to: String, val org: String, val basis: String)

/** 금융위 기업기본정보 — 대표·설립·업종. */
data class CorpInfo(
    val available: Boolean,
    val corpName: String? = null,
    val repName: String? = null,
    val estbDate: String? = null,
    val industry: String? = null,
)

/** 국민연금 가입사업장 — 규모(가입자수)·당월 고지액. */
data class PensionInfo(
    val available: Boolean,
    val workplaceName: String? = null,
    val members: String? = null,
    val monthlyNotice: String? = null,
    val industry: String? = null,
)

/** 실사 결과 → 프롬프트 <DATA> 주입용 결정론적 한국어 facts 블록. */
object BizHealthFacts {

    fun summary(r: BizHealthResult): String = buildString {
        appendLine("[거래상대방 실사 — 공공데이터 실측, 창작 금지]")
        append("· 사업자등록상태: ")
        if (r.status.available) {
            append(r.status.status ?: "-")
            r.status.taxType?.takeIf { it.isNotBlank() }?.let { append(" (").append(it).append(")") }
            r.status.closedDate?.takeIf { it.isNotBlank() }?.let { append(" · 폐업일 ").append(it) }
            appendLine()
        } else appendLine("조회 불가(사업자번호 미입력 또는 미승인 API)")

        append("· 부정당제재(조달청): ")
        if (r.sanctions.available) {
            if (r.sanctions.count == 0) appendLine("유효 제재 없음")
            else {
                appendLine("${r.sanctions.count}건")
                r.sanctions.items.take(3).forEach {
                    appendLine("    - ${it.from}~${it.to} ${it.org} (${it.basis})")
                }
            }
        } else appendLine("조회 불가")

        append("· 기업정보(금융위): ")
        if (r.corp.available) {
            append(r.corp.corpName ?: "-")
            r.corp.repName?.takeIf { it.isNotBlank() }?.let { append(" · 대표 ").append(it) }
            r.corp.estbDate?.takeIf { it.isNotBlank() }?.let { append(" · 설립 ").append(it) }
            r.corp.industry?.takeIf { it.isNotBlank() }?.let { append(" · 업종 ").append(it) }
            appendLine()
        } else appendLine("조회 불가(상호 미입력 또는 미승인 API)")

        append("· 규모(국민연금): ")
        if (r.pension.available) {
            append(r.pension.workplaceName ?: "-")
            r.pension.members?.takeIf { it.isNotBlank() }?.let { append(" · 가입자 ").append(it).append("명") }
            r.pension.monthlyNotice?.takeIf { it.isNotBlank() }?.let { append(" · 당월고지 ").append(it).append("원") }
            appendLine()
        } else appendLine("조회 불가")

        append("위는 공공기관 실측이므로 신용·리스크 판단 시 추정 대신 인용하세요. 폐업·유효제재는 즉시 RED.")
    }
}
