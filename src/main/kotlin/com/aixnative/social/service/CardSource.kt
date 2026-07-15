package com.aixnative.social.service

import com.aixnative.social.domain.CardDraft

/**
 * 카드 소스 - 한 데이터 소스(유튜브 인기영상/구글 트렌드/언론사 RSS/커뮤니티)에서
 * 랭킹 카드 초안([CardDraft])들을 만든다. @Component 로 등록하면 오케스트레이터가
 * `List<CardSource>` 로 모두 주입받아 produce() 를 취합한다(MediaRenderer/SocialPublisher 패턴 일관).
 *
 * 각 구현체는 내부에서 graceful 해야 한다(소스 실패가 전체를 막지 않게 빈 리스트 반환).
 */
interface CardSource {
    fun produce(): List<CardDraft>
}
