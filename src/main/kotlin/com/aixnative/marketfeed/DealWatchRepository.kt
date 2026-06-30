package com.aixnative.marketfeed

import org.springframework.data.jpa.repository.JpaRepository

interface DealWatchRepository : JpaRepository<DealWatch, Long> {
    fun findByTenantIdAndOwnerUserIdOrderByIdDesc(tenantId: Long, ownerUserId: Long): List<DealWatch>
    fun findByTenantIdAndOwnerUserIdAndFeedItemId(tenantId: Long, ownerUserId: Long, feedItemId: Long): DealWatch?
    fun existsByTenantIdAndOwnerUserIdAndFeedItemId(tenantId: Long, ownerUserId: Long, feedItemId: Long): Boolean

    /** 계정 삭제 정리용 — 해당 사용자의 관심 딜 전부 삭제. */
    fun deleteByOwnerUserId(ownerUserId: Long)
}
