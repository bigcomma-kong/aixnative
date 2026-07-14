package com.aixnative.social.repository

import com.aixnative.social.domain.SocialPost
import com.aixnative.social.domain.SocialPostStatus
import org.springframework.data.jpa.repository.JpaRepository

interface SocialPostRepository : JpaRepository<SocialPost, Long> {

    /** 관리자 콘솔 목록(최신순). */
    fun findTop100ByOrderByCreatedAtDesc(): List<SocialPost>

    /** 상태별 목록(최신순) - 승인 대기 큐 등. */
    fun findByStatusOrderByCreatedAtDesc(status: SocialPostStatus): List<SocialPost>

    /** 중복 차단 - 이미 만든 dedup_key 조회. */
    fun findByDedupKeyIn(keys: Collection<String>): List<SocialPost>
}
