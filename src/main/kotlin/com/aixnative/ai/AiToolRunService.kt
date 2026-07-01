package com.aixnative.ai

import com.aixnative.common.tenant.TenantContext
import com.aixnative.common.web.NotFoundException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.Instant

/**
 * Persists and reads AI analysis run history, always scoped to the current
 * tenant from [TenantContext]. All reads/writes are constrained to the caller's
 * tenant so one tenant can never see another's runs.
 */
@Service
class AiToolRunService(private val repository: AiToolRunRepository) {

    @Transactional
    fun record(
        tool: String,
        status: RunStatus,
        requestHash: String? = null,
        resultRef: String? = null,
        dealName: String? = null,
        requestJson: String? = null,
        resultJson: String? = null,
    ): AiToolRun {
        val current = TenantContext.require()
        val run = AiToolRun(
            tool = tool,
            status = status,
            requestHash = requestHash,
            resultRef = resultRef,
            dealName = dealName,
            requestJson = requestJson,
            resultJson = resultJson,
        )
        run.tenantId = current.tenantId
        run.ownerUserId = current.userId
        return repository.save(run)
    }

    @Transactional(readOnly = true)
    fun listMine(): List<AiToolRun> {
        val current = TenantContext.require()
        return repository.findByTenantIdAndOwnerUserIdAndDeletedAtIsNullOrderByIdDesc(
            current.tenantId,
            current.userId,
        )
    }

    /**
     * 같은 딜의 단계별 최신 성공 결과 JSON 맵(tool → resultJson). 테넌트/유저 스코프.
     * IC 메모 등 후행 단계가 앞 단계(스크리닝·시장조사·언더라이팅) 결과를 종합(체이닝)하는 데 사용.
     */
    @Transactional(readOnly = true)
    fun latestSuccessResultsByTool(dealName: String): Map<String, String> {
        val current = TenantContext.require()
        val runs = repository.findByTenantIdAndOwnerUserIdAndDealNameAndDeletedAtIsNullOrderByIdDesc(
            current.tenantId,
            current.userId,
            dealName,
        )
        val latest = LinkedHashMap<String, String>()
        for (run in runs) {
            val json = run.resultJson ?: continue
            if (run.status == RunStatus.SUCCESS && !latest.containsKey(run.tool)) {
                latest[run.tool] = json
            }
        }
        return latest
    }

    /**
     * 최근 [withinMinutes] 분 내 같은 도구의 성공 런(테넌트/유저 스코프, 최신순).
     * 중복 분석 가드용 — 호출부가 requestJson 을 비교해 동일 입력 재실행을 판정.
     */
    @Transactional(readOnly = true)
    fun findRecentByTool(tool: String, withinMinutes: Long): List<AiToolRun> {
        val current = TenantContext.require()
        val cutoff = Instant.now().minus(Duration.ofMinutes(withinMinutes))
        return repository.findByTenantIdAndOwnerUserIdAndToolAndStatusAndCreatedAtAfterAndDeletedAtIsNullOrderByIdDesc(
            current.tenantId,
            current.userId,
            tool,
            RunStatus.SUCCESS,
            cutoff,
        )
    }

    /**
     * 같은 딜의 단계별 최신 성공 런(tool → AiToolRun). 테넌트/유저 스코프.
     * 결과 합본 화면(딜 한 건의 스크리닝·시장조사·언더라이팅·투심을 한 번에)에서 사용.
     */
    @Transactional(readOnly = true)
    fun latestSuccessRunsForDeal(dealName: String): Map<String, AiToolRun> {
        val current = TenantContext.require()
        val runs = repository.findByTenantIdAndOwnerUserIdAndDealNameAndDeletedAtIsNullOrderByIdDesc(
            current.tenantId,
            current.userId,
            dealName,
        )
        val latest = LinkedHashMap<String, AiToolRun>()
        for (run in runs) {
            if (run.status == RunStatus.SUCCESS && !latest.containsKey(run.tool)) latest[run.tool] = run
        }
        return latest
    }

    /** 어드민 전용 — 전 테넌트 활성 런(테넌트 격리 의도적 우회). 호출부가 ADMIN 가드. */
    @Transactional(readOnly = true)
    fun listAllAdmin(): List<AiToolRun> = repository.findByDeletedAtIsNullOrderByIdDesc()

    /** 어드민 전용 — 테넌트 무관 단건 조회(데이터 점검용). */
    @Transactional(readOnly = true)
    fun getAdmin(id: Long): AiToolRun =
        repository.findById(id).orElseThrow { NotFoundException("분석 이력을 찾을 수 없습니다.") }

    /** Tenant-scoped fetch. Returns 404 for a missing row OR one owned by another tenant. */
    @Transactional(readOnly = true)
    fun get(id: Long): AiToolRun {
        val tenantId = TenantContext.requireTenantId()
        return repository.findByIdAndTenantId(id, tenantId)
            ?: throw NotFoundException("분석 이력을 찾을 수 없습니다.")
    }

    /**
     * 공유 토큰 발급(멱등) — 이미 있으면 그대로 반환. 테넌트 스코프(본인 런만).
     * 읽기전용 공유 링크에 사용. 토큰은 추측 불가한 랜덤(UUID hex).
     */
    @Transactional
    fun enableShare(id: Long): String {
        val run = get(id) // 테넌트 스코프 강제
        run.shareToken?.let { return it }
        val token = java.util.UUID.randomUUID().toString().replace("-", "") +
            java.util.UUID.randomUUID().toString().replace("-", "").take(8)
        run.shareToken = token.take(64)
        repository.save(run)
        return run.shareToken!!
    }

    /** 공유 토큰으로 런 조회(무인증 공개). 테넌트 스코프 없음 — 호출부는 공개 보고서 렌더에만 사용. */
    @Transactional(readOnly = true)
    fun getByShareToken(token: String): AiToolRun? =
        repository.findByShareTokenAndDeletedAtIsNull(token)

    /** 특정 소유자의 활성 런(최신순) — 공개 보고서가 딜 단계를 모을 때 TenantContext 없이 사용. */
    @Transactional(readOnly = true)
    fun listForOwner(tenantId: Long, ownerUserId: Long): List<AiToolRun> =
        repository.findByTenantIdAndOwnerUserIdAndDeletedAtIsNullOrderByIdDesc(tenantId, ownerUserId)

    @Transactional
    fun softDelete(id: Long) {
        val run = get(id) // enforces tenant scope
        run.deletedAt = Instant.now()
        repository.save(run)
    }
}
