package com.aixnative.billing.service

import com.aixnative.common.tenant.TenantContext
import com.aixnative.common.web.InsufficientCreditsException
import org.springframework.stereotype.Component
import com.aixnative.billing.domain.ToolPricing

/**
 * Declarative marker for AI-analysis endpoints that cost a credit. The actual
 * debit is performed via [CreditGate.charge] in the service layer so that the
 * credit is consumed only when the AI call succeeds (Phase 2 wiring).
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class RequiresCredit

/**
 * Credit gate primitive: pre-checks balance (fast 402 when it can't cover [cost]),
 * runs the work, and debits exactly [cost] credits only on success. The current
 * tenant/user is resolved from [TenantContext], so all charges are tenant-scoped.
 * Per-analysis cost comes from [ToolPricing]; callers pass the resolved amount.
 */
@Component
class CreditGate(private val creditService: CreditService) {

    private companion object {
        const val ADMIN_ROLE = "ADMIN"
    }

    fun <T> charge(cost: Int, block: () -> T): T {
        val current = TenantContext.require()
        // ADMIN runs with unlimited credits — never balance-checked, never debited.
        if (current.role == ADMIN_ROLE) {
            return block()
        }
        // Fail fast with 402 before doing any expensive work.
        val balance = creditService.balance(current.tenantId, current.userId)
        if (balance < cost) throw InsufficientCreditsException.forRequirement(cost, balance)
        val result = block()
        // Debit only after the work succeeded (failed analyses are free).
        // Note: balance-check and debit run in separate transactions — the same TOCTOU
        // window documented in CreditService applies here. Acceptable for v1 (1 user/tenant);
        // harden with a row lock / DB check constraint when team plans land.
        creditService.debitForAnalysis(current.tenantId, current.userId, cost)
        return result
    }
}
