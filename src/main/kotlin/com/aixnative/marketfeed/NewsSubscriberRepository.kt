package com.aixnative.marketfeed

import org.springframework.data.jpa.repository.JpaRepository

interface NewsSubscriberRepository : JpaRepository<NewsSubscriber, Long> {
    fun findByEmail(email: String): NewsSubscriber?
    fun findByUnsubToken(token: String): NewsSubscriber?
    fun findAllByActiveTrue(): List<NewsSubscriber>
    fun countByActiveTrue(): Long
}
