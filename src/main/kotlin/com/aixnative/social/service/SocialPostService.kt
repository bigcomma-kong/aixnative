package com.aixnative.social.service

import com.aixnative.social.domain.RankSlide
import com.aixnative.social.domain.SocialIngestReport
import com.aixnative.social.domain.SocialMediaType
import com.aixnative.social.domain.SocialPost
import com.aixnative.social.domain.SocialPostStatus
import com.aixnative.social.domain.SourceRef
import com.aixnative.social.repository.SocialPostRepository
import com.aixnative.social.web.SocialPostView
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * 공감랭킹 소셜 게시물 오케스트레이터. 한 번의 수집 실행이:
 *  1) 다중 소스([CardSource] 목록: 유튜브 인기·구글 트렌드·언론사 RSS·커뮤니티)에서 카드 초안 취합,
 *  2) Claude 로 랭킹 카드 캡션·슬라이드 생성([SocialCaptionGenerator]),
 *  3) 중복 차단 후 저장(DRAFT, 출처유형·리스크 등급 포함),
 *  4) 미디어 렌더러가 있으면 이미지 렌더 → PENDING(승인 대기).
 *
 * 소스/렌더러/퍼블리셔는 모두 인터페이스 목록으로 주입 - 구현체가 없거나 개별 실패해도 graceful.
 * 게시는 승인(APPROVED) 후 [publish] 에서 플랫폼 퍼블리셔로 수행. 리스크 등급은 관리자 승인 단계에서 판단.
 */
@Service
class SocialPostService(
    private val cardSources: List<CardSource>,
    private val captionGenerator: SocialCaptionGenerator,
    private val storySources: List<StorySource>,
    private val storyGenerator: StoryScriptGenerator,
    private val imageComposer: StoryImageComposer,
    private val repository: SocialPostRepository,
    private val renderers: List<MediaRenderer>,
    private val publishers: List<SocialPublisher>,
    private val objectMapper: ObjectMapper,
    private val props: SocialProperties,
    @Value("\${app.base-url:http://localhost:8080}") private val baseUrl: String,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * @param autoPublish true 면 렌더 성공분을 승인 없이 바로 게시(스케줄러 완전 자동 경로).
     *   계정 미연동/게시 실패 시 승인 대기(PENDING)로 남는다. 관리자 수동 트리거는 false.
     */
    @Transactional
    fun ingest(autoPublish: Boolean = false): SocialIngestReport {
        val errors = ArrayList<String>()
        var created = 0
        var skipped = 0
        var rendered = 0
        var published = 0
        val today = LocalDate.now(ZoneId.of("Asia/Seoul"))

        // 각 소스는 내부 graceful 하지만 오케스트레이터에서도 방어(한 소스 예외가 전체를 막지 않게).
        val drafts = cardSources.flatMap { src ->
            runCatching { src.produce() }
                .getOrElse { errors += "${src.javaClass.simpleName} 수집 실패: ${it.message}"; emptyList() }
        }
        val sourcesFetched = drafts.sumOf { it.articles.size }

        for (draft in drafts) {
            runCatching {
                val dedupKey = "${draft.dedupSuffix}:$today"
                if (repository.findByDedupKeyIn(listOf(dedupKey)).isNotEmpty()) {
                    skipped++
                    return@runCatching
                }

                val post = captionGenerator.generate(draft) ?: return@runCatching
                post.origin = "AUTO"
                post.dedupKey = dedupKey
                applyCta(post)
                repository.save(post)
                created++

                if (renderImage(post)) rendered++
                if (post.status == SocialPostStatus.PENDING && autoPublish && autoPublishPost(post, errors)) published++
            }.onFailure { errors += "카드 '${draft.title}' 처리 실패: ${it.message}"; log.warn("[social] 카드 처리 실패", it) }
        }

        // 스토리 루프 - 커뮤니티 핫글 각각을 별도 STORY 게시물로(본문 딥페치 → Claude 각색 → AI 이미지).
        val storyDrafts = storySources.flatMap { src ->
            runCatching { src.produce() }
                .getOrElse { errors += "${src.javaClass.simpleName} 스토리 수집 실패: ${it.message}"; emptyList() }
        }
        for (sd in storyDrafts) {
            runCatching {
                val dedupKey = "${sd.dedupSuffix}:$today"
                if (repository.findByDedupKeyIn(listOf(dedupKey)).isNotEmpty()) {
                    skipped++
                    return@runCatching
                }
                val post = storyGenerator.generate(sd) ?: return@runCatching
                post.origin = "AUTO"
                post.dedupKey = dedupKey
                repository.save(post)
                created++

                imageComposer.compose(post) // 장면별 AI 이미지 채움(엔진 없으면 폴백)
                repository.save(post)

                if (renderImage(post)) rendered++
                if (post.status == SocialPostStatus.PENDING && autoPublish && autoPublishPost(post, errors)) published++
            }.onFailure { errors += "스토리 '${sd.title}' 처리 실패: ${it.message}"; log.warn("[social] 스토리 처리 실패", it) }
        }

        log.info("[social] ingest cards={} stories={} sources={} created={} skipped={} rendered={} published={} auto={}",
            drafts.size, storyDrafts.size, sourcesFetched, created, skipped, rendered, published, autoPublish)
        return SocialIngestReport(
            topicsRequested = drafts.size,
            sourcesFetched = sourcesFetched,
            postsCreated = created,
            skippedDuplicate = skipped,
            rendered = rendered,
            published = published,
            errors = errors,
        )
    }

    /** 완전 자동 게시 - 렌더된 게시물을 승인 없이 바로 플랫폼에 올린다. 계정 미연동/실패면 PENDING 유지. */
    private fun autoPublishPost(post: SocialPost, errors: MutableList<String>): Boolean {
        val publisher = publishers.firstOrNull { it.platform == post.platform && it.isConfigured() }
        if (publisher == null) {
            log.info("[social] 자동게시 스킵(계정 미연동) - 승인 대기로 남김 id={}", post.id)
            return false
        }
        return runCatching {
            val result = publisher.publish(post)
            post.status = SocialPostStatus.PUBLISHED
            post.externalPostId = result.externalPostId
            post.publishedAt = Instant.now()
            post.error = null
            repository.save(post)
            true
        }.getOrElse {
            errors += "자동게시 실패 id=${post.id}: ${it.message}"
            post.error = it.message?.take(1000)
            repository.save(post)
            log.warn("[social] 자동게시 실패 id={}", post.id, it)
            false
        }
    }

    /** 부동산·재테크 주제에는 aixnative 유입 CTA 를 캡션 끝에 덧붙인다(범용 주제는 미적용). */
    private fun applyCta(post: SocialPost) {
        val hit = CTA_KEYWORDS.any { post.topic.contains(it) }
        if (!hit) return
        val cta = "\n\n부동산 딜 분석은 aixnative.com"
        post.captionText = (post.captionText ?: "") + cta
    }

    /** 미디어 렌더러가 있으면 캐러셀(표지+건별) 렌더 후 PENDING 으로. 없거나 실패면 DRAFT 유지. */
    private fun renderImage(post: SocialPost): Boolean {
        val renderer = renderers.firstOrNull { it.mediaType == post.mediaType } ?: return false
        return runCatching {
            val pages = renderer.renderSlides(post)
            post.imagesJson = objectMapper.writeValueAsString(pages)
            post.imageBase64 = pages.first() // 표지(하위호환·썸네일)
            post.status = SocialPostStatus.PENDING
            repository.save(post)
            true
        }.getOrElse { log.warn("[social] 렌더 실패 id={}: {}", post.id, it.message); false }
    }

    @Transactional
    fun approve(id: Long): SocialPostView = transition(id, SocialPostStatus.APPROVED)

    @Transactional
    fun reject(id: Long): SocialPostView = transition(id, SocialPostStatus.REJECTED)

    private fun transition(id: Long, to: SocialPostStatus): SocialPostView {
        val post = repository.findById(id).orElseThrow { IllegalArgumentException("게시물을 찾을 수 없습니다: $id") }
        post.status = to
        return repository.save(post).toView()
    }

    /** 승인된 게시물을 플랫폼에 게시. 퍼블리셔 미설정/미승인 시 예외. */
    @Transactional
    fun publish(id: Long): SocialPostView {
        val post = repository.findById(id).orElseThrow { IllegalArgumentException("게시물을 찾을 수 없습니다: $id") }
        require(post.status == SocialPostStatus.APPROVED) { "승인(APPROVED)된 게시물만 게시할 수 있습니다." }
        val publisher = publishers.firstOrNull { it.platform == post.platform && it.isConfigured() }
            ?: throw IllegalStateException("${post.platform} 게시 계정이 연동되지 않았습니다.")
        return try {
            val result = publisher.publish(post)
            post.status = SocialPostStatus.PUBLISHED
            post.externalPostId = result.externalPostId
            post.publishedAt = Instant.now()
            post.error = null
            repository.save(post).toView()
        } catch (e: Exception) {
            post.error = e.message?.take(1000)
            repository.save(post)
            throw e
        }
    }

    @Transactional
    fun delete(id: Long) = repository.deleteById(id)

    @Transactional(readOnly = true)
    fun listAll(): List<SocialPostView> = repository.findTop100ByOrderByCreatedAtDesc().map { it.toView() }

    /** 플랫폼 게시 가능 여부(퍼블리셔 설정됨). */
    private fun canPublish(post: SocialPost): Boolean =
        publishers.any { it.platform == post.platform && it.isConfigured() }

    fun SocialPost.toView(): SocialPostView {
        val slides = slidesJson?.let {
            runCatching { objectMapper.readValue(it, object : TypeReference<List<RankSlide>>() {}) }.getOrNull()
        }.orEmpty()
        val refs = sourceRefsJson?.let {
            runCatching { objectMapper.readValue(it, object : TypeReference<List<SourceRef>>() {}) }.getOrNull()
        }.orEmpty()
        // 캐러셀 슬라이드 URL 목록. images_json 이 있으면 그 장수만큼, 없으면 단일(하위호환).
        val slideCount = imagesJson
            ?.let { runCatching { objectMapper.readValue(it, object : TypeReference<List<String>>() {}).size }.getOrNull() }
            ?: (if (imageBase64 != null) 1 else 0)
        val imageUrls = if (id != null && slideCount > 0) {
            (0 until slideCount).map { "$baseUrl/cardnews/$id/$it.png" }
        } else emptyList()
        return SocialPostView(
            id = id ?: 0,
            topic = topic,
            title = title,
            caption = captionText,
            hashtags = hashtags,
            mediaType = mediaType.name,
            platform = platform.name,
            status = status.name,
            sourceType = sourceType.name,
            riskLevel = riskLevel.name,
            kind = kind.name,
            engagement = engagement,
            sourceBoard = sourceBoard,
            slides = slides,
            sourceRefs = refs,
            imageUrl = if (imageBase64 != null && id != null) "$baseUrl/cardnews/$id.png" else null,
            imageUrls = imageUrls,
            hasImage = imageBase64 != null,
            aiProvider = aiProvider,
            createdAt = createdAt,
            publishedAt = publishedAt,
            externalPostId = externalPostId,
            error = error,
            canPublish = canPublish(this),
        )
    }

    private companion object {
        val CTA_KEYWORDS = listOf("부동산", "재테크", "투자", "리츠", "부린이")
    }
}
