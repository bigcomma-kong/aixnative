package com.aixnative.followup.repository

import com.aixnative.followup.domain.FollowUpTurn
import org.springframework.data.jpa.repository.JpaRepository

interface FollowUpTurnRepository : JpaRepository<FollowUpTurn, Long> {

    /** 한 분석의 스레드(테넌트/유저 스코프), 순서대로. */
    fun findByTenantIdAndOwnerUserIdAndRunIdOrderByTurnSeqAscIdAsc(
        tenantId: Long,
        ownerUserId: Long,
        runId: Long,
    ): List<FollowUpTurn>

    /** 계정 삭제 정리용 - 해당 사용자의 모든 후속 논의 삭제. */
    fun deleteByOwnerUserId(ownerUserId: Long)
}
