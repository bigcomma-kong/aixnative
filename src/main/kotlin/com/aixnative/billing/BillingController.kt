package com.aixnative.billing

import com.aixnative.common.tenant.TenantContext
import com.aixnative.common.web.ApiResponse
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Billing/credit endpoints for the signed-in user. Auth required (JWT); the
 * caller is resolved from [TenantContext], so history is always self-scoped.
 */
@RestController
@RequestMapping("/api/billing")
class BillingController(private val billingService: BillingService) {

    /** Plan + live credit balance + full ledger history for the current user. */
    @GetMapping("/history")
    fun history(): ApiResponse<BillingHistoryResponse> {
        val current = TenantContext.require()
        return ApiResponse.ok(billingService.history(current.tenantId, current.userId))
    }
}
