package com.aixnative.property

import com.aixnative.property.domain.Lease
import com.aixnative.property.domain.LeaseAnalytics
import com.aixnative.property.domain.LeaseEventType
import com.aixnative.property.domain.LeaseStatus
import org.junit.jupiter.api.Test
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LeaseAnalyticsTest {

    private val today = LocalDate.of(2026, 1, 1)

    private fun lease(
        id: Long,
        tenant: String,
        rent: Double? = null,
        deposit: Double? = null,
        area: Double? = null,
        start: LocalDate? = null,
        end: LocalDate? = null,
        rentFreeMonths: Int? = null,
        nextEscalation: LocalDate? = null,
    ): Lease = Lease(
        buildingId = 1,
        tenantName = tenant,
        areaPyeong = area,
        monthlyRentManwon = rent,
        depositManwon = deposit,
        leaseStartDate = start,
        leaseEndDate = end,
        rentFreeMonths = rentFreeMonths,
        nextEscalationDate = nextEscalation,
    ).apply { this.id = id }

    @Test
    fun `aggregate computes totals, WALT, concentration and rent per pyeong`() {
        val leases = listOf(
            lease(1, "메가리테일", rent = 9000.0, deposit = 100000.0, area = 800.0, end = LocalDate.of(2026, 12, 27)),
            lease(2, "한빛물류", rent = 3000.0, deposit = 40000.0, area = 200.0, end = LocalDate.of(2027, 12, 27)),
        )
        val agg = LeaseAnalytics.aggregate(leases, today)

        assertEquals(12000.0, agg.totalMonthlyRent)
        assertEquals(144000.0, agg.annualRent)
        assertEquals(140000.0, agg.totalDeposit)
        assertEquals("메가리테일", agg.topTenantName)
        assertEquals(75.0, agg.topTenantPct)          // 9000 / 12000
        assertEquals(12.0, agg.avgRentPerPyeong)       // 12000 / 1000평
        // WALT = (9000*360/365.25 + 3000*725/365.25) / 12000 ≈ 1.24년
        assertEquals(1.24, agg.waltYears!!, absoluteTolerance = 0.01)
    }

    @Test
    fun `aggregate groups same tenant across units for concentration`() {
        val leases = listOf(
            lease(1, "A사", rent = 4000.0),
            lease(2, "A사", rent = 2000.0),   // 같은 임차인 다른 호실
            lease(3, "B사", rent = 4000.0),
        )
        val agg = LeaseAnalytics.aggregate(leases, today)
        assertEquals("A사", agg.topTenantName)          // 4000+2000 = 6000 > 4000
        assertEquals(60.0, agg.topTenantPct)            // 6000 / 10000
    }

    @Test
    fun `risk flags detect expiry-soon, concentration, short WALT and data gaps`() {
        val leases = listOf(
            lease(1, "C사", rent = 1000.0, start = today, end = LocalDate.of(2026, 2, 15)), // 45일 후 만기
            lease(2, "D사"),                                                                 // 임대료·만기 미입력
        )
        val agg = LeaseAnalytics.aggregate(leases, today)
        val flags = LeaseAnalytics.riskFlags(leases, today, agg)
        val sev = flags.groupingBy { it.severity }.eachCount()

        // 90일내 만기(45일, MEDIUM) + 집중도 HIGH(100%) + WALT<2 MEDIUM + 만기 미입력 MEDIUM + 임대료 미입력 LOW
        assertEquals(1, sev["HIGH"])
        assertEquals(3, sev["MEDIUM"])
        assertEquals(1, sev["LOW"])
        assertTrue(flags.any { it.label.contains("90일 내 만기") })
        assertTrue(flags.any { it.label.contains("집중도") && it.severity == "HIGH" })
        assertTrue(flags.any { it.label.contains("만기일 미입력") })
    }

    @Test
    fun `expiry within 30 days is HIGH`() {
        val leases = listOf(lease(1, "E사", rent = 1000.0, end = today.plusDays(10)))
        val agg = LeaseAnalytics.aggregate(leases, today)
        val flags = LeaseAnalytics.riskFlags(leases, today, agg)
        assertTrue(flags.any { it.label.contains("90일 내 만기") && it.severity == "HIGH" })
    }

    @Test
    fun `empty leases yield no flags`() {
        val agg = LeaseAnalytics.aggregate(emptyList(), today)
        assertEquals(0.0, agg.totalMonthlyRent)
        assertNull(agg.waltYears)
        assertTrue(LeaseAnalytics.riskFlags(emptyList(), today, agg).isEmpty())
    }

    @Test
    fun `events cover expiry, escalation and rent-free end`() {
        val l = lease(
            1, "F사", rent = 1000.0, start = LocalDate.of(2026, 1, 1),
            end = LocalDate.of(2026, 2, 15), rentFreeMonths = 2, nextEscalation = LocalDate.of(2026, 1, 20),
        )
        val events = LeaseAnalytics.events(l, today)
        val types = events.map { it.type }.toSet()
        assertEquals(setOf(LeaseEventType.EXPIRY, LeaseEventType.ESCALATION, LeaseEventType.RENT_FREE_END), types)

        val expiry = events.first { it.type == LeaseEventType.EXPIRY }
        assertEquals(45, expiry.daysUntil)             // 2026-01-01 → 02-15
        val rentFree = events.first { it.type == LeaseEventType.RENT_FREE_END }
        assertEquals(LocalDate.of(2026, 3, 1), rentFree.dueDate) // start + 2개월
    }

    @Test
    fun `status derives from dates`() {
        assertEquals(LeaseStatus.UNKNOWN, LeaseAnalytics.status(lease(1, "x"), today))
        assertEquals(LeaseStatus.UPCOMING, LeaseAnalytics.status(lease(2, "x", start = today.plusDays(30)), today))
        assertEquals(LeaseStatus.EXPIRED, LeaseAnalytics.status(lease(3, "x", end = today.minusDays(1)), today))
        assertEquals(LeaseStatus.ACTIVE, LeaseAnalytics.status(lease(4, "x", start = today.minusDays(10), end = today.plusDays(10)), today))
        assertEquals(10L, LeaseAnalytics.daysToExpiry(today, lease(5, "x", end = today.plusDays(10))))
        assertNull(LeaseAnalytics.daysToExpiry(today, lease(6, "x")))
    }
}
