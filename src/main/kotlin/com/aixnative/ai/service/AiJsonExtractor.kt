package com.aixnative.ai.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory

/**
 * AI 응답 텍스트 → JSON 파싱 공용 유틸(무상태).
 *
 * "JSON 만 내라"고 프롬프트로 못박아도 모델은 코드펜스를 두르거나 앞뒤에 한 줄 설명을 붙이곤 한다.
 * 그 처리를 서비스마다 따로 두면 조금씩 다르게 틀리므로 한 곳에 모은다.
 *
 * [isUsable] 는 "파싱은 됐지만 알맹이가 없는" 응답을 걸러 **재요청(repair) 여부를 판단**하는 데 쓴다.
 * 파싱 성공 여부만 보면, 모델이 `{}` 하나만 뱉었을 때도 성공으로 처리돼 빈 결과가 사용자에게 나간다.
 */
object AiJsonExtractor {

    private val log = LoggerFactory.getLogger(javaClass)
    private val mapper = ObjectMapper()

    /**
     * 코드펜스·앞뒤 잡텍스트를 걷어내고 첫 `{` ~ 마지막 `}` 를 잘라낸다. 실패 시 null.
     */
    fun sliceJsonObject(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        var s = raw.trim()
        if (s.startsWith("```")) {
            val firstNewline = s.indexOf('\n')
            if (firstNewline > 0) s = s.substring(firstNewline + 1)
            val lastFence = s.lastIndexOf("```")
            if (lastFence > 0) s = s.substring(0, lastFence)
            s = s.trim()
        }
        val start = s.indexOf('{')
        val end = s.lastIndexOf('}')
        return if (start in 0 until end) s.substring(start, end + 1) else null
    }

    /** 응답 텍스트 → JsonNode. 잘라내기·파싱 중 어디서 실패하든 null(호출부가 raw 폴백을 결정). */
    fun parse(raw: String?): JsonNode? {
        val json = sliceJsonObject(raw) ?: return null
        return try {
            mapper.readTree(json)
        } catch (e: Exception) {
            log.warn("[AI] 응답 JSON 파싱 실패: {}", e.message)
            null
        }
    }

    /**
     * 결과가 쓸 만한지 - 객체이고 [requiredAnyOf] 중 하나 이상이 null 이 아닌 값으로 존재하면 true.
     * 키를 주지 않으면 "비어 있지 않은 객체"인지만 본다.
     */
    fun isUsable(node: JsonNode?, vararg requiredAnyOf: String): Boolean {
        if (node == null || !node.isObject || node.isEmpty) return false
        if (requiredAnyOf.isEmpty()) return true
        return requiredAnyOf.any { key ->
            val v = node.get(key)
            v != null && !v.isNull
        }
    }

    /** [parse] + [isUsable] 를 한 번에. 쓸 만하지 않으면 null 을 돌려 호출부가 재요청하게 한다. */
    fun parseUsable(raw: String?, vararg requiredAnyOf: String): JsonNode? =
        parse(raw)?.takeIf { isUsable(it, *requiredAnyOf) }
}
