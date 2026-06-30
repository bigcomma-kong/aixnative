package com.aixnative.marketfeed

import org.springframework.data.jpa.repository.JpaRepository

interface NewsSubscriberRepository : JpaRepository<NewsSubscriber, Long> {
    fun findByEmail(email: String): NewsSubscriber?
    fun findByUnsubToken(token: String): NewsSubscriber?
    fun findAllByActiveTrue(): List<NewsSubscriber>
    fun countByActiveTrue(): Long

    /** 계정 삭제 정리용 — 해당 이메일 구독 삭제. */
    fun deleteByEmail(email: String)
}
