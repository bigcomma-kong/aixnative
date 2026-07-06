package com.aixnative.analytics.repository

import com.aixnative.analytics.domain.UserEvent
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant

/** 이벤트별 집계 한 줄(퍼널 표시용). */
interface EventCount {
    val event: String
    val cnt: Long
}

interface UserEventRepository : JpaRepository<UserEvent, Long> {

    /** 지정 시각 이후 이벤트별 건수(퍼널). 최근순 정렬은 호출측에서 사용 이벤트 순서로 재배치. */
    @Query(
        """
        SELECT e.event AS event, COUNT(e) AS cnt
        FROM UserEvent e
        WHERE e.createdAt >= :since
        GROUP BY e.event
        """,
    )
    fun funnelSince(@Param("since") since: Instant): List<EventCount>

    /** 고유 방문자(로그인) 근사 — 지정 이벤트의 distinct user 수. 익명(user_id null)은 제외. */
    @Query(
        """
        SELECT COUNT(DISTINCT e.userId)
        FROM UserEvent e
        WHERE e.event = :event AND e.userId IS NOT NULL AND e.createdAt >= :since
        """,
    )
    fun distinctUsersSince(@Param("event") event: String, @Param("since") since: Instant): Long

    /** 관리자 최근 이벤트 열람(감독/디버깅). */
    fun findTop200ByOrderByIdDesc(): List<UserEvent>
}
