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

/**
 * 시장 인텔리전스 피드 항목 — 큐레이션된 거래/시장 다이제스트.
 * 글로벌 콘텐츠(테넌트 비스코프): 모든 사용자에게 동일하게 노출되는 홍보·인게이지먼트 surface.
 * 읽기 = 인증 사용자, 쓰기 = ADMIN. [sourceText] 는 '이 딜 분석하기' 진입 시 딜 추출로 넘기는 원문.
 */
@Entity
@Table(name = "market_feed_item")
@EntityListeners(AuditingEntityListener::class)
class MarketFeedItem(
    @Column(nullable = false, length = 200)
    var title: String,

    @Column(length = 1000)
    var summary: String? = null,

    /** 오피스 | 물류 | 호텔 | 리테일 | null */
    @Column(name = "asset_type", length = 20)
    var assetType: String? = null,

    @Column(length = 120)
    var location: String? = null,

    @Column(name = "source_text", length = 4000)
    var sourceText: String? = null,

    @Column(name = "source_url", length = 500)
    var sourceUrl: String? = null,

    @Column(name = "published_at")
    var publishedAt: Instant? = null,

    /** 출처: 'ADMIN'(수동) | 'RSS:<매체>' | 'GOOGLE_NEWS' 등. */
    @Column(length = 40)
    var origin: String? = null,

    /** 중복제거 키 — 정규화된 기사 링크. 자동 수집 시 재삽입 차단용(수동 등록은 null). */
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
