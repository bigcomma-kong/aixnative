package com.aixnative.document

import com.aixnative.document.service.DocumentTextCleaner
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 정제기는 순수 함수라 전부 여기서 덮인다. 회귀가 나면 AI 입력 품질이 조용히 나빠지는 지점이라
 * "무엇을 지우고 무엇을 남기는지"를 양쪽 다 단언한다.
 */
class DocumentTextCleanerTest {

    @Test
    fun `페이지 번호 줄을 지운다`() {
        val input = """
            본문 첫 문단입니다.
            - 3 -
            Page 12
            4 / 10
            페이지 7
            본문 둘째 문단입니다.
        """.trimIndent()

        val out = DocumentTextCleaner.preprocess(input)

        assertTrue(out.contains("본문 첫 문단입니다."))
        assertTrue(out.contains("본문 둘째 문단입니다."))
        assertFalse(out.contains("- 3 -"))
        assertFalse(out.contains("Page 12"))
        assertFalse(out.contains("4 / 10"))
        assertFalse(out.contains("페이지 7"))
    }

    @Test
    fun `목차 점선 줄을 지운다`() {
        val input = "제1장 총칙 ........................ 3\n실제 본문 내용."
        val out = DocumentTextCleaner.preprocess(input)
        assertFalse(out.contains("........"))
        assertTrue(out.contains("실제 본문 내용."))
    }

    @Test
    fun `다섯 번 이상 반복되는 짧은 줄은 머리말로 보고 지운다`() {
        val header = "대외비 - 무단 배포 금지"
        val input = (1..6).joinToString("\n") { "$header\n본문 $it 번째 문단입니다." }

        val out = DocumentTextCleaner.removeRepeatedLines(input)

        assertFalse(out.contains(header))
        assertTrue(out.contains("본문 1 번째 문단입니다."))
        assertTrue(out.contains("본문 6 번째 문단입니다."))
    }

    @Test
    fun `반복 횟수가 적으면 지우지 않는다`() {
        val input = "같은 줄\n다른 줄\n같은 줄"
        assertEquals(input, DocumentTextCleaner.removeRepeatedLines(input))
    }

    @Test
    fun `장과 조를 마크다운 헤딩으로 세운다`() {
        val input = "제1장 총칙\n제 12 조 (계약의 목적) 본 계약은 ...\n일반 문장."

        val out = DocumentTextCleaner.toMarkdown(input)

        assertTrue(out.contains("# 제1장 총칙"), out)
        assertTrue(out.contains("## 제12조 (계약의 목적)"), out)
        assertTrue(out.contains("일반 문장."))
    }

    @Test
    fun `원 숫자를 리스트로 바꾼다`() {
        val out = DocumentTextCleaner.circlesToList("①첫째 항목 ②둘째 항목")
        assertTrue(out.startsWith("- (1) 첫째 항목"), out)
        assertTrue(out.contains("- (2) 둘째 항목"), out)
    }

    @Test
    fun `호 표기는 구두점이 있을 때만 리스트로 바꾼다`() {
        // 바꾼다 - 줄 첫머리 + 구두점
        assertTrue(DocumentTextCleaner.subItemsToList("1호. 대상 물건").contains("- 1호."))
        // 안 바꾼다 - "5호선"처럼 일반 명사의 일부인 경우(원본 로직의 오작동 지점)
        val untouched = "5호선 역세권 물건"
        assertEquals(untouched, DocumentTextCleaner.subItemsToList(untouched))
    }

    @Test
    fun `빈 입력은 빈 문자열을 돌려준다`() {
        assertEquals("", DocumentTextCleaner.clean(""))
        assertEquals("", DocumentTextCleaner.clean("   \n  \n "))
    }
}
