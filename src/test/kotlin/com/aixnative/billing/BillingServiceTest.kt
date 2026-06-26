package com.aixnative.billing

import com.aixnative.account.Tenant
import com.aixnative.account.TenantRepository
import com.aixnative.account.User
import com.aixnative.account.UserRepository
import com.aixnative.common.web.NotFoundException
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.annotation.Transactional
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class BillingServiceTest(
    @Autowired private val billingService: BillingService,
    @Autowired private val creditService: CreditService,
    @Autowired private val tenants: TenantRepository,
    @Autowired private val users: UserRepository,
) {

    private fun newUser(): Pair<Long, Long> {
        val tenant = tenants.save(Tenant(name = "t@example.com"))
        val tenantId = requireNotNull(tenant.id)
        val user = users.save(User(tenantId = tenantId, email = "t@example.com"))
        return tenantId to requireNotNull(user.id)
    }

    @Test
    fun `history returns plan, live balance and newest-first ledger`() {
        val (tenantId, userId) = newUser()
        creditService.grantSignupCredits(tenantId, userId) // +5
        creditService.debitForAnalysis(tenantId, userId) // -1

        val result = billingService.history(tenantId, userId)

        assertEquals(Plan.FREE, result.plan)
        assertEquals(4, result.creditBalance)
        assertEquals(2, result.entries.size)
        // Newest first: the analysis debit precedes the signup grant.
        assertEquals(CreditReason.AI_ANALYSIS, result.entries[0].reason)
        assertEquals(-1, result.entries[0].delta)
        assertEquals(CreditReason.SIGNUP_GRANT, result.entries[1].reason)
        assertEquals(5, result.entries[1].delta)
    }

    @Test
    fun `history is empty for a user with no ledger activity`() {
        val (tenantId, userId) = newUser()

        val result = billingService.history(tenantId, userId)

        assertEquals(0, result.creditBalance)
        assertEquals(0, result.entries.size)
    }

    @Test
    fun `history raises 404 for an unknown user`() {
        assertFailsWith<NotFoundException> {
            billingService.history(1L, 999_999L)
        }
    }
}
