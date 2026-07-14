package com.aixnative.social.service

import com.aixnative.ai.service.ClaudeClient
import com.aixnative.social.domain.RankSlide
import com.aixnative.social.domain.SocialPost
import com.aixnative.social.domain.SourceArticle
import com.aixnative.social.domain.SourceRef
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * 랭킹 카드 캡션 생성 - [ClaudeClient] **직접** 호출(라우터/크레딧 게이트 미경유, 운영 계정 비용).
 * 자동 배경 작업이라 사용자 크레딧을 차감하지 않는다. Claude 미설정 시 null(graceful).
 *
 * 저작권 안전선: 프롬프트에서 원문 복제 금지 + 새 요약 + 출처 유지를 강제한다.
 * 입력 = 주제 소재 기사 풀, 출력 = DRAFT 상태 [SocialPost](제목/슬라이드/캡션/출처, 이미지 미렌더).
 */
@Component
class SocialCaptionGenerator(
    private val claudeClient: ClaudeClient,
    private val objectMapper: ObjectMapper,
    private val props: SocialProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** @return DRAFT 게시물(미저장) 또는 null(Claude 미설정/소재 없음/파싱 실패). */
    fun generate(topic: String, sources: List<SourceArticle>): SocialPost? {
        if (!claudeClient.isConfigured()) {
            log.info("[social] Claude 미설정 - 캡션 생성 생략")
            return null
        }
        if (sources.isEmpty()) {
            log.info("[social] 주제 '{}' 소재 없음 - 생략", topic)
            return null
        }
        val n = props.rankSize.coerceAtMost(sources.size)
        val raw = claudeClient.complete(buildPrompt(topic, sources, n))
        val json = extractJson(raw) ?: run {
            log.warn("[social] 주제 '{}' 응답에서 JSON 추출 실패", topic)
            return null
        }
        val node = objectMapper.readTree(json)

        val slides = node.path("slides").takeIf { it.isArray }?.mapIndexedNotNull { i, s ->
            val title = s.path("title").asText("").ifBlank { null } ?: return@mapIndexedNotNull null
            RankSlide(
                rank = s.path("rank").asInt(i + 1),
                title = title.take(120),
                summary = s.path("summary").asText("").take(400),
                sourceName = s.path("sourceName").asText("").take(80),
                sourceUrl = s.path("sourceUrl").asText("").take(500),
            )
        }.orEmpty()
        if (slides.isEmpty()) {
            log.warn("[social] 주제 '{}' 슬라이드 파싱 결과 없음", topic)
            return null
        }

        val refs = slides
            .filter { it.sourceUrl.isNotBlank() }
            .map { SourceRef(name = it.sourceName.ifBlank { "출처" }, url = it.sourceUrl) }

        return SocialPost(
            topic = topic.take(80),
            title = node.path("title").asText("").ifBlank { "이번 주 $topic TOP $n" }.take(300),
            captionText = node.path("caption").asText("").ifBlank { null },
            slidesJson = objectMapper.writeValueAsString(slides),
            hashtags = node.path("hashtags").asText("").ifBlank { null }?.take(500),
            sourceRefsJson = objectMapper.writeValueAsString(refs),
            aiProvider = "Claude",
        )
    }

    private fun buildPrompt(topic: String, sources: List<SourceArticle>, n: Int): String {
        val lines = sources.mapIndexed { i, a ->
            "${i + 1}. [${a.source}] ${a.title}${if (a.summary.isNotBlank()) " — ${a.summary.take(200)}" else ""} (${a.link})"
        }.joinToString("\n")
        return """
            당신은 한국어 소셜 미디어 콘텐츠 에디터입니다. 아래 '$topic' 관련 최신 기사들을 바탕으로
            인스타그램 랭킹 카드뉴스 "이번 주 $topic 화제 TOP $n" 를 만드세요.

            규칙(엄수):
            - 원문 문장을 그대로 복사하지 마세요. 각 항목 요약은 당신이 새로 쓴 1~2문장(한국어).
            - 공감되고 흥미로운 순서로 상위 ${n}개를 선별.
            - 각 항목에 출처 매체명(sourceName)과 원문 링크(sourceUrl)를 그대로 유지(저작권 표기).
            - 과장·허위 금지. 제공된 기사 범위 안에서만 작성.

            반드시 아래 스키마의 JSON 객체 하나만 출력하세요(코드블록·설명 금지):
            {
              "title": "카드 대표 제목 (40자 이내)",
              "caption": "인스타 캡션 본문 2~4문장 (마지막에 팔로우 유도 한 문장)",
              "hashtags": "#태그1 #태그2 (5~10개, 공백 구분)",
              "slides": [
                {"rank":1,"title":"항목 제목(30자 이내)","summary":"새로 쓴 요약 1~2문장","sourceName":"매체명","sourceUrl":"원문링크"}
              ]
            }
            slides 는 정확히 ${n}개. 모든 값은 한국어.

            <기사>
            $lines
            </기사>
        """.trimIndent()
    }

    /** 코드펜스/잡텍스트를 걷어내고 첫 '{' ~ 마지막 '}' 구간을 JSON 으로 추출. */
    private fun extractJson(raw: String): String? {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        return if (start in 0 until end) raw.substring(start, end + 1) else null
    }
}
