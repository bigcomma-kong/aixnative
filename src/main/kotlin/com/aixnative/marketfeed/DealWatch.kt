package com.aixnative.marketfeed

import com.aixnative.common.tenant.BaseTenantEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table

/**
 * 관심 딜(찜) — 사용자가 저장한 시장 피드 카드. 테넌트 스코프([BaseTenantEntity]).
 * 표시 필드를 비정규화 저장해 원본 카드가 purge 돼도 목록이 유지된다.
 */
@Entity
@Table(name = "deal_watch")
class DealWatch(
    @Column(name = "feed_item_id", nullable = false)
    var feedItemId: Long,

    @Column(nullable = false, length = 300)
    var title: String,

    @Column(length = 1000)
    var summary: String? = null,

    @Column(name = "asset_type", length = 20)
    var assetType: String? = null,

    @Column(length = 120)
    var location: String? = null,

    @Column(name = "source_text", length = 4000)
    var sourceText: String? = null,

    @Column(name = "source_url", length = 500)
    var sourceUrl: String? = null,
) : BaseTenantEntity()
