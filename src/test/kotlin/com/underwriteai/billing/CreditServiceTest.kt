package com.underwriteai.billing

import com.underwriteai.common.web.InsufficientCreditsException
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
class CreditServiceTest(
    @Autowired private val creditService: CreditService,
) {

    private val tenantId = 100L
    private val userId = 200L

    @Test
    fun `grant then balance reflects the free allotment`() {
        val granted = creditService.grantSignupCredits(tenantId, userId)
        assertEquals(5, granted)
        assertEquals(5, creditService.balance(tenantId, userId))
    }

    @Test
    fun `debit decrements balance and empties after N analyses`() {
        creditService.grantSignupCredits(tenantId, userId)
        repeat(5) { creditService.debitForAnalysis(tenantId, userId) }
        assertEquals(0, creditService.balance(tenantId, userId))
    }

    @Test
    fun `debit with empty balance raises 402 paywall error`() {
        creditService.grantSignupCredits(tenantId, userId)
        repeat(5) { creditService.debitForAnalysis(tenantId, userId) }
        assertFailsWith<InsufficientCreditsException> {
            creditService.debitForAnalysis(tenantId, userId)
        }
    }

    @Test
    fun `balances are isolated per tenant-user`() {
        creditService.grantSignupCredits(tenantId, userId)
        assertEquals(0, creditService.balance(999L, 999L))
    }
}
