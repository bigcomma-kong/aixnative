package com.aixnative.social.service

import com.aixnative.ai.service.ClaudeClient
import com.aixnative.social.domain.SocialPost
import com.aixnative.social.domain.SocialPostKind
import com.aixnative.social.domain.SourceRef
import com.aixnative.social.domain.StoryDraft
import com.aixnative.social.domain.StoryScene
import com.aixnative.social.domain.StoryScript
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * 스토리 스크립트 각색 - 커뮤니티 핫글 본문을 [ClaudeClient] **직접** 호출로 장면별 스토리로 각색.
 * [SocialCaptionGenerator] 패턴 미러링(라우터/크레딧 게이트 미경유, 운영 계정 비용, graceful null).
 *
 * 저작권 안전선: 원문 복제 금지 + 재작성 + 실명/특정인물 이미지 프롬프트 금지 + 출처 유지.
 * 반환 = DRAFT [SocialPost](kind=STORY, slides_json=StoryScript(장면 imageB64=null), 이미지 미생성).
 */
@Component
class StoryScriptGenerator(
    private val claudeClient: ClaudeClient,
    private val fetcher: CommunityArticleFetcher,
    private val objectMapper: ObjectMapper,
    private val props: SocialProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** @return DRAFT 스토리 게시물(미저장) 또는 null(Claude 미설정/본문없음/파싱실패). */
    fun generate(draft: StoryDraft): SocialPost? {
        if (!claudeClient.isConfigured()) {
            log.info("[social][story] Claude 미설정 - 각색 생략")
            return null
        }
        val article = fetcher.fetch(draft.url) ?: run {
            log.info("[social][story] '{}' 본문 딥페치 실패 - 생략", draft.title)
            return null
        }

        val raw = claudeClient.complete(buildPrompt(draft, article.bodyText))
        val json = extractJson(raw) ?: run {
            log.warn("[social][story] '{}' JSON 추출 실패", draft.title)
            return null
        }
        val node = objectMapper.readTree(json)

        val scenes = node.path("scenes").takeIf { it.isArray }?.mapNotNull { s ->
            val caption = s.path("caption").asText("").ifBlank { null } ?: return@mapNotNull null
            val prompt = s.path("imagePrompt").asText("").ifBlank { caption }
            StoryScene(caption = caption.take(220), imagePrompt = prompt.take(600), imageB64 = null)
        }.orEmpty().take(props.storyMaxScenes)
        if (scenes.isEmpty()) {
            log.warn("[social][story] '{}' 장면 파싱 결과 없음", draft.title)
            return null
        }

        val script = StoryScript(scenes = scenes, outro = node.path("outro").asText("").ifBlank { null }?.take(400))
        val refs = listOf(SourceRef(name = draft.board, url = draft.url))

        return SocialPost(
            topic = draft.board.take(80),
            title = node.path("coverTitle").asText("").ifBlank { draft.title }.take(300),
            captionText = node.path("caption").asText("").ifBlank { null },
            slidesJson = objectMapper.writeValueAsString(script),
            hashtags = node.path("hashtags").asText("").ifBlank { null }?.take(500),
            sourceRefsJson = objectMapper.writeValueAsString(refs),
            aiProvider = "Claude",
            sourceType = draft.sourceType,
            riskLevel = draft.riskLevel,
            kind = SocialPostKind.STORY,
            engagement = draft.engagement?.take(120),
            sourceBoard = draft.board.take(120),
        )
    }

    private fun buildPrompt(draft: StoryDraft, body: String): String {
        return """
            당신은 한국어 소셜 미디어 스토리 에디터입니다. 아래 커뮤니티 인기글을 바탕으로
            인스타그램 카드뉴스용 **스토리**를 만드세요. 하나의 글을 장면별로 흥미롭게 재구성합니다.

            규칙(엄수):
            - 원문 문장을 그대로 복사하지 마세요. 당신의 말로 새로 씁니다(한국어).
            - 장면 ${props.storyMaxScenes}개 이내로, 도입→전개→반전/결말 흐름.
            - 각 장면: caption(자막 1~2문장) + imagePrompt(그 장면을 묘사하는 **영어** 이미지 생성 프롬프트).
            - imagePrompt 는 사실적/일러스트 묘사로. **실명·특정 실존인물 얼굴·로고·상표 금지**(일반화).
            - 과장·허위·혐오 금지. 제공된 글 범위 안에서만.

            반드시 아래 스키마의 JSON 객체 하나만 출력(코드블록·설명 금지):
            {
              "coverTitle": "표지 제목(글 제목 각색, 30자 이내)",
              "caption": "인스타 캡션 본문 2~4문장 (마지막에 팔로우 유도 한 문장)",
              "hashtags": "#태그1 #태그2 (5~10개, 공백 구분)",
              "scenes": [ {"caption":"장면 자막(한국어)", "imagePrompt":"english image prompt, no text, no real person"} ],
              "outro": "왜 화제가 됐는지 한 문단 요약(한국어)"
            }

            <출처>${draft.board}</출처>
            <제목>${draft.title}</제목>
            <본문>
            ${body}
            </본문>
        """.trimIndent()
    }

    /** 코드펜스/잡텍스트를 걷어내고 첫 '{' ~ 마지막 '}' 구간을 JSON 으로 추출. */
    private fun extractJson(raw: String): String? {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        return if (start in 0 until end) raw.substring(start, end + 1) else null
    }
}
