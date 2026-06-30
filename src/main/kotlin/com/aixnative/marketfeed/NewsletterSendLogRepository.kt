package com.aixnative.marketfeed

import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository

interface NewsletterSendLogRepository : JpaRepository<NewsletterSendLog, Long> {
    /** 최근 발송 로그(최신순). */
    fun findAllByOrderBySentAtDescIdDesc(pageable: Pageable): List<NewsletterSendLog>
}
