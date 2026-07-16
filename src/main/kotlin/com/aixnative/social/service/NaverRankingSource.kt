package com.aixnative.social.service

import com.aixnative.social.domain.RiskLevel
import com.aixnative.social.domain.SourceType
import com.aixnative.social.domain.StoryDraft
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import kotlin.math.absoluteValue

/**
 * 네이버 랭킹뉴스 스토리 소스([StorySource]) - 네이버 실시간 검색어(실검)는 2021 폐지되어,
 * 대체 화제 신호로 "많이 본 뉴스" 상위 기사를 [ScrapingProxy] 로 긁어 스토리 1건씩 만든다.
 * 기사 URL(n.news.naver.com/article/{oid}/{aid})을 딥페치 → Claude 각색.
 *
 * 언론 기사라 리스크 MEDIUM(각색기 저작권 안전선 적용). 차단/파싱 실패 시 graceful 빈결과.
 */
@Component
class NaverRankingSource(
    private val props: SocialProperties,
    private val scrapingProxy: ScrapingProxy,
) : StorySource {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun produce(): List<StoryDraft> {
        if (!props.naverRankingEnabled) return emptyList()
        val doc = runCatching { scrapingProxy.fetch(props.naverRankingUrl) }
            .getOrElse { log.warn("[social][naver] 랭킹뉴스 수집 실패: {}", it.message); null }
            ?: run { log.info("[social][naver] 랭킹뉴스 fetch 실패(프록시/직접)"); return emptyList() }

        val anchors = doc.select("a[href*=/article/]").asSequence()
            .filter { a ->
                val href = a.absUrl("href")
                ARTICLE_LINK.containsMatchIn(href) && a.text().trim().length in TITLE_MIN..TITLE_MAX
            }
            .distinctBy { it.absUrl("href").substringBefore("?") }
            .take(props.naverRankingMax)
            .toList()
        if (anchors.isEmpty()) log.info("[social][naver] 랭킹뉴스 기사 앵커 없음(구조 변경 가능)")

        return anchors.map { a ->
            val link = a.absUrl("href").substringBefore("?")
            StoryDraft(
                board = BOARD,
                url = link,
                title = a.text().trim().take(120),
                engagement = null,
                riskLevel = RiskLevel.MEDIUM,
                dedupSuffix = "naverrank:${link.hashCode().absoluteValue}",
                sourceType = SourceType.NEWS,
            )
        }
    }

    private companion object {
        const val TITLE_MIN = 8
        const val TITLE_MAX = 80
        const val BOARD = "네이버 랭킹뉴스"
        val ARTICLE_LINK = Regex("n\\.news\\.naver\\.com/article/\\d+/\\d+")
    }
}
