package com.aixnative.social.service

import com.aixnative.social.domain.StoryDraft

/**
 * 스토리 소스 - 커뮤니티 핫글 리스트에서 스토리 게시물 초안([StoryDraft])들을 만든다.
 * 각 초안 = 핫글 1건 = 스토리 게시물 1건. @Component 로 등록하면 오케스트레이터가
 * `List<StorySource>` 로 주입받는다. CardSource(랭킹)와 반환 단위가 달라 별개 인터페이스.
 *
 * 구현체는 내부 graceful(소스 실패가 전체를 막지 않게 빈 리스트).
 */
interface StorySource {
    fun produce(): List<StoryDraft>
}
