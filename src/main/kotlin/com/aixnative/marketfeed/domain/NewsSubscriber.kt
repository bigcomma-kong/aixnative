package com.aixnative.marketfeed.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EntityListeners
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.annotation.CreatedDate
import org.springframework.data.annotation.LastModifiedDate
import org.springframework.data.jpa.domain.support.AuditingEntityListener
import java.time.Instant

/**
 * 마켓 브리핑 메일 구독자(무료). 매일 아침 브리핑을 받아 재방문 → 딜 분석(크레딧 소비)로 이어지게 하는 퍼널.
 * 글로벌 콘텐츠라 테넌트 스코프 없음. 해지는 [unsubToken] 기반 공개 링크.
 */
@Entity
@Table(name = "news_subscriber")
@EntityListeners(AuditingEntityListener::class)
class NewsSubscriber(
    @Column(nullable = false, unique = true, length = 200)
    var email: String,

    @Column(name = "unsub_token", nullable = false, length = 64)
    var unsubToken: String,

    @Column(nullable = false)
    var active: Boolean = true,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant? = null

    @LastModifiedDate
    @Column(name = "updated_at")
    var updatedAt: Instant? = null
}
