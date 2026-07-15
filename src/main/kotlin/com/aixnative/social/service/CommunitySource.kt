package com.aixnative.social.service

import com.aixnative.social.domain.CardDraft
import com.aixnative.social.domain.RiskLevel
import com.aixnative.social.domain.SourceArticle
import com.aixnative.social.domain.SourceType
import org.jsoup.Jsoup
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * 커뮤니티 베스트글 소스 - 설정된 대상(social.community-targets)의 정적 HTML 을 Jsoup 으로 파싱.
 * 공식 RSS 가 없고 Cloudflare/JS 렌더가 많아 정적 요청이 막히면 graceful 빈결과(후속 헤드리스 과제).
 * 출처 신뢰도가 낮아 항상 리스크 HIGH - 관리자 승인 단계에서 "리스크 감수 필요"로 표기해 사람이 판단.
 * 기본 대상 목록은 비어 있어(빈 목록) 명시적으로 켤 때만 동작한다.
 */
@Component
class CommunitySource(
    private val props: SocialProperties,
) : CardSource {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun produce(): List<CardDraft> {
        if (props.communityTargets.isEmpty()) return emptyList()
        return props.communityTargets.mapNotNull { target ->
            val articles = runCatching { scrape(target.url, target.label) }
                .getOrElse { log.warn("[social][community] '{}' 수집 실패: {}", target.label, it.message); emptyList() }
            if (articles.isEmpty()) {
                log.info("[social][community] '{}' 결과 없음(정적 파싱 한계 가능)", target.label)
                return@mapNotNull null
            }
            CardDraft(
                title = "${target.label} 베스트글 TOP ${articles.size}",
                sourceType = SourceType.COMMUNITY,
                riskLevel = RiskLevel.HIGH,
                dedupSuffix = "community:${target.label}",
                topic = "${target.label} 인기글",
                articles = articles,
            )
        }
    }

    /**
     * 정적 HTML 링크 스크랩(제목·href 있는 앵커). 사이트 구조가 제각각이라
     * 최소 휴리스틱(텍스트 길이 필터)만 적용하고, 최종 선별·정리는 Claude 큐레이션에 맡긴다.
     */
    private fun scrape(url: String, label: String): List<SourceArticle> {
        val doc = Jsoup.connect(url)
            .userAgent(USER_AGENT)
            .timeout(TIMEOUT_MS)
            .followRedirects(true)
            .get()
        return doc.select("a[href]")
            .asSequence()
            .map { it.text().trim() to it.absUrl("href") }
            .filter { (text, href) -> text.length in TITLE_MIN..TITLE_MAX && href.startsWith("http") }
            .distinctBy { it.first }
            .take(props.rankSize)
            .map { (text, href) ->
                SourceArticle(title = text, summary = "커뮤니티 인기글", link = href, source = label)
            }
            .toList()
    }

    private companion object {
        const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
        const val TIMEOUT_MS = 8_000
        const val TITLE_MIN = 8
        const val TITLE_MAX = 80
    }
}
