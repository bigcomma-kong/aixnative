package com.aixnative.followup.domain

import com.aixnative.common.tenant.BaseTenantEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Table

/**
 * 후속 논의 턴 종류.
 *  - USER: 사용자 질문
 *  - AI: 질문에 대한 AI 답변(마크다운)
 *  - SUGGEST: AI 가 먼저 짚은 검토 포인트(질문 형태, 마크다운)
 *  - CRITIQUE: 반박 검토 결과(판정 JSON - verdict·issues·invalidation)
 */
enum class FollowUpRole { USER, AI, SUGGEST, CRITIQUE }

/**
 * 분석 결과(ai_tool_run) 1건에 쌓이는 후속 논의 턴. 테넌트 스코프([BaseTenantEntity]).
 * 원본 분석 결과는 그대로 두고 여기에만 쌓는다 - 재분석과는 별개 기능.
 */
@Entity
@Table(name = "ai_run_followup")
class FollowUpTurn(
    @Column(name = "run_id", nullable = false)
    var runId: Long,

    @Column(name = "deal_id")
    var dealId: Long? = null,

    @Column(name = "turn_seq", nullable = false)
    var turnSeq: Int,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var role: FollowUpRole,

    @Column(nullable = false)
    var content: String,

    @Column(length = 40)
    var provider: String? = null,
) : BaseTenantEntity()
