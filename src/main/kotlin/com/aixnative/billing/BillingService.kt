package com.aixnative.billing

import com.aixnative.account.UserRepository
import com.aixnative.common.web.NotFoundException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Read model for the prefree-tier UX: the signed-in user's plan, live credit
 * balance, and full append-only ledger history. All reads are scoped to the
 * caller's (tenant, user) so one account never sees another's usage.
 *
 * Reused later by an admin console (Phase 6) to inspect any user's ledger.
 */
@Service
class BillingService(
    private val ledger: CreditLedgerRepository,
    private val users: UserRepository,
) {

    @Transactional(readOnly = true)
    fun history(tenantId: Long, userId: Long): BillingHistoryResponse {
        val user = users.findById(userId).orElseThrow { NotFoundException("사용자를 찾을 수 없습니다.") }
        val balance = ledger.balance(tenantId, userId)
        val entries = ledger.findByTenantIdAndUserIdOrderByIdDesc(tenantId, userId).map {
            CreditHistoryItem(
                id = requireNotNull(it.id),
                delta = it.delta,
                reason = it.reason,
                createdAt = requireNotNull(it.createdAt),
            )
        }
        return BillingHistoryResponse(plan = user.plan, creditBalance = balance, entries = entries)
    }
}
