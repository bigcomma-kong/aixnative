package com.aixnative.marketfeed.repository

import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import com.aixnative.marketfeed.domain.NewsletterSendLog

interface NewsletterSendLogRepository : JpaRepository<NewsletterSendLog, Long> {
    /** 최근 발송 로그(최신순). */
    fun findAllByOrderBySentAtDescIdDesc(pageable: Pageable): List<NewsletterSendLog>
}
