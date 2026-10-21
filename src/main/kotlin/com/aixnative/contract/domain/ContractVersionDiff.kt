package com.aixnative.contract.domain

/**
 * 계약서 평문을 조(Article)/항(Paragraph) 단위로 분해한다 - 버전 비교에서 **LLM 대신 코드**가 조항을 키로 대조하기 위한 파서.
 * (긴 두 판을 LLM 이 머릿속으로 대조하면 환각·누락이 생긴다.)
 *
 *  - 줄 시작의 제N장·별첨·부록·붙임 머리는 제거(조 본문 혼입 차단).
 *  - 조: 줄 시작의 제N조. 번호가 '제1조'로 재시작하면 새 세그먼트(별첨 계약 등)로 보고 키에 (2) 접미사를 붙인다.
 *    그 외 역행은 본문 속 교차참조로 보고 무시한다.
 *  - 항: 조별로 직속 열거 기호를 동적 판별 - ①~⑳ → 제N항 → 줄 시작 N. 순. 하위 기호(N)·가.)는 본문에 남긴다.
 */
object ContractClauseParser {

    data class Clause(val ref: String, val articleNo: Int, val paragraphNo: Int, val heading: String, val text: String)

    private val BOUNDARY = Regex("(?m)^\\s*(제\\s*\\d+\\s*장|별\\s*첨|부\\s*록|붙\\s*임).*$")
    private val ARTICLE = Regex("(?m)^\\s*제\\s*(\\d+)\\s*조")
    private val HEADING = Regex("^\\s*([(（【])\\s*([^)）】]*?)\\s*[)）】]")
    private val HAS_HANGUL = Regex("[가-힣]")
    private val P_CIRCLED = Regex("[\\u2460-\\u2473]")
    private val P_JEHANG = Regex("(?m)^\\s*제\\s*(\\d+)\\s*항")
    private val P_NDOT = Regex("(?m)^\\s*(\\d+)\\.(?=\\s)")
    private const val CIRCLED_ONE = 0x2460

    private data class ArticleHead(val start: Int, val no: Int, val headerEnd: Int, val segment: Int)
    private data class ParaMark(val start: Int, val no: Int, val end: Int)

    /** 조·항 → Clause(등장 순서 보존). 조를 2개 미만으로 찾으면 빈 맵(호출부가 문단 비교로 폴백). */
    fun parse(text: String?): LinkedHashMap<String, Clause> {
        val out = LinkedHashMap<String, Clause>()
        if (text.isNullOrBlank()) return out
        val src = BOUNDARY.replace(text, "")

        val arts = mutableListOf<ArticleHead>()
        var lastArticle = 0
        var segment = 1
        for (m in ARTICLE.findAll(src)) {
            val no = m.groupValues[1].toIntOrNull() ?: continue
            if (no <= lastArticle) {
                if (no == 1) { segment++; lastArticle = 0 } else continue
            }
            lastArticle = no
            arts += ArticleHead(m.range.first, no, m.range.last + 1, segment)
        }
        if (arts.size < 2) return out

        arts.forEachIndexed { i, a ->
            val suffix = if (a.segment > 1) " (${a.segment})" else ""
            val bodyEnd = if (i + 1 < arts.size) arts[i + 1].start else src.length
            if (a.headerEnd >= bodyEnd) return@forEachIndexed
            var body = src.substring(a.headerEnd, bodyEnd)

            var heading = ""
            HEADING.find(body)?.let { hm ->
                val cand = hm.groupValues[2].trim()
                // 【】 는 제목 전용. ( ) 는 한글이 있을 때만 제목 - "(i)"·"(1)" 은 본문 문구라 남긴다.
                if (hm.groupValues[1] == "【" || HAS_HANGUL.containsMatchIn(cand)) {
                    heading = cand
                    body = body.substring(hm.range.last + 1)
                }
            }

            val paras = detectParagraphs(body)
            val articleRef = "제${a.no}조$suffix"
            if (paras.isEmpty()) {
                putIfText(out, articleRef, a.no, 0, heading, body)
                return@forEachIndexed
            }
            putIfText(out, articleRef, a.no, 0, heading, body.substring(0, paras[0].start))
            paras.forEachIndexed { j, p ->
                val pe = if (j + 1 < paras.size) paras[j + 1].start else body.length
                putIfText(out, "제${a.no}조 제${p.no}항$suffix", a.no, p.no, heading, body.substring(p.end, pe))
            }
        }
        return out
    }

    private fun detectParagraphs(body: String): List<ParaMark> =
        scan(body, P_CIRCLED, circled = true).ifEmpty { scan(body, P_JEHANG, false) }.ifEmpty { scan(body, P_NDOT, false) }

    /** 번호가 단조증가할 때만 항으로 인정(교차참조·호 재시작 배제). */
    private fun scan(body: String, p: Regex, circled: Boolean): List<ParaMark> {
        val res = mutableListOf<ParaMark>()
        var last = 0
        for (m in p.findAll(body)) {
            val no = if (circled) body[m.range.first].code - CIRCLED_ONE + 1 else m.groupValues[1].toIntOrNull() ?: continue
            if (no <= last) continue
            last = no
            res += ParaMark(m.range.first, no, m.range.last + 1)
        }
        return res
    }

    private fun putIfText(out: LinkedHashMap<String, Clause>, ref: String, articleNo: Int, paraNo: Int, heading: String, raw: String) {
        val t = clean(raw)
        if (t.isNotEmpty()) out.putIfAbsent(ref, Clause(ref, articleNo, paraNo, heading, t))
    }

    /** 줄바꿈(PDF 줄바꿈 포함)을 공백으로, 연속 공백 접기. NBSP 는 \s 에 안 걸려 먼저 바꾼다. */
    fun clean(s: String): String = s.replace(' ', ' ').replace(Regex("\\s+"), " ").trim()
}

/**
 * 같은 계약서의 N개 판을 조항 키로 매칭해 **달라진 조항만** 코드로 검출한다(환각·누락·거짓부재 없음).
 * 변경 여부는 공백 정규화 후 동등성(괄호 표기 변경은 실제 변경이라 지우지 않는다).
 * 조·항 구조를 못 읽는 문서는 2판에 한해 문단 단위 LCS 정렬로 폴백한다.
 */
object ContractVersionDiff {

    data class Version(val label: String, val clauses: Map<String, ContractClauseParser.Clause>)

    /** 달라진 조항 하나. texts: 버전 라벨 → 그 판 원문(없으면 null). */
    data class ChangedClause(val ref: String, val heading: String, val texts: LinkedHashMap<String, String?>)

    /**
     * @param total 비교 대상 조항 수(합집합) - 문단 모드에선 기준 문단 수
     * @param minor changed 중 공백·문장부호만 다른 경미 변경 수
     * @param mode "조·항" 또는 "문단"
     */
    data class DiffResult(val changed: List<ChangedClause>, val total: Int, val identical: Int, val minor: Int, val mode: String)

    fun diff(versions: List<Version>): DiffResult {
        if (versions.size < 2) return DiffResult(emptyList(), 0, 0, 0, "조·항")
        val keyHeading = LinkedHashMap<String, String>()
        versions.forEach { v -> v.clauses.forEach { (k, c) -> keyHeading.putIfAbsent(k, c.heading) } }

        var minor = 0
        val out = mutableListOf<ChangedClause>()
        for ((ref, heading) in keyHeading) {
            val texts = LinkedHashMap<String, String?>()
            versions.forEach { v -> texts[v.label] = v.clauses[ref]?.text }
            val present = texts.values.filterNotNull()
            val changed = present.size < versions.size || present.map(::norm).distinct().size > 1
            if (!changed) continue
            if (present.size == versions.size && minorOnly(present)) minor++
            out += ChangedClause(ref, heading, texts)
        }
        return DiffResult(out, keyHeading.size, keyHeading.size - out.size, minor, "조·항")
    }

    /** 조·항 구조가 없는 문서용 - 두 판의 문단 시퀀스를 LCS 로 정렬해 바뀐 구간만 뽑는다. */
    fun diffParagraphs(baseLabel: String, baseText: String, otherLabel: String, otherText: String): DiffResult {
        val a = splitParagraphs(baseText)
        val b = splitParagraphs(otherText)
        val hunks = lcsHunks(a, b)
        var minor = 0
        var changedBase = 0
        val out = hunks.mapIndexed { idx, (src, tgt) ->
            val baseJoined = src.takeIf { it.isNotEmpty() }?.joinToString(" / ")
            val otherJoined = tgt.takeIf { it.isNotEmpty() }?.joinToString(" / ")
            changedBase += src.size
            if (baseJoined != null && otherJoined != null && minorOnly(listOf(baseJoined, otherJoined))) minor++
            ChangedClause(
                "변경 ${idx + 1}",
                hint(baseJoined ?: otherJoined),
                linkedMapOf(baseLabel to baseJoined, otherLabel to otherJoined),
            )
        }
        return DiffResult(out, a.size, maxOf(0, a.size - changedBase), minor, "문단")
    }

    /** 공백·문장부호를 모두 지웠을 때 같으면 경미(띄어쓰기·마침표·괄호만 바뀜). 조사·서식은 평문으로 가를 수 없다. */
    fun minorOnly(present: List<String>): Boolean {
        if (present.size < 2) return false
        val base = stripPunctSpace(present[0])
        return base.isNotEmpty() && present.drop(1).all { stripPunctSpace(it) == base }
    }

    /**
     * 변경 유형 확정 - 첫 판에 없고 마지막 판에 있으면 신설, 반대면 삭제(코드가 판정). 그 외는 AI 라벨을
     * 정해진 다섯 값으로만 받아들이고 나머지는 '수정'.
     */
    fun resolveChangeType(llmType: String?, texts: Map<String, String?>): String {
        if (texts.size >= 2) {
            val firstMissing = texts.values.first().isNullOrBlank()
            val lastMissing = texts.values.last().isNullOrBlank()
            if (firstMissing && !lastMissing) return "신설"
            if (!firstMissing && lastMissing) return "삭제"
        }
        val t = llmType.orEmpty()
        return listOf("신설", "삭제", "이동", "오탈자").firstOrNull { t.contains(it) } ?: "수정"
    }

    /** LCS 표 칸 수 상한(약 16MB). 문단 2,000 x 2,000 수준까지는 정상 정렬한다. */
    private const val MAX_LCS_CELLS = 4_000_000L

    fun normRef(ref: String?): String = ref.orEmpty().replace(Regex("\\s+"), "")

    fun splitParagraphs(text: String): List<String> =
        text.split(Regex("\\r?\\n")).map { ContractClauseParser.clean(it) }.filter { it.length >= 2 }

    private fun norm(s: String) = s.replace(Regex("\\s+"), " ").trim()

    private fun stripPunctSpace(s: String) = s.replace(Regex("[\\s\\p{Punct}·…「」『』“”‘’—–]"), "")

    private fun hint(s: String?): String = s.orEmpty().let { if (it.length > 40) it.take(40) + "…" else it }

    /**
     * 최장 공통 부분열로 두 시퀀스를 정렬해, 공통이 아닌 연속 구간을 (기준 쪽 줄들, 비교 쪽 줄들) 쌍으로 돌려준다.
     * 계약서 문단 수(수백)에서는 O(n·m) 표로 충분하다.
     */
    private fun lcsHunks(fullA: List<String>, fullB: List<String>): List<Pair<List<String>, List<String>>> {
        // 같은 머리·꼬리는 표에 넣지 않는다 - 계약서 판 비교는 대부분이 동일 문단이라 표가 크게 줄어든다.
        var head = 0
        while (head < fullA.size && head < fullB.size && fullA[head] == fullB[head]) head++
        var tail = 0
        while (tail < fullA.size - head && tail < fullB.size - head &&
            fullA[fullA.size - 1 - tail] == fullB[fullB.size - 1 - tail]
        ) tail++
        val a = fullA.subList(head, fullA.size - tail)
        val b = fullB.subList(head, fullB.size - tail)
        // O(n·m) 표의 메모리 상한 - 넘으면 정렬 대신 통째로 한 덩어리 변경으로 본다(1GiB 인스턴스 보호).
        if (a.size.toLong() * b.size > MAX_LCS_CELLS) {
            return if (a.isEmpty() && b.isEmpty()) emptyList() else listOf(a to b)
        }
        val dp = Array(a.size + 1) { IntArray(b.size + 1) }
        for (i in a.indices.reversed()) for (j in b.indices.reversed()) {
            dp[i][j] = if (a[i] == b[j]) dp[i + 1][j + 1] + 1 else maxOf(dp[i + 1][j], dp[i][j + 1])
        }
        val hunks = mutableListOf<Pair<List<String>, List<String>>>()
        val src = mutableListOf<String>()
        val tgt = mutableListOf<String>()
        fun flush() {
            if (src.isNotEmpty() || tgt.isNotEmpty()) hunks += src.toList() to tgt.toList()
            src.clear(); tgt.clear()
        }
        var i = 0
        var j = 0
        while (i < a.size || j < b.size) {
            when {
                i < a.size && j < b.size && a[i] == b[j] -> { flush(); i++; j++ }
                j >= b.size || (i < a.size && dp[i + 1][j] >= dp[i][j + 1]) -> src += a[i++]
                else -> tgt += b[j++]
            }
        }
        flush()
        return hunks
    }
}
