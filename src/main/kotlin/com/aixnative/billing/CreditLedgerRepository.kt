package com.aixnative.billing

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface CreditLedgerRepository : JpaRepository<CreditLedger, Long> {

    /** Current balance = sum of all deltas for this tenant/user (0 if none). */
    @Query(
        "select coalesce(sum(c.delta), 0) from CreditLedger c " +
            "where c.tenantId = :tenantId and c.userId = :userId",
    )
    fun balance(@Param("tenantId") tenantId: Long, @Param("userId") userId: Long): Int

    /** Ledger entries newest-first. Ordered by id (monotonic) so same-instant rows stay stable. */
    fun findByTenantIdAndUserIdOrderByIdDesc(tenantId: Long, userId: Long): List<CreditLedger>

      


}
