package com.aixnative.social.service

import org.jsoup.nodes.Document
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/** 딥페치 결과 - 각색 입력용 본문 텍스트(원문 저장/출력 금지). */
data class FetchedArticle(val bodyText: String)

/**
 * 커뮤니티 핫글 본문 딥페치([ScrapingProxy] 경유 - 키 설정 시 주거IP, 아니면 직접). 사이트별 본문
 * 셀렉터를 시도하고, 실패 시 최대 텍스트 블록 휴리스틱으로 폴백. 막히면(차단/JS) graceful null → 해당 글만 스킵.
 *
 * 저작권: 본문은 [StoryScriptGenerator] 의 **각색 입력으로만** 쓰고 원문을 저장/출력하지 않는다.
 */
@Component
class CommunityArticleFetcher(
    private val scrapingProxy: ScrapingProxy,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** @return 본문 텍스트(각색 입력) 또는 null(딥페치 실패). */
    fun fetch(url: String): FetchedArticle? {
        val doc = scrapingProxy.fetch(url)
            ?: run { log.info("[social][story] 본문 딥페치 실패 {}", url); return null }

        val body = extractBody(doc)
        if (body.length < MIN_BODY) {
            log.info("[social][story] 본문 너무 짧음(추출 실패 가능) {}", url)
            return null
        }
        return FetchedArticle(bodyText = body.take(MAX_BODY))
    }

    /** 사이트별 본문 셀렉터 우선, 실패 시 최대 <p> 텍스트 블록 휴리스틱. */
    private fun extractBody(doc: Document): String {
        for (sel in BODY_SELECTORS) {
            val el = doc.selectFirst(sel) ?: continue
            val text = el.text().trim()
            if (text.length >= MIN_BODY) return text
        }
        // 휴리스틱: 가장 텍스트 많은 컨테이너.
        return doc.select("article, div, section")
            .maxByOrNull { it.ownText().length }
            ?.text()?.trim().orEmpty()
    }

    private companion object {
        const val MIN_BODY = 40
        const val MAX_BODY = 4_000
        // 본문 컨테이너 셀렉터 - 뉴스(네이버 기사) 우선 + 커뮤니티(에펨·클리앙·루리웹·디시·보배드림) 공통 후보.
        val BODY_SELECTORS = listOf(
            // 네이버 뉴스 기사 본문(랭킹뉴스 스토리) - 신/구 레이아웃.
            "#dic_area", "#newsct_article", "article#dic_area", ".newsct_article", "#articeBody", "#articleBodyContents",
            // 커뮤니티 공통.
            ".content", ".post_article", ".view_content", ".article-content", ".s_body",
            ".write_div", ".board-content", ".view-content", ".read_body", "#powerbbsContent",
            "article",
        )
    }
}
