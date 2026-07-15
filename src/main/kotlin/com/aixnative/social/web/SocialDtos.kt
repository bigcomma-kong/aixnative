package com.aixnative.social.web

import com.aixnative.social.domain.RankSlide
import com.aixnative.social.domain.SourceRef
import java.time.Instant

/** 관리자 콘솔용 게시물 뷰. 엔티티의 JSON 필드는 파싱된 형태로 노출. */
data class SocialPostView(
    val id: Long,
    val topic: String,
    val title: String,
    val caption: String?,
    val hashtags: String?,
    val mediaType: String,
    val platform: String,
    val status: String,
    /** 소재 출처 유형(YOUTUBE/TREND/NEWS/COMMUNITY) - 관리자 배지. */
    val sourceType: String,
    /** 게시 리스크 등급(LOW/MEDIUM/HIGH) - HIGH 는 승인 단계 경고. */
    val riskLevel: String,
    val slides: List<RankSlide>,
    val sourceRefs: List<SourceRef>,
    /** 렌더 이미지 공개 URL(있을 때). 없으면 null. */
    val imageUrl: String?,
    val hasImage: Boolean,
    val aiProvider: String?,
    val createdAt: Instant?,
    val publishedAt: Instant?,
    val externalPostId: String?,
    val error: String?,
    /** 이 플랫폼 퍼블리셔가 연동됐는지(게시 버튼 활성 여부). */
    val canPublish: Boolean,
)
