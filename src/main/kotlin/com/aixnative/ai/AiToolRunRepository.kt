package com.aixnative.ai

import org.springframework.data.jpa.repository.JpaRepository

interface AiToolRunRepository : JpaRepository<AiToolRun, Long> {

    /** Tenant-scoped lookup — never load a row from another tenant (IDOR 차단). */
    fun findByIdAndTenantId(id: Long, tenantId: Long): AiToolRun?

    /** 전 테넌트 활성 런(어드민 전용 — 운영 감독). newest first. */
    fun findByDeletedAtIsNullOrderByIdDesc(): List<AiToolRun>

    /** Active (non-deleted) runs for one tenant/user, newest first. */
    fun findByTenantIdAndOwnerUserIdAndDeletedAtIsNullOrderByIdDesc(
        tenantId: Long,
        ownerUserId: Long,
    ): List<AiToolRun>

    /**
     * Active runs for one deal (tenant/user scoped), newest first.
     * IC 메모 등 후행 단계가 같은 딜의 앞 단계 결과를 종합(체이닝)할 때 사용.
     */
    fun findByTenantIdAndOwnerUserIdAndDealNameAndDeletedAtIsNullOrderByIdDesc(
        tenantId: Long,
        ownerUserId: Long,
        dealName: String,
    ): List<AiToolRun>
}
