package com.aixnative.residential.repository

import com.aixnative.residential.domain.ResidentialBrief
import org.springframework.data.jpa.repository.JpaRepository

/** AI 동네 브리핑 저장소 - 테넌트+사용자 스코프 최신순 조회(마이페이지). */
interface ResidentialBriefRepository : JpaRepository<ResidentialBrief, Long> {
    fun findTop50ByTenantIdAndOwnerUserIdOrderByIdDesc(tenantId: Long, ownerUserId: Long): List<ResidentialBrief>
}
