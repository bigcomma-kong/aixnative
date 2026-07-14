package com.aixnative.social.service

import com.aixnative.social.domain.SocialPost
import com.aixnative.social.domain.SocialPlatform

/** 게시 결과 - 플랫폼이 부여한 게시물 ID. */
data class PublishResult(val externalPostId: String)

/**
 * 소셜 플랫폼 퍼블리셔 - 승인된 게시물을 실제 플랫폼에 올린다.
 * 1단계는 [InstagramPublisher], 2단계에서 YoutubePublisher 를 구현체로 추가.
 * 계정/키 미설정 시 [isConfigured] = false → 오케스트레이터/관리자가 게시를 건너뛴다(graceful).
 */
interface SocialPublisher {
    val platform: SocialPlatform

    /** 게시 자격(토큰/계정) 준비 여부. false 면 게시 버튼 비활성. */
    fun isConfigured(): Boolean

    /** 게시물을 플랫폼에 게시하고 외부 ID 를 반환. 실패 시 예외. */
    fun publish(post: SocialPost): PublishResult
}
