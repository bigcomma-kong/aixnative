package com.aixnative.social.service

import com.aixnative.social.domain.SocialMediaType
import com.aixnative.social.domain.SocialPost

/**
 * 미디어 렌더러 - 소셜 게시물의 미디어(이미지/영상)를 생성한다.
 * 1단계는 [ImageCardRenderer](IMAGE), 2단계에서 VideoRenderer(VIDEO)를 구현체로 추가만 하면 된다.
 * 오케스트레이터는 [mediaType] 이 일치하는 렌더러를 골라 호출한다(없으면 DRAFT 로 남김, graceful).
 */
interface MediaRenderer {
    val mediaType: SocialMediaType

    /**
     * 게시물 콘텐츠(제목/슬라이드)를 렌더링해 **슬라이드별 base64 PNG 목록**을 반환한다.
     * index0=표지, 이후 항목별 1장(캐러셀). 단일 이미지 렌더러는 1건 리스트.
     * (영상 렌더러는 공개 URL 을 반환하도록 확장 시 시그니처 조정)
     */
    fun renderSlides(post: SocialPost): List<String>
}
