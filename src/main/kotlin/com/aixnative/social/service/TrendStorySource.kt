package com.aixnative.social.service

import com.aixnative.social.domain.RiskLevel
import com.aixnative.social.domain.SourceType
import com.aixnative.social.domain.StoryDraft
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import kotlin.math.absoluteValue

/**
 * 구글 트렌드 급상승 검색어 스토리 소스([StorySource]) - trending/rss?geo=KR 의 각 검색어에 붙어오는
 * 대표 관련 기사(ht:news_item_url)를 딥페치 대상으로 삼아 스토리 1건씩 만든다.
 * 커뮤니티가 당일 소진돼도 새 소재를 공급(중복 차단은 오케스트레이터가 검색어 해시+날짜로).
 *
 * 검색어는 "화제성" 신호라 검증 안 된 내용이 섞일 수 있어 리스크 MEDIUM. 실패 시 graceful 빈결과.
 */
@Component
class TrendStorySource(
    private val rssFetcher: RssFetcher,
    private val props: SocialProperties,
) : StorySource {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun produce(): List<StoryDraft> {
        if (!props.trendStoryEnabled) return emptyList()
        val items = runCatching { rssFetcher.fetch(TREND_URL, "Google Trends") }
            .getOrElse { log.warn("[social][trend] 스토리 수집 실패: {}", it.message); return emptyList() }

        return items.asSequence()
            .filter { !it.newsUrl.isNullOrBlank() } // 딥페치할 기사 URL 이 있어야 스토리화 가능
            .take(props.trendStoryMax)
            .map { item ->
                val term = item.title.trim()
                StoryDraft(
                    board = BOARD,
                    url = item.newsUrl!!,
                    title = term, // 표지 각색 기본값(검색어). Claude 가 본문 기반 제목 생성.
                    engagement = item.approxTraffic?.let { "검색 $it" },
                    riskLevel = RiskLevel.MEDIUM,
                    dedupSuffix = "trendstory:${term.hashCode().absoluteValue}",
                    sourceType = SourceType.TREND,
                )
            }
            .toList()
    }

    private companion object {
        const val TREND_URL = "https://trends.google.com/trending/rss?geo=KR"
        const val BOARD = "지금 뜨는 검색어"
    }
}
