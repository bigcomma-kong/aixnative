package com.aixnative.marketfeed

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

/** 뉴스레터 발송 로그 1건(누구에게/언제/성공여부). 관리자 추적용. */
@Entity
@Table(name = "newsletter_send_log")
@EntityListeners(AuditingEntityListener::class)
class NewsletterSendLog(
    @Column(nullable = false, length = 200)
    var email: String,

    @Column(length = 300)
    var subject: String? = null,

    @Column(nullable = false, length = 20)
    var status: String,

    @Column(name = "briefing_id")
    var briefingId: Long? = null,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null

    @CreatedDate
    @Column(name = "sent_at", nullable = false, updatable = false)
    var sentAt: Instant? = null
}
