package com.aixnative.social.service

import com.aixnative.social.domain.CardDraft
import com.aixnative.social.domain.RiskLevel
import com.aixnative.social.domain.SourceArticle
import com.aixnative.social.domain.SourceType
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * 구글 트렌드 소스 - 지금 뜨는 검색어(trending/rss?geo=KR)를 랭킹 카드 1건으로.
 * 각 item 의 <title> = 화제 검색어. "화제성" 신호라 검증 안 된 내용 섞일 수 있어 리스크 MEDIUM.
 * 신규 URL(옛 daily rss 폐기). 실패 시 빈 목록(graceful).
 */
@Component
class GoogleTrendSource(
    private val rssFetcher: RssFetcher,
    private val props: SocialProperties,
) : CardSource {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun produce(): List<CardDraft> {
        val items = runCatching { rssFetcher.fetch(TREND_URL, "Google Trends") }
            .getOrElse { log.warn("[social][trend] 수집 실패: {}", it.message); emptyList() }
        if (items.isEmpty()) return emptyList()
        val articles = items.take(props.rankSize).map {
            SourceArticle(
                title = it.title,
                summary = it.description.ifBlank { "지금 화제인 검색어" },
                link = it.link.ifBlank { "https://trends.google.com/trending?geo=KR" },
                source = "Google Trends",
                imageUrl = it.imageUrl,
            )
        }
        return listOf(
            CardDraft(
                title = "지금 뜨는 검색어 TOP ${articles.size}",
                sourceType = SourceType.TREND,
                riskLevel = RiskLevel.MEDIUM,
                dedupSuffix = "trend:KR",
                topic = "오늘의 급상승 검색어",
                articles = articles,
            ),
        )
    }

    private companion object {
        const val TREND_URL = "https://trends.google.com/trending/rss?geo=KR"
    }
}
