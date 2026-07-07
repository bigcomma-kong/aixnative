package com.aixnative.ai.domain

import com.aixnative.common.tenant.BaseTenantEntity
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

    @Column(name = "deal_name", length = 200)
    var dealName: String? = null,

    /**
     * 딜 식별자(PK) — 딜의 anchor(첫) 런 id. 첫 분석은 self-anchor(자기 id),
     * 이후 분석은 같은 deal_id 로 묶인다. 딜명은 라벨일 뿐 식별은 이 값으로 한다.
     */
    @Column(name = "deal_id")
    var dealId: Long? = null,

    /** 분석 입력(JSON) — 조회 API 재현용. */
    @Column(name = "request_json")
    var requestJson: String? = null,

    /** 분석 결과(JSON: proForma+scenarios+analysis) — 조회 API 반환용. */
    @Column(name = "result_json")
    var resultJson: String? = null,

    @Column(name = "deleted_at")
    var deletedAt: Instant? = null,

    /** 읽기전용 공유 링크 토큰(발급 시 채워짐). 무인증 공개 보고서 조회에 사용. */
    @Column(name = "share_token", length = 64)
    var shareToken: String? = null,
) : BaseTenantEntity()
