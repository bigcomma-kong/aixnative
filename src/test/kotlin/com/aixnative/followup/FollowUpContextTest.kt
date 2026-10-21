package com.aixnative.followup

import com.aixnative.followup.domain.FollowUpRole
import com.aixnative.followup.domain.FollowUpTurn
import com.aixnative.followup.service.FollowUpContext
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FollowUpContextTest {

    private fun turn(seq: Int, role: FollowUpRole, content: String) =
        FollowUpTurn(runId = 1, turnSeq = seq, role = role, content = content)

    @Test
    fun `subject only when there are no siblings or prior turns`() {
        val ctx = FollowUpContext.build("IC_MEMO", """{"analysis":{"verdict":"GO"}}""", emptyMap(), emptyList())

        assertTrue(ctx.startsWith("<분석 결과>"))
        assertFalse(ctx.contains("<참고 결과>"))
        assertFalse(ctx.contains("<이전 논의>"))
    }

    @Test
    fun `siblings follow stage order, exclude the subject tool and are labeled`() {
        val siblings = mapOf(
            "IC_MEMO" to "투심본문",
            "UNDERWRITING_NARRATIVE" to "언더본문",
            "DEAL_SCREENING" to "스크본문",
        )
        val ctx = FollowUpContext.build("IC_MEMO", "{}", siblings, emptyList())

        // 투심 메모가 주 결과이므로 참고에서는 빠지고, 스크리닝 → 언더라이팅 순으로 라벨이 붙는다.
        assertFalse(ctx.contains("[투심 메모]"))
        assertTrue(ctx.indexOf("[1차 스크리닝]") < ctx.indexOf("[언더라이팅]"))
        assertTrue(ctx.contains("언더본문"))
    }

    @Test
    fun `sibling text is capped per stage and in total`() {
        val big = "가".repeat(FollowUpContext.SIBLING_EACH_MAX * 2)
        val siblings = FollowUpContext.STAGE_LABELS.keys.associateWith { big }
        val ctx = FollowUpContext.build("NONE", "{}", siblings, emptyList())

        val refBody = ctx.substringAfter("<참고 결과>").substringBefore("</참고 결과>")
        val totalChars = refBody.count { it == '가' }
        assertTrue(totalChars <= FollowUpContext.SIBLING_TOTAL_MAX, "총 상한 초과: $totalChars")
        assertEquals(FollowUpContext.SIBLING_EACH_MAX, refBody.substringAfter("]\n").substringBefore("…").length)
    }

    @Test
    fun `prior turns keep only the most recent and skip critique json`() {
        val prior = (0 until 20).map { turn(it, FollowUpRole.USER, "질문$it") } +
            turn(20, FollowUpRole.CRITIQUE, """{"verdict":"REVISE"}""")
        val ctx = FollowUpContext.build("IC_MEMO", "{}", emptyMap(), prior)

        assertFalse(ctx.contains("질문7\n"), "오래된 턴은 잘려야 한다")
        assertTrue(ctx.contains("- 사용자 질문: 질문19"))
        assertFalse(ctx.contains("REVISE"), "반박 검토 JSON 은 대화 맥락에 넣지 않는다")
        val lines = ctx.substringAfter("<이전 논의>").lines().count { it.startsWith("- ") }
        assertEquals(FollowUpContext.MAX_PRIOR_TURNS, lines)
    }
}
