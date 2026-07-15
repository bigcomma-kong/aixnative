package com.aixnative.social.service

import com.aixnative.social.domain.CardDraft
import com.aixnative.social.domain.RiskLevel
import com.aixnative.social.domain.SourceType
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * 유튜브 인기영상 소스 - 카테고리별 [YoutubePopularClient.mostPopular] 결과를 카드 초안으로.
 * "진짜 인기"(플랫폼 공식 랭킹) 신호라 리스크 LOW. 키 미설정 시 빈 목록(graceful).
 */
@Component
class YoutubePopularSource(
    private val client: YoutubePopularClient,
    private val props: SocialProperties,
) : CardSource {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun produce(): List<CardDraft> {
        if (!props.youtubeEnabled) return emptyList()
        return props.youtubeCategories.mapNotNull { cat ->
            val articles = client.mostPopular(cat.id, props.rankSize)
            if (articles.isEmpty()) {
                log.info("[social][youtube] category={} 결과 없음", cat.label)
                return@mapNotNull null
            }
            CardDraft(
                title = "오늘 유튜브 ${cat.label} 인기 TOP ${articles.size.coerceAtMost(props.rankSize)}",
                sourceType = SourceType.YOUTUBE,
                riskLevel = RiskLevel.LOW,
                dedupSuffix = "youtube:${cat.id}",
                topic = "유튜브 ${cat.label} 인기영상",
                articles = articles.take(props.rankSize),
            )
        }
    }
}
