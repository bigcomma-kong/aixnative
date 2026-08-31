package com.aixnative.ai

import com.aixnative.ai.service.AiJsonExtractor
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * "JSON 만 내라"고 못박아도 모델은 코드펜스를 두르거나 앞뒤에 설명을 붙인다.
 * 이 유틸이 그 처리를 독점하므로(이전에는 6곳에 흩어져 있었다) 회귀는 여러 도구를 동시에 깬다.
 */
class AiJsonExtractorTest {

    @Test
    fun `코드펜스로 감싼 JSON 을 벗겨낸다`() {
        val raw = "```json\n{\"summary\":\"요약\"}\n```"
        val node = AiJsonExtractor.parse(raw)
        assertNotNull(node)
        assertEquals("요약", node.get("summary").asText())
    }

    @Test
    fun `앞뒤 설명 문장을 걷어낸다`() {
        val raw = "다음과 같이 분석했습니다.\n{\"overallRisk\":\"HIGH\"}\n이상입니다."
        val node = AiJsonExtractor.parse(raw)
        assertNotNull(node)
        assertEquals("HIGH", node.get("overallRisk").asText())
    }

    @Test
    fun `중첩 객체가 있어도 마지막 닫는 괄호까지 잡는다`() {
        val raw = "{\"a\":{\"b\":1},\"c\":[{\"d\":2}]}"
        val node = AiJsonExtractor.parse(raw)
        assertNotNull(node)
        assertEquals(2, node.get("c").get(0).get("d").asInt())
    }

    @Test
    fun `깨진 JSON 은 null`() {
        assertNull(AiJsonExtractor.parse("{ 이건 JSON 이 아님 "))
        assertNull(AiJsonExtractor.parse("설명만 있고 객체가 없습니다."))
        assertNull(AiJsonExtractor.parse(null))
        assertNull(AiJsonExtractor.parse(""))
    }

    @Test
    fun `slice 는 객체 구간만 잘라낸다`() {
        assertEquals("{\"a\":1}", AiJsonExtractor.sliceJsonObject("앞 {\"a\":1} 뒤"))
        assertNull(AiJsonExtractor.sliceJsonObject("}{"))
    }

    @Test
    fun `isUsable 은 any-of 로 알맹이를 판정한다`() {
        val node = AiJsonExtractor.parse("{\"summary\":\"있음\",\"parties\":null}")
        assertTrue(AiJsonExtractor.isUsable(node, "summary", "parties"))
        assertFalse(AiJsonExtractor.isUsable(node, "riskAssessment"))
    }

    @Test
    fun `빈 객체는 쓸 수 없다고 본다`() {
        // 모델이 {} 만 뱉는 경우 - 파싱은 되지만 재요청해야 한다.
        val empty = AiJsonExtractor.parse("{}")
        assertFalse(AiJsonExtractor.isUsable(empty))
        assertNull(AiJsonExtractor.parseUsable("{}", "summary"))
    }

    @Test
    fun `parseUsable 은 키가 하나라도 있으면 통과시킨다`() {
        assertNotNull(AiJsonExtractor.parseUsable("{\"target\":{\"name\":\"역삼\"}}", "target", "schedule"))
    }
}
