package com.aixnative.social.service

import com.aixnative.social.domain.SourceArticle
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.util.UriComponentsBuilder

/**
 * 유튜브 Data API v3 - 지역(KR) 카테고리별 인기영상(chart=mostPopular).
 * 호출당 1 unit(무료 1만/일), API 키만 필요하고 Cloud Run 송신 IP 제약 없음.
 * 키 미설정/조회 실패 시 빈 목록(graceful) - [YoutubePopularSource] 가 흡수.
 * (EcosClient JSON API 패턴 이식 - 키는 신규 발급, 복사 금지.)
 */
@Component
class YoutubePopularClient(
    private val props: SocialProperties,
    @Qualifier("marketDataRestClient") private val rest: RestClient,
    private val mapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** 카테고리(categoryId) 인기영상 상위 [max]건을 랭킹 소재로 반환. 실패 시 빈 목록. */
    fun mostPopular(categoryId: String, max: Int): List<SourceArticle> {
        if (!props.youtubeEnabled) return emptyList()
        return try {
            val uri = UriComponentsBuilder.fromHttpUrl("https://www.googleapis.com/youtube/v3/videos")
                .queryParam("part", "snippet,statistics")
                .queryParam("chart", "mostPopular")
                .queryParam("regionCode", "KR")
                .queryParam("videoCategoryId", categoryId)
                .queryParam("maxResults", max.coerceIn(1, 20))
                .queryParam("key", props.youtubeApiKey)
                .build(true).toUriString()
            val body = rest.get().uri(uri).retrieve().body(String::class.java) ?: return emptyList()
            val items: JsonNode = mapper.readTree(body).path("items")
            if (!items.isArray) return emptyList()
            items.mapNotNull { toArticle(it) }
        } catch (e: Exception) {
            log.warn("[social][youtube] category={} 조회 실패: {}", categoryId, e.message)
            emptyList()
        }
    }

    private fun toArticle(item: JsonNode): SourceArticle? {
        val id = item.path("id").asText("").ifBlank { return null }
        val snippet = item.path("snippet")
        val title = snippet.path("title").asText("").ifBlank { return null }
        val channel = snippet.path("channelTitle").asText("").ifBlank { "YouTube" }
        val views = item.path("statistics").path("viewCount").asText("").toLongOrNull()
        val summary = buildString {
            if (views != null) append("조회수 ").append(formatViews(views)).append(" · ")
            append(channel)
        }
        return SourceArticle(
            title = title,
            summary = summary,
            link = "https://www.youtube.com/watch?v=$id",
            source = "YouTube · $channel",
            imageUrl = bestThumbnail(snippet.path("thumbnails"), id),
        )
    }

    /** 썸네일 최고 화질 우선(maxres→standard→high). 없으면 유튜브 규칙 URL 폴백. */
    private fun bestThumbnail(thumbs: JsonNode, videoId: String): String {
        for (key in listOf("maxres", "standard", "high", "medium")) {
            val url = thumbs.path(key).path("url").asText("")
            if (url.isNotBlank()) return url
        }
        return "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"
    }

    /** 조회수 한글 축약(1.2만 / 3.4억). */
    private fun formatViews(v: Long): String = when {
        v >= 100_000_000 -> "%.1f억".format(v / 100_000_000.0)
        v >= 10_000 -> "%.1f만".format(v / 10_000.0)
        else -> v.toString()
    }
}
