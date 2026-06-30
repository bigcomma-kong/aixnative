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

    /** 어드민 전용 — 전 사용자 최근 원장(테넌트 격리 의도적 우회). 최신순 상한. */
    fun findTop300ByOrderByIdDesc(): List<CreditLedger>

    /** 계정 삭제 정리용 — 해당 사용자의 원장 전부 삭제. */
    fun deleteByUserId(userId: Long)
}
