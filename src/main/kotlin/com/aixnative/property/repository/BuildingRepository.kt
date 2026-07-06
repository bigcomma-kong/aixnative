package com.aixnative.property.repository

import com.aixnative.property.domain.Building
import org.springframework.data.jpa.repository.JpaRepository

interface BuildingRepository : JpaRepository<Building, Long> {
    /** 내 건물 목록(최신순). 테넌트 스코프. */
    fun findByTenantIdAndOwnerUserIdOrderByIdDesc(tenantId: Long, ownerUserId: Long): List<Building>

    /** 단건 - 다른 테넌트의 id 면 null(IDOR 차단). */
    fun findByIdAndTenantId(id: Long, tenantId: Long): Building?

    /** 계정 삭제 정리용. */
    fun deleteByOwnerUserId(ownerUserId: Long)
}
