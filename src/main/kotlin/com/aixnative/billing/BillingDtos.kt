package com.aixnative.billing

import java.time.Instant

/** One credit ledger entry as shown in the user's usage history. */
data class CreditHistoryItem(
    val id: Long,
    val delta: Int,
    val reason: CreditReason,
    val createdAt: Instant,
)

/** Billing snapshot for the signed-in user: plan, live balance, and full ledger history. */
data class BillingHistoryResponse(
    val plan: Plan,
    val creditBalance: Int,
    val entries: List<CreditHistoryItem>,
)
