package com.underwriteai.ai

import com.underwriteai.common.tenant.BaseTenantEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Table
import java.time.Instant

enum class RunStatus { SUCCESS, FAILED }

/**
 * Persisted AI analysis run (tenant-scoped via [BaseTenantEntity]). Soft-deleted
 * by setting [deletedAt]; queries filter it out.
 */
@Entity
@Table(name = "ai_tool_run")
class AiToolRun(
    @Column(nullable = false, length = 60)
    var tool: String,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var status: RunStatus,

    @Column(name = "request_hash", length = 64)
    var requestHash: String? = null,

    @Column(name = "result_ref", length = 200)
    var resultRef: String? = null,

    @Column(name = "deleted_at")
    var deletedAt: Instant? = null,
) : BaseTenantEntity()
