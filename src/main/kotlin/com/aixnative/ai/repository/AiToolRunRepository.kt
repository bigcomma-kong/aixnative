package com.aixnative.ai.repository

import org.springframework.data.jpa.repository.JpaRepository
import java.time.Instant
import com.aixnative.ai.domain.AiToolRun
import com.aixnative.ai.domain.RunStatus

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

    /**
     * 최근 시간창 내 같은 도구의 성공 런(테넌트/유저 스코프), 최신순.
     * 중복 분석 가드 — 호출부가 requestJson(저장된 원본 입력)을 비교해 동일 입력 재실행을 감지.
     */
    fun findByTenantIdAndOwnerUserIdAndToolAndStatusAndCreatedAtAfterAndDeletedAtIsNullOrderByIdDesc(
        tenantId: Long,
        ownerUserId: Long,
        tool: String,
        status: RunStatus,
        createdAt: Instant,
    ): List<AiToolRun>

    /** 공유 토큰으로 조회(무인증 공개 보고서) — 테넌트 무관. 삭제된 런은 제외. */
    fun findByShareTokenAndDeletedAtIsNull(shareToken: String): AiToolRun?

    /** 계정 삭제 정리용 — 해당 사용자의 모든 분석 런 삭제. */
    fun deleteByOwnerUserId(ownerUserId: Long)
}
