package com.aixnative.lead.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EntityListeners
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.annotation.CreatedDate
import org.springframework.data.jpa.domain.support.AuditingEntityListener
import java.time.Instant

/**
 * 리드(비회원) — 가입 전 공개 도구(무료 ProForma 계산기 등)에서 캡처한 이메일.
 * 계정·테넌트가 아직 없는 단계라 테넌트 비스코프(news_subscriber 와 동일 정책).
 * 테이블 정의: db/migration/V16__lead.sql.
 */
@Entity
@Table(name = "lead")
@EntityListeners(AuditingEntityListener::class)
class Lead(
    @Column(nullable = false, length = 200)
    var email: String,

    /** 유입 도구 식별(예: FREE_PROFORMA). */
    @Column(nullable = false, length = 60)
    var source: String,

    @Column(name = "marketing_opt_in", nullable = false)
    var marketingOptIn: Boolean = false,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant? = null
}
