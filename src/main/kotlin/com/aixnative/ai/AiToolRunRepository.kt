package com.aixnative.ai

import org.springframework.data.jpa.repository.JpaRepository

interface AiToolRunRepository : JpaRepository<AiToolRun, Long> {

    /** Tenant-scoped lookup — never load a row from another tenant (IDOR 차단). */
    fun findByIdAndTenantId(id: Long, tenantId: Long): AiToolRun?

    /** Active (non-deleted) runs for one tenant/user, newest first. */
    fun findByTenantIdAndOwnerUserIdAndDeletedAtIsNullOrderByIdDesc(
        tenantId: Long,
        ownerUserId: Long,
    ): List<AiToolRun>
}
