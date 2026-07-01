package com.aixnative.billing

import com.aixnative.common.web.InsufficientCreditsException
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.annotation.Transactional
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import com.aixnative.billing.service.CreditService

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
        repeat(5) { creditService.debitForAnalysis(tenantId, userId, 1) }
        assertEquals(0, creditService.balance(tenantId, userId))
    }

    @Test
    fun `debit with empty balance raises 402 paywall error`() {
        creditService.grantSignupCredits(tenantId, userId)
        repeat(5) { creditService.debitForAnalysis(tenantId, userId, 1) }
        assertFailsWith<InsufficientCreditsException> {
            creditService.debitForAnalysis(tenantId, userId, 1)
        }
    }

    @Test
    fun `debit by N decrements by that amount`() {
        creditService.grantSignupCredits(tenantId, userId) // 5 (test profile)
        creditService.debitForAnalysis(tenantId, userId, 3)
        assertEquals(2, creditService.balance(tenantId, userId))
    }

    @Test
    fun `debit raises 402 when balance is below the cost`() {
        creditService.grantSignupCredits(tenantId, userId) // 5
        creditService.debitForAnalysis(tenantId, userId, 3) // 2 left
        assertFailsWith<InsufficientCreditsException> {
            creditService.debitForAnalysis(tenantId, userId, 5) // needs 5, has 2
        }
        assertEquals(2, creditService.balance(tenantId, userId)) // unchanged on failure
    }

    @Test
    fun `balances are isolated per tenant-user`() {
        creditService.grantSignupCredits(tenantId, userId)
        assertEquals(0, creditService.balance(999L, 999L))
    }
}
