package com.aixnative.document.service

/**
 * 추출 원문 → AI 입력용 텍스트 정제. 순수 함수(무상태)라 단위 테스트로 전부 덮인다.
 *
 * 두 가지를 한다.
 *  1. **노이즈 제거** - 페이지 번호, 목차 점선 줄, 문서 전체에 반복되는 머리말·꼬리말, 과다 공백.
 *     이것들은 토큰만 먹고 분석에 기여하지 않으며, 반복 머리말은 AI 가 본문으로 오인하기까지 한다.
 *  2. **구조 복원** - 장/절을 `#`, 조항을 `##`, 원 숫자(①②③)를 리스트로.
 *     계약서 검토는 "제12조 제(4)항" 같은 **조항 인용**이 결과 품질의 핵심인데, 추출 직후 텍스트는
 *     줄바꿈만 있는 평문이라 AI 가 조문 경계를 놓친다. 헤딩을 세워 주면 인용 정확도가 올라간다.
 */
object DocumentTextCleaner {

    /** 정제 + 구조 복원을 한 번에. */
    fun clean(text: String): String = toMarkdown(preprocess(text))

    /** 노이즈 제거만(구조 복원 없이). */
    fun preprocess(text: String): String {
        if (text.isBlank()) return ""
        var t = text.replace("\r\n", "\n").replace('\r', '\n')
        t = SPACES.replace(t, " ")
        t = PAGE_DASH.replace(t, "")
        t = PAGE_WORD.replace(t, "")
        t = PAGE_FRACTION.replace(t, "")
        t = PAGE_KO.replace(t, "")
        t = TOC_DOTS.replace(t, "")
        t = removeRepeatedLines(t)
        t = BLANK_RUN.replace(t, "\n\n")
        return t.trim()
    }

    /** 장/절·조항 헤딩과 원 숫자 리스트를 세운다. */
    fun toMarkdown(text: String): String {
        if (text.isBlank()) return ""
        val out = StringBuilder()
        for (raw in text.split('\n')) {
            val line = raw.trim()
            if (line.isEmpty()) {
                out.append('\n')
                continue
            }
            val chapter = CHAPTER.find(line)
            if (chapter != null) {
                val title = chapter.groupValues[2].trim()
                out.append("\n# ").append(chapter.groupValues[1].replace(" ", ""))
                if (title.isNotEmpty()) out.append(' ').append(title)
                out.append("\n\n")
                continue
            }

            val article = ARTICLE.find(line)
            if (article != null) {
                val num = article.groupValues[1].replace(" ", "")
                val title = article.groupValues[2].trim()
                out.append("\n## ").append(num)
                if (title.isNotEmpty()) out.append(" (").append(title).append(')')
                out.append("\n\n")
                val rest = line.substring(article.range.last + 1).trim()
                if (rest.isNotEmpty()) out.append(circlesToList(rest)).append('\n')
                continue
            }

            out.append(subItemsToList(circlesToList(line))).append('\n')
        }
        return out.toString().trim()
    }

    /**
     * 원 숫자(①②③…)를 `- (n)` 리스트로. 한 줄에 여러 항이 붙어 나오는 경우가 흔해 줄바꿈도 함께 넣는다.
     * 맨 앞 항목 앞의 줄바꿈은 다시 걷어내 빈 줄이 생기지 않게 한다.
     */
    fun circlesToList(text: String): String {
        if (CIRCLES.none { text.contains(it) }) return text
        var t = text
        CIRCLES.forEachIndexed { i, c -> t = t.replace(c.toString(), "\n- (${i + 1}) ") }
        return t.trimStart('\n')
    }

    /**
     * 줄 첫머리의 "1호." / "2호:" 를 리스트로. **구두점을 반드시 요구한다** -
     * 요구하지 않으면 "5호선"·"3호 라인" 같은 일반 명사까지 잘려 문장이 깨진다.
     */
    fun subItemsToList(text: String): String = SUBITEM.replace(text) { m -> "  - ${m.groupValues[1]}호. " }

    /**
     * 문서 전체에서 [REPEAT_THRESHOLD] 번 이상 나오는 짧은 줄을 지운다(머리말·꼬리말·워터마크).
     * 길이 제한을 두는 이유는 실제 본문 문장이 우연히 반복될 때 지워지지 않게 하기 위함이다.
     */
    fun removeRepeatedLines(text: String): String {
        val lines = text.split('\n')
        val counts = HashMap<String, Int>()
        for (line in lines) {
            val t = line.trim()
            if (t.isNotEmpty() && t.length < REPEAT_MAX_LEN) counts[t] = (counts[t] ?: 0) + 1
        }
        val repeated = counts.filterValues { it >= REPEAT_THRESHOLD }.keys
        if (repeated.isEmpty()) return text
        return lines.filterNot { it.trim() in repeated }.joinToString("\n")
    }

    private val SPACES = Regex("[ \\t]+")
    private val BLANK_RUN = Regex("\\n{3,}")
    private val PAGE_DASH = Regex("(?m)^\\s*-\\s*\\d+\\s*-\\s*$")
    private val PAGE_WORD = Regex("(?im)^\\s*page\\s+\\d+\\s*$")
    private val PAGE_FRACTION = Regex("(?m)^\\s*\\d+\\s*/\\s*\\d+\\s*$")
    private val PAGE_KO = Regex("(?m)^\\s*페이지\\s*\\d+\\s*$")

    /** 목차 줄 - 제목 다음에 점선이 이어지고 페이지 번호로 끝난다. */
    private val TOC_DOTS = Regex("(?m)^.*\\.{4,}.*\\d+\\s*$")

    private val CHAPTER = Regex("^(제\\s*\\d+\\s*[장절편])\\s*(.*)$")
    private val ARTICLE = Regex("^(제\\s*\\d+\\s*조)\\s*[(\\[]?([^)\\]\\n]*)[)\\]]?")
    private val SUBITEM = Regex("^\\s*(\\d+)\\s*호\\s*[.:]\\s*")

    private val CIRCLES = listOf('①', '②', '③', '④', '⑤', '⑥', '⑦', '⑧', '⑨', '⑩', '⑪', '⑫', '⑬', '⑭', '⑮')

    /** 이 횟수 이상 반복되면 머리말·꼬리말로 본다. */
    private const val REPEAT_THRESHOLD = 5

    /** 반복 제거 대상으로 볼 줄 길이 상한 - 긴 문장은 본문일 가능성이 높다. */
    private const val REPEAT_MAX_LEN = 50
}
