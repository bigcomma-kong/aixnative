package com.aixnative.social.service

import com.aixnative.social.domain.SocialPlatform
import com.aixnative.social.domain.SocialPost
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.util.LinkedMultiValueMap
import org.springframework.util.MultiValueMap
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientResponseException

/**
 * 인스타그램 퍼블리셔(INSTAGRAM) - Graph API Content Publishing.
 *  - 단일: (1) POST /{ig}/media (image_url, caption) → creation_id, (2) media_publish → media id
 *  - 캐러셀(슬라이드 2장+): (1) 슬라이드별 media(is_carousel_item=true) → child id N개,
 *    (2) media(media_type=CAROUSEL, children=..., caption) → parent, (3) media_publish → media id
 *
 * image_url 은 우리 공개 엔드포인트 `${baseUrl}/cardnews/{id}/{index}.png` - 인스타 서버가 이 URL 을 fetch 한다
 * (따라서 baseUrl 이 공개 도메인이어야 하며 로컬 localhost 로는 게시 불가). 토큰/계정 미설정 시 비활성.
 */
@Component
class InstagramPublisher(
    builder: RestClient.Builder,
    private val objectMapper: ObjectMapper,
    private val props: SocialProperties,
    @Value("\${app.base-url:http://localhost:8080}") private val baseUrl: String,
) : SocialPublisher {

    private val log = LoggerFactory.getLogger(javaClass)
    private val rest: RestClient = builder.build()

    override val platform = SocialPlatform.INSTAGRAM

    override fun isConfigured(): Boolean = props.instagram.isConfigured()

    override fun publish(post: SocialPost): PublishResult {
        check(isConfigured()) { "인스타그램 계정이 연동되지 않았습니다." }
        requireNotNull(post.id) { "저장되지 않은 게시물입니다." }
        require(post.imageBase64 != null) { "렌더된 카드 이미지가 없습니다." }

        val ig = props.instagram
        val caption = buildCaption(post)
        val slideCount = slideCount(post)

        val creationId = if (slideCount > 1) {
            createCarousel(post, slideCount, caption)
        } else {
            postForm(
                "${ig.graphApiUrl}/${ig.businessAccountId}/media",
                mapOf("image_url" to "$baseUrl/cardnews/${post.id}.png", "caption" to caption, "access_token" to ig.accessToken),
            ).path("id").asText("").ifBlank { throw RuntimeException("컨테이너 생성 응답에 id 없음") }
        }

        // 게시(단일·캐러셀 공통)
        val mediaId = postForm(
            "${ig.graphApiUrl}/${ig.businessAccountId}/media_publish",
            mapOf("creation_id" to creationId, "access_token" to ig.accessToken),
        ).path("id").asText("").ifBlank { throw RuntimeException("게시 응답에 id 없음") }

        log.info("[social] 인스타 게시 완료 postId={} slides={} mediaId={}", post.id, slideCount, mediaId)
        return PublishResult(externalPostId = mediaId)
    }

    /** images_json 배열 크기(캐러셀 장수). 없으면 1(단일). */
    private fun slideCount(post: SocialPost): Int =
        post.imagesJson
            ?.let { runCatching { objectMapper.readValue(it, object : TypeReference<List<String>>() {}).size }.getOrNull() }
            ?: 1

    /** 캐러셀 부모 컨테이너 생성 → creation_id. 슬라이드별 child 컨테이너 N개 후 CAROUSEL 로 묶는다. */
    private fun createCarousel(post: SocialPost, slideCount: Int, caption: String): String {
        val ig = props.instagram
        val children = (0 until slideCount).map { i ->
            postForm(
                "${ig.graphApiUrl}/${ig.businessAccountId}/media",
                mapOf(
                    "image_url" to "$baseUrl/cardnews/${post.id}/$i.png",
                    "is_carousel_item" to "true",
                    "access_token" to ig.accessToken,
                ),
            ).path("id").asText("").ifBlank { throw RuntimeException("캐러셀 child($i) 생성 응답에 id 없음") }
        }
        return postForm(
            "${ig.graphApiUrl}/${ig.businessAccountId}/media",
            mapOf(
                "media_type" to "CAROUSEL",
                "children" to children.joinToString(","),
                "caption" to caption,
                "access_token" to ig.accessToken,
            ),
        ).path("id").asText("").ifBlank { throw RuntimeException("캐러셀 부모 생성 응답에 id 없음") }
    }

    /** 캡션 = 본문 + 해시태그(두 줄 띄움). */
    private fun buildCaption(post: SocialPost): String =
        listOfNotNull(post.captionText, post.hashtags)
            .filter { it.isNotBlank() }
            .joinToString("\n\n")

    private fun postForm(url: String, params: Map<String, String>): JsonNode {
        val body: MultiValueMap<String, String> = LinkedMultiValueMap<String, String>().apply {
            params.forEach { (k, v) -> add(k, v) }
        }
        return try {
            rest.post()
                .uri(url)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(body)
                .retrieve()
                .body(JsonNode::class.java)
                ?: throw RuntimeException("인스타 API 빈 응답")
        } catch (e: RestClientResponseException) {
            // Graph API 에러: {"error":{"message":...}}
            val msg = runCatching { objectMapper.readTree(e.responseBodyAsString).path("error").path("message").asText("") }
                .getOrDefault("")
            log.warn("[social] 인스타 API 실패 status={} body={}", e.statusCode, e.responseBodyAsString.take(400))
            throw RuntimeException("인스타 게시 실패(${e.statusCode.value()}): ${msg.ifBlank { "알 수 없는 오류" }}", e)
        }
    }
}
