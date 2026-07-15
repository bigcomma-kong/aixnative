package com.aixnative.social.service

import com.aixnative.social.domain.CardDraft
import com.aixnative.social.domain.RiskLevel
import com.aixnative.social.domain.SourceArticle
import com.aixnative.social.domain.SourceType
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * 언론사 직접 RSS 소스 - 설정된 분야별 피드(구글뉴스 아님)를 최신순으로 카드화.
 * 구글뉴스 RSS 가 Cloud Run 송신 IP 에서 빈응답 나던 문제를 우회(직접 RSS 는 정상 동작).
 * 매체 발신이라 리스크 LOW. 피드 실패는 개별 graceful.
 */
@Component
class NewsRssSource(
    private val rssFetcher: RssFetcher,
    private val props: SocialProperties,
) : CardSource {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun produce(): List<CardDraft> {
        val cutoff = Instant.now().minus(props.recentHours, ChronoUnit.HOURS)
        return props.newsFeeds.mapNotNull { feed ->
            val items = runCatching { rssFetcher.fetch(feed.url, feed.label) }
                .getOrElse { log.warn("[social][news] '{}' 수집 실패: {}", feed.label, it.message); emptyList() }
            val articles = items
                .filter { it.publishedAt == null || it.publishedAt.isAfter(cutoff) }
                .sortedByDescending { it.publishedAt ?: Instant.EPOCH }
                .take(props.rankSize)
                .map {
                    SourceArticle(
                        title = it.title,
                        summary = it.description,
                        link = it.link,
                        source = it.source.ifBlank { feed.label },
                        imageUrl = it.imageUrl,
                    )
                }
            if (articles.isEmpty()) {
                log.info("[social][news] '{}' 최근 기사 없음", feed.label)
                return@mapNotNull null
            }
            CardDraft(
                title = "${feed.label} 오늘의 주요 뉴스 TOP ${articles.size}",
                sourceType = SourceType.NEWS,
                riskLevel = RiskLevel.LOW,
                dedupSuffix = "news:${feed.label}",
                topic = "${feed.label} 뉴스",
                articles = articles,
            )
        }
    }
}
