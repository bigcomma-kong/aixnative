package com.aixnative.social.service

import com.aixnative.social.domain.RiskLevel
import com.aixnative.social.domain.StoryDraft
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import kotlin.math.absoluteValue

/**
 * 커뮤니티 핫글 리스트 소스([StorySource]) - 설정된 대상(social.community-story-targets)의
 * 베스트/인기 게시판을 Jsoup 정적 스크랩해 상위 핫글 각각을 스토리 초안으로.
 * 리스트에서 제목·링크·(가능하면)참여수만 추출(본문은 [CommunityArticleFetcher] 가 딥페치).
 * 항상 리스크 HIGH. 봇차단/JS 로 막히면 graceful 빈결과. 기본 대상 비어있어 명시적 on 시에만.
 */
@Component
class CommunityHotSource(
    private val props: SocialProperties,
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
        val doc = Jsoup.connect(url)
            .userAgent(USER_AGENT).timeout(TIMEOUT_MS).followRedirects(true).get()
        // 제목 앵커 후보: 링크 텍스트가 제목 길이대인 것들.
        val anchors = doc.select("a[href]").asSequence()
            .filter { it.text().trim().length in TITLE_MIN..TITLE_MAX && it.absUrl("href").startsWith("http") }
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
                title = a.text().trim(),
                engagement = nearbyEngagement(a),
                riskLevel = RiskLevel.HIGH,
                dedupSuffix = "story:$board:${link.hashCode().absoluteValue}",
            )
        }
    }

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
        const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
        const val TIMEOUT_MS = 8_000
        const val TITLE_MIN = 8
        const val TITLE_MAX = 80
        val NUM_REGEX = Regex("\\b\\d[\\d,.]*(?:만|천)?\\b")
    }
}
