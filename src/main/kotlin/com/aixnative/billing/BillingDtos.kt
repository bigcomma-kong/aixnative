package com.aixnative.billing

import java.time.Instant

/** One credit ledger entry as shown in the user's usage history. */
data class CreditHistoryItem(
    val id: Long,
    val delta: Int,
    val reason: CreditReason,
    /** 변동 출처/경로(선택) — 충전 수단·금액, 관리자 조정 식별 등. */
    val ref: String?,
    val createdAt: Instant,
)

/** Billing snapshot for the signed-in user: plan, live balance, and full ledger history. */
data class BillingHistoryResponse(
    val plan: Plan,
    val creditBalance: Int,
    val entries: List<CreditHistoryItem>,
)

/** 가격표(분석유형 id → 크레딧 단가) + 가입 무료 지급량. 프론트 버튼 라벨/안내의 단일 소스. */
data class PricingResponse(
    val toolCosts: Map<String, Int>,
    val freeSignupCredits: Int,
)
