package com.aixnative.social.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EntityListeners
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.annotation.CreatedDate
import org.springframework.data.jpa.domain.support.AuditingEntityListener
import java.time.Instant

/** 렌더할 미디어 종류. 1단계는 IMAGE, 2단계에서 VIDEO 추가. */
enum class SocialMediaType { IMAGE, VIDEO }

/** 게시 대상 플랫폼. 1단계는 INSTAGRAM, 2단계에서 YOUTUBE 추가. */
enum class SocialPlatform { INSTAGRAM, YOUTUBE }

/**
 * 승인 워크플로우 상태.
 *  - DRAFT: 생성 직후(캡션만, 이미지 미렌더)
 *  - PENDING: 이미지 렌더 완료, 관리자 승인 대기
 *  - APPROVED: 관리자 승인(게시 가능)
 *  - PUBLISHED: 플랫폼 게시 완료
 *  - REJECTED: 관리자 반려
 */
enum class SocialPostStatus { DRAFT, PENDING, APPROVED, PUBLISHED, REJECTED }

/**
 * 공감랭킹 소셜 게시물 - 자동 생성 → 관리자 승인 → 게시 파이프라인의 단위.
 * 글로벌 콘텐츠(비테넌트, [com.aixnative.marketfeed.domain.MarketBriefing] 패턴).
 *
 * slides/source_refs 는 JSON 문자열로 보관(서비스단에서 파싱). 이미지는 base64 TEXT(MVP).
 */
@Entity
@Table(name = "social_post")
@EntityListeners(AuditingEntityListener::class)
class SocialPost(
    @Column(nullable = false, length = 80)
    var topic: String,

    @Column(nullable = false, length = 300)
    var title: String,

    @Column(name = "caption_text", columnDefinition = "TEXT")
    var captionText: String? = null,

    @Column(name = "slides_json", columnDefinition = "TEXT")
    var slidesJson: String? = null,

    @Column(length = 500)
    var hashtags: String? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "media_type", nullable = false, length = 20)
    var mediaType: SocialMediaType = SocialMediaType.IMAGE,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var platform: SocialPlatform = SocialPlatform.INSTAGRAM,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var status: SocialPostStatus = SocialPostStatus.DRAFT,

    @Column(name = "image_base64", columnDefinition = "TEXT")
    var imageBase64: String? = null,

    @Column(name = "source_refs_json", columnDefinition = "TEXT")
    var sourceRefsJson: String? = null,

    @Column(name = "ai_provider", length = 40)
    var aiProvider: String? = null,

    @Column(length = 60)
    var origin: String? = null,

    @Column(name = "dedup_key", length = 400)
    var dedupKey: String? = null,

    @Column(name = "published_at")
    var publishedAt: Instant? = null,

    @Column(name = "external_post_id", length = 120)
    var externalPostId: String? = null,

    @Column(length = 1000)
    var error: String? = null,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant? = null
}
