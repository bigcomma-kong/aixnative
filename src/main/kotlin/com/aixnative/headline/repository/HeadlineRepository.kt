package com.aixnative.headline.repository

import com.aixnative.headline.domain.HeadlineItem
import org.springframework.data.jpa.repository.JpaRepository

interface HeadlineRepository : JpaRepository<HeadlineItem, Long> {

    /** 최신 발행순(발행일 없으면 id 보조정렬). 보드 렌더용 상위 N. 글로벌 — 테넌트 스코프 없음. */
    fun findTop120ByOrderByPublishedAtDescIdDesc(): List<HeadlineItem>

    /** 자동 수집 중복제거: 이미 존재하는 dedup_key 집합(이번 배치 후보와 대조). */
    fun findByDedupKeyIn(keys: Collection<String>): List<HeadlineItem>

    /** 자동 수집분 전량 삭제(전부 dedup_key 보유). 1회성 정리/리셋용. */
    fun deleteByDedupKeyIsNotNull(): Long
}
