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
import java.time.LocalDate

/**
 * 마켓 브리핑 — 무료 AI가 수집 기사 풀을 합성한 일일 시장 다이제스트(뉴스레터 강점).
 * sections/watchlist/risks 는 JSON 문자열로 보관(프론트에서 파싱). 매 수집마다 새 행, 최신 1건 노출.
 */
@Entity
@Table(name = "market_briefing")
@EntityListeners(AuditingEntityListener::class)
class MarketBriefing(
    @Column(name = "briefing_date", nullable = false)
    var briefingDate: LocalDate,

    @Column(length = 500)
    var headline: String? = null,

    @Column(columnDefinition = "TEXT")
    var outlook: String? = null,

    @Column(name = "sections_json", columnDefinition = "TEXT")
    var sectionsJson: String? = null,

    @Column(name = "watchlist_json", columnDefinition = "TEXT")
    var watchlistJson: String? = null,

    @Column(name = "risks_json", columnDefinition = "TEXT")
    var risksJson: String? = null,

    @Column(name = "article_count")
    var articleCount: Int? = null,

    @Column(name = "ai_provider", length = 40)
    var aiProvider: String? = null,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null

    @CreatedDate
    @Column(name = "generated_at", nullable = false, updatable = false)
    var generatedAt: Instant? = null
}
