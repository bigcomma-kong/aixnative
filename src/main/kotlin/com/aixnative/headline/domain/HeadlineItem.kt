package com.aixnative.headline.domain

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
 * 업계 헤드라인 한 건 — CRE 매체(SPI·딜사이트·코어비트)의 기사 제목·출처·발행시각만 보관.
 * 딜 카드([com.aixnative.marketfeed.domain.MarketFeedItem])와 분리된 경량 항목: 요약·자산유형 없음.
 * 글로벌 콘텐츠(테넌트 비스코프). 전부 자동 수집분이라 [dedupKey] 로 재삽입을 차단한다.
 */
@Entity
@Table(name = "headline_item")
@EntityListeners(AuditingEntityListener::class)
class HeadlineItem(
    @Column(nullable = false, length = 300)
    var title: String,

    /** 매체 라벨: 'SPI' | '딜사이트' | '코어비트'. */
    @Column(nullable = false, length = 40)
    var source: String,

    @Column(name = "source_url", length = 500)
    var sourceUrl: String? = null,

    @Column(name = "published_at")
    var publishedAt: Instant? = null,

    /** 중복제거 키 — 정규화된 기사 링크(재수집 시 중복 삽입 차단). */
    @Column(name = "dedup_key", length = 300)
    var dedupKey: String? = null,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant? = null
}
