package com.aixnative.marketfeed.repository

import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import java.time.Instant
import com.aixnative.marketfeed.domain.MarketFeedItem

interface MarketFeedRepository : JpaRepository<MarketFeedItem, Long> {

    /** 최신 발행순(발행일 없으면 id 보조정렬). 글로벌 — 테넌트 스코프 없음. */
    fun findAllByOrderByPublishedAtDescIdDesc(pageable: Pageable): List<MarketFeedItem>

    /**
     * 브리핑 분석용 누적 최근 풀 — [cutoff] 이후 발행분을 최신순으로(자동+수동 카드 모두).
     * 단일 수집(fetch)이 아니라 DB에 쌓인 최근 카드를 보므로, 하루 수집이 스로틀나도 건수가 안 무너진다.
     */
    fun findByPublishedAtAfterOrderByPublishedAtDescIdDesc(cutoff: Instant, pageable: Pageable): List<MarketFeedItem>

    /** 자동 수집 중복제거: 이미 존재하는 dedup_key 집합(이번 배치 후보와 대조). */
    fun findByDedupKeyIn(keys: Collection<String>): List<MarketFeedItem>

    /** 자동 수집 카드만 삭제(수동 등록 ADMIN 카드는 dedup_key=null 이라 보존). 1회성 정리/리셋용. */
    fun deleteByDedupKeyIsNotNull(): Long
}
