package com.aixnative.social.service

import com.aixnative.ai.service.ScrapingProxyProperties
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.net.URLEncoder

/**
 * 커뮤니티 fetch 프록시 - 키 설정 시 스크래핑 API(주거 IP·필요시 JS렌더) 경유로 Cloud Run 데이터센터 IP
 * 차단을 우회한다. 미설정 시 직접 Jsoup(현행 동작). 두 지점([CommunityHotSource] 리스트,
 * [CommunityArticleFetcher] 본문)이 공용으로 쓴다.
 *
 * 프록시 응답은 대상의 원본 HTML이므로 baseUri=원본 URL 로 파싱해 상대링크(absUrl)가 실 사이트로 해석되게 한다.
 * 어느 경로든 실패 시 null(graceful) → 해당 대상/글만 스킵.
 */
@Component
class ScrapingProxy(
    private val props: ScrapingProxyProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun isEnabled(): Boolean = props.api.key.isNotBlank()

    /** target URL의 HTML을 Document로. 프록시 설정 시 경유, 아니면 직접. 실패 시 null. */
    fun fetch(targetUrl: String): Document? =
        if (isEnabled()) fetchViaProxy(targetUrl) else fetchDirect(targetUrl)

    private fun fetchDirect(targetUrl: String): Document? = runCatching {
        Jsoup.connect(targetUrl)
            .userAgent(UA)
            .timeout(DIRECT_TIMEOUT_MS)
            .followRedirects(true)
            .get()
    }.getOrElse { log.info("[social][scrape] 직접 fetch 실패 {}: {}", targetUrl, it.message); null }

    private fun fetchViaProxy(targetUrl: String): Document? {
        val proxyUrl = buildString {
            append(props.api.url).append("/?api_key=").append(props.api.key)
            append("&url=").append(URLEncoder.encode(targetUrl, Charsets.UTF_8))
            if (props.api.renderJs) append("&render=true")
            if (props.api.premium) append("&premium=true")
            if (props.api.countryCode.isNotBlank()) append("&country_code=").append(props.api.countryCode)
        }
        return runCatching {
            val html = Jsoup.connect(proxyUrl)
                .ignoreContentType(true)
                .maxBodySize(0)
                .timeout(PROXY_TIMEOUT_MS)
                .execute()
                .body()
            Jsoup.parse(html, targetUrl) // baseUri=원본 → absUrl 정상 해석
        }.getOrElse { log.warn("[social][scrape] 프록시 fetch 실패 {}: {}", targetUrl, it.message); null }
    }

    private companion object {
        const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        const val DIRECT_TIMEOUT_MS = 8_000
        const val PROXY_TIMEOUT_MS = 45_000 // 주거IP 여유(render=false는 보통 <15s). 지연 요청 빠른 실패.
    }
}
