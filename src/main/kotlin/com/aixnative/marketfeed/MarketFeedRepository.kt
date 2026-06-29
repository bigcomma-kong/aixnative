package com.aixnative.marketfeed

import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository

interface MarketFeedRepository : JpaRepository<MarketFeedItem, Long> {

    /** 최신 발행순(발행일 없으면 id 보조정렬). 글로벌 — 테넌트 스코프 없음. */
    fun findAllByOrderByPublishedAtDescIdDesc(pageable: Pageable): List<MarketFeedItem>

    /** 자동 수집 중복제거: 이미 존재하는 dedup_key 집합(이번 배치 후보와 대조). */
    fun findByDedupKeyIn(keys: Collection<String>): List<MarketFeedItem>

    /** 자동 수집 카드만 삭제(수동 등록 ADMIN 카드는 dedup_key=null 이라 보존). 1회성 정리/리셋용. */
    fun deleteByDedupKeyIsNotNull(): Long
}
