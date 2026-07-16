package com.aixnative.social.service

import com.aixnative.social.domain.RiskLevel
import com.aixnative.social.domain.StoryDraft
import org.jsoup.nodes.Element
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import kotlin.math.absoluteValue

/**
 * 커뮤니티 핫글 리스트 소스([StorySource]) - 설정된 대상(social.community-story-targets)의
 * 베스트/인기 게시판을 [ScrapingProxy] 로 스크랩해 상위 핫글 각각을 스토리 초안으로.
 * 리스트에서 제목·링크·(가능하면)참여수만 추출(본문은 [CommunityArticleFetcher] 가 딥페치).
 * 항상 리스크 HIGH. 차단/JS 로 막히면 graceful 빈결과. 기본 대상 비어있어 명시적 on 시에만.
 */
@Component
class CommunityHotSource(
    private val props: SocialProperties,
    private val scrapingProxy: ScrapingProxy,
) : StorySource {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun produce(): List<StoryDraft> {
        if (props.communityStoryTargets.isEmpty()) return emptyList()
        return props.communityStoryTargets.flatMap { target ->
            runCatching { scrapeList(target.url, target.label) }
                .getOrElse { log.warn("[social][story] '{}' 리스트 수집 실패: {}", target.label, it.message); emptyList() }
        }
    }

    private fun scrapeList(url: String, board: String): List<StoryDraft> {
        val doc = scrapingProxy.fetch(url) ?: run {
            log.info("[social][story] '{}' fetch 실패(프록시/직접)", board); return emptyList()
        }
        // 제목 앵커 후보: 링크 텍스트가 제목 길이대 + href 가 게시글 퍼머링크 형태(네비/공지/위젯 배제).
        val anchors = doc.select("a[href]").asSequence()
            .filter { a ->
                val href = a.absUrl("href")
                a.text().trim().length in TITLE_MIN..TITLE_MAX &&
                    href.startsWith("http") &&
                    POST_LINK.containsMatchIn(href)
            }
            .distinctBy { it.absUrl("href") }
            .take(props.storyPostsPerTarget)
            .toList()
        if (anchors.isEmpty()) {
            log.info("[social][story] '{}' 핫글 앵커 없음(정적 파싱 한계 가능)", board)
        }
        return anchors.map { a ->
            val link = a.absUrl("href")
            StoryDraft(
                board = board,
                url = link,
                title = cleanTitle(a.text()),
                engagement = nearbyEngagement(a),
                riskLevel = RiskLevel.HIGH,
                dedupSuffix = "story:$board:${link.hashCode().absoluteValue}",
            )
        }
    }

    /** 제목 끝의 댓글수 꼬리표([636]·(12) 등)와 잉여 공백 제거. */
    private fun cleanTitle(raw: String): String =
        raw.replace(TRAILING_COUNT, "").replace(WS, " ").trim()

    /** 앵커 주변에서 추천/조회 수치 텍스트를 긁는 휴리스틱(이모지 없이, 없으면 null). */
    private fun nearbyEngagement(anchor: Element): String? {
        val scope = anchor.parent()?.parent() ?: anchor.parent() ?: return null
        val nums = NUM_REGEX.findAll(scope.text()).map { it.value }.take(2).toList()
        return when {
            nums.size >= 2 -> "추천 ${nums[0]} · 댓글 ${nums[1]}"
            nums.size == 1 -> "추천 ${nums[0]}"
            else -> null
        }
    }

    private companion object {
        const val TITLE_MIN = 8
        const val TITLE_MAX = 80
        val NUM_REGEX = Regex("\\b\\d[\\d,.]*(?:만|천)?\\b")
        // 게시글 퍼머링크 형태(에펨 document_srl·디시 board/view·ppomppu view.php·숫자 ID 경로 등). 네비/공지 배제.
        val POST_LINK = Regex("document_srl=|[?&]no=|view\\.php|/board/view/|/\\d{6,}(?:[/?#]|$)")
        val TRAILING_COUNT = Regex("[\\[(]\\d[\\d,]*[\\])]\\s*$")
        val WS = Regex("\\s+")
    }
}
