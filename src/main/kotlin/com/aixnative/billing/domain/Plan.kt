package com.aixnative.billing.domain

/** Account plan. Payment wiring lands in Phase 5; for now only the field exists. */
enum class Plan { FREE, PAID }

/** Reason recorded on each append-only credit ledger entry. */
enum class CreditReason {
    /** Free credits granted at signup (+N). */
    SIGNUP_GRANT,

    /** One AI analysis consumed (-1). */
    AI_ANALYSIS,

    /** Purchased top-up (+M) — Phase 5. */
    PURCHASE,

    /** 관리자 수동 조정(+N/-N). 어드민 패널에서 자유 가감. */
    ADMIN_ADJUST,
}
