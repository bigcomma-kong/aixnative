package com.aixnative.analytics.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

/**
 * 경량 행동 이벤트 한 건. 방문·무료계산·분석시작 같은 얕은 퍼널 신호를 남긴다.
 * 익명 방문자도 기록하므로 [tenantId]·[userId] 는 null 가능. 과금 기록(ai_tool_run)과는 별개.
 * PII 를 담지 않는다(경로/짧은 meta 만) — 상세는 ai_tool_run·credit_ledger 가 담당.
 */
@Entity
@Table(name = "user_event")
class UserEvent(
    @Column(name = "tenant_id")
    var tenantId: Long? = null,

    @Column(name = "user_id")
    var userId: Long? = null,

    @Column(nullable = false, length = 40)
    var event: String,

    @Column(length = 200)
    var path: String? = null,

    @Column(length = 500)
    var meta: String? = null,

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now(),
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
}
