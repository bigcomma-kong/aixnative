package com.aixnative.marketfeed

import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository

interface MarketFeedRepository : JpaRepository<MarketFeedItem, Long> {

    /** 최신 발행순(발행일 없으면 id 보조정렬). 글로벌 — 테넌트 스코프 없음. */
    fun findAllByOrderByPublishedAtDescIdDesc(pageable: Pageable): List<MarketFeedItem>
}
