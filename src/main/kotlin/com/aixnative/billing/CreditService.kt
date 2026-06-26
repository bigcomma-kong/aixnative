package com.aixnative.billing

import com.aixnative.common.web.InsufficientCreditsException
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Credit accounting over the append-only [CreditLedger].
 * Unit of charge = 1 AI analysis = 1 credit (확정 결정).
 */
@Service
class CreditService( private val ledger: CreditLedgerRepository, @Value("\${billing.free-signup-credits}") private val freeSignupCredits: Int, ) {

    /** Free credits granted once at signup. */
    @Transactional
    fun grantSignupCredits(tenantId: Long, userId: Long): Int {
        record(tenantId, userId, freeSignupCredits, CreditReason.SIGNUP_GRANT)
        return freeSignupCredits
    }

    @Transactional(readOnly = true)
    fun balance(tenantId: Long, userId: Long): Int = ledger.balance(tenantId, userId)

    /**
     * Consume one credit. Throws [InsufficientCreditsException] (→402) when the
     * balance is empty so the caller renders the paywall.
     *
     * Note: balance check + insert are not strictly serialized; under heavy
     * concurrent abuse a tenant could momentarily go to -1. Acceptable for v1
     * (single user per tenant); a row lock / DB constraint can harden it later.
     */
    @Transactional
    fun debitForAnalysis(tenantId: Long, userId: Long) {
        if (balance(tenantId, userId) <= 0) throw InsufficientCreditsException()
        record(tenantId, userId, -1, CreditReason.AI_ANALYSIS)
    }

    private fun record(tenantId: Long, userId: Long, delta: Int, reason: CreditReason) {
        ledger.save(CreditLedger(tenantId = tenantId, userId = userId, delta = delta, reason = reason))
    }
}
