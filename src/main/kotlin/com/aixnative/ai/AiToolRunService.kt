package com.aixnative.ai

import com.aixnative.common.tenant.TenantContext
import com.aixnative.common.web.NotFoundException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
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

    /** Tenant-scoped fetch. Returns 404 for a missing row OR one owned by another tenant. */
    @Transactional(readOnly = true)
    fun get(id: Long): AiToolRun {
        val tenantId = TenantContext.requireTenantId()
        return repository.findByIdAndTenantId(id, tenantId)
            ?: throw NotFoundException("분석 이력을 찾을 수 없습니다.")
    }

    @Transactional
    fun softDelete(id: Long) {
        val run = get(id) // enforces tenant scope
        run.deletedAt = Instant.now()
        repository.save(run)
    }
}
