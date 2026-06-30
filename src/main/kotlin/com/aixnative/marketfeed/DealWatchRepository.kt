package com.aixnative.marketfeed

import org.springframework.data.jpa.repository.JpaRepository

interface DealWatchRepository : JpaRepository<DealWatch, Long> {
    fun findByTenantIdAndOwnerUserIdOrderByIdDesc(tenantId: Long, ownerUserId: Long): List<DealWatch>
    fun findByTenantIdAndOwnerUserIdAndFeedItemId(tenantId: Long, ownerUserId: Long, feedItemId: Long): DealWatch?
    fun existsByTenantIdAndOwnerUserIdAndFeedItemId(tenantId: Long, ownerUserId: Long, feedItemId: Long): Boolean
}
