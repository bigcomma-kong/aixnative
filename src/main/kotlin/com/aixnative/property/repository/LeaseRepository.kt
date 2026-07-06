package com.aixnative.property.repository

import com.aixnative.property.domain.Lease
import org.springframework.data.jpa.repository.JpaRepository

interface LeaseRepository : JpaRepository<Lease, Long> {
    /** 한 건물의 임대차(최신순). 테넌트 스코프. */
    fun findByTenantIdAndOwnerUserIdAndBuildingIdOrderByIdDesc(
        tenantId: Long,
        ownerUserId: Long,
        buildingId: Long,
    ): List<Lease>

    /** 내 임대차 전체(테넌트 스코프) - 리마인더 스캔용. */
    fun findByTenantIdAndOwnerUserId(tenantId: Long, ownerUserId: Long): List<Lease>

    /** 단건 - 다른 테넌트의 id 면 null(IDOR 차단). */
    fun findByIdAndTenantId(id: Long, tenantId: Long): Lease?

    /** 건물 삭제 시 하위 임대차 정리용. */
    fun deleteByBuildingId(buildingId: Long)

    /** 계정 삭제 정리용. */
    fun deleteByOwnerUserId(ownerUserId: Long)
}
