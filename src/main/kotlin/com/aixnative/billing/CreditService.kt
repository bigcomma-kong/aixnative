package com.aixnative.billing

import com.aixnative.common.web.InsufficientCreditsException
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Credit accounting over the append-only [CreditLedger].
 * Unit of charge = 1 AI analysis = N credits (가치 기반 차등, 단가는 [ToolPricing]).
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

    /** 어드민 전용 — 전 사용자 최근 원장(최신순 상한). 호출부(AdminController)가 ADMIN 가드. */
    @Transactional(readOnly = true)
    fun recentLedgerAdmin(): List<CreditLedger> = ledger.findTop300ByOrderByIdDesc()

    /**
     * 결제 충전 — 승인된 결제 1건당 크레딧을 원장에 지급(PURCHASE). 갱신 잔액 반환.
     * 호출부(PaymentService)가 결제 승인검증·멱등을 보장한다.
     */
    @Transactional
    fun grantPurchase(tenantId: Long, userId: Long, credits: Int, ref: String? = null): Int {
        require(credits > 0) { "충전 크레딧은 1 이상이어야 합니다." }
        record(tenantId, userId, credits, CreditReason.PURCHASE, ref)
        return ledger.balance(tenantId, userId)
    }

    /**
     * 관리자 수동 조정. delta(+/-)를 원장에 기록하고 갱신 잔액을 반환.
     * 어드민 패널 전용 — 호출부(AdminController)가 ADMIN 권한을 보장한다.
     * [ref] 에 조정한 관리자 식별을 남겨 감사 추적을 돕는다.
     */
    @Transactional
    fun adminAdjust(tenantId: Long, userId: Long, delta: Int, ref: String? = null): Int {
        require(delta != 0) { "조정 수량은 0이 아니어야 합니다." }
        record(tenantId, userId, delta, CreditReason.ADMIN_ADJUST, ref)
        return ledger.balance(tenantId, userId)
    }

    /**
     * Consume [amount] credits for one analysis. Throws [InsufficientCreditsException]
     * (→402, with the required/remaining amounts) when the balance can't cover it so
     * the caller renders the paywall.
     *
     * Note: balance check + insert are not strictly serialized; under heavy
     * concurrent abuse a tenant could momentarily go negative. Acceptable for v1
     * (single user per tenant); a row lock / DB constraint can harden it later.
     */
    @Transactional
    fun debitForAnalysis(tenantId: Long, userId: Long, amount: Int) {
        require(amount > 0) { "차감 크레딧은 1 이상이어야 합니다." }
        val current = balance(tenantId, userId)
        if (current < amount) throw InsufficientCreditsException.forRequirement(amount, current)
        record(tenantId, userId, -amount, CreditReason.AI_ANALYSIS)
    }

    private fun record(tenantId: Long, userId: Long, delta: Int, reason: CreditReason, ref: String? = null) {
        ledger.save(CreditLedger(tenantId = tenantId, userId = userId, delta = delta, reason = reason, ref = ref))
    }
}
