package com.aixnative.property.domain

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * 임대차 결정론 분석(순수 로직) - 렌트롤 집계·상태·D-day·리스크·일정. 저장/AI 의존 없음.
 * PropertyService(화면·보고서)와 LeaseReminderService(배치)가 공유해 계산 일관성을 보장한다.
 * ([com.aixnative.underwriting.domain.ProFormaCalculator] 와 같은 순수 계산기 패턴.)
 */
object LeaseAnalytics {
    const val DAYS_PER_YEAR = 365.25

    /** 렌트롤 집계값(만원·년·%). */
    data class Aggregate(
        val totalMonthlyRent: Double,
        val totalDeposit: Double,
        val totalMgmtFee: Double,
        val annualRent: Double,
        val waltYears: Double?,
        val topTenantName: String?,
        val topTenantPct: Double?,
        val avgRentPerPyeong: Double?,
    )

    /** 리스크 플래그(label + severity HIGH|MEDIUM|LOW). */
    data class Flag(val label: String, val severity: String)

    /** 임대 일정 이벤트. */
    data class Event(
        val leaseId: Long,
        val buildingId: Long,
        val tenantName: String,
        val type: LeaseEventType,
        val dueDate: LocalDate,
        val daysUntil: Long,
    )

    /** 상태 파생 - 시작 전=UPCOMING, 만기 지남=EXPIRED, 진행 중=ACTIVE, 날짜 없음=UNKNOWN. */
    fun status(lease: Lease, today: LocalDate): LeaseStatus = when {
        lease.leaseStartDate == null && lease.leaseEndDate == null -> LeaseStatus.UNKNOWN
        lease.leaseStartDate != null && lease.leaseStartDate!!.isAfter(today) -> LeaseStatus.UPCOMING
        lease.leaseEndDate != null && lease.leaseEndDate!!.isBefore(today) -> LeaseStatus.EXPIRED
        else -> LeaseStatus.ACTIVE
    }

    /** 만기까지 남은 일수(음수면 지남). 만기일 없으면 null. */
    fun daysToExpiry(today: LocalDate, lease: Lease): Long? =
        lease.leaseEndDate?.let { ChronoUnit.DAYS.between(today, it) }

    fun aggregate(leases: List<Lease>, today: LocalDate): Aggregate {
        val totalMonthly = leases.sumOf { it.monthlyRentManwon ?: 0.0 }
        val totalDeposit = leases.sumOf { it.depositManwon ?: 0.0 }
        val totalMgmt = leases.sumOf { it.mgmtFeeManwon ?: 0.0 }

        // WALT - 월임대료 가중 잔여 임대기간(년). 만기일 + 임대료 있는 계약만.
        val walted = leases.filter { it.leaseEndDate != null && (it.monthlyRentManwon ?: 0.0) > 0 }
        val waltDen = walted.sumOf { it.monthlyRentManwon ?: 0.0 }
        val walt = if (waltDen > 0) {
            round2(walted.sumOf { (it.monthlyRentManwon ?: 0.0) * remainingYears(today, it.leaseEndDate!!) } / waltDen)
        } else null

        // 최대 임차인 집중도 - 임차인명으로 월임대료 합산 후 최대 비중.
        val byTenant = leases.filter { (it.monthlyRentManwon ?: 0.0) > 0 }
            .groupBy { it.tenantName }
            .mapValues { (_, ls) -> ls.sumOf { it.monthlyRentManwon ?: 0.0 } }
        val top = byTenant.maxByOrNull { it.value }
        val topPct = if (top != null && totalMonthly > 0) round1(top.value / totalMonthly * 100) else null

        // 평당 월임대료(만원/평) - 면적·임대료 있는 계약 가중평균.
        val areaLeases = leases.filter { (it.areaPyeong ?: 0.0) > 0 && (it.monthlyRentManwon ?: 0.0) > 0 }
        val areaSum = areaLeases.sumOf { it.areaPyeong ?: 0.0 }
        val avgPerPyeong = if (areaSum > 0) round2(areaLeases.sumOf { it.monthlyRentManwon ?: 0.0 } / areaSum) else null

        return Aggregate(
            totalMonthlyRent = round1(totalMonthly),
            totalDeposit = round1(totalDeposit),
            totalMgmtFee = round1(totalMgmt),
            annualRent = round1(totalMonthly * 12),
            waltYears = walt,
            topTenantName = top?.key,
            topTenantPct = topPct,
            avgRentPerPyeong = avgPerPyeong,
        )
    }

    /** 결정론 리스크 - 만기 경과·임박·집중도·짧은 WALT·데이터 공백. */
    fun riskFlags(leases: List<Lease>, today: LocalDate, agg: Aggregate): List<Flag> {
        if (leases.isEmpty()) return emptyList()
        val flags = mutableListOf<Flag>()

        val expired = leases.count { it.leaseEndDate != null && it.leaseEndDate!!.isBefore(today) }
        if (expired > 0) flags += Flag("$expired 개 계약이 만기 경과 - 갱신·퇴거 여부 확인 필요", "HIGH")

        val within30 = leases.count { daysToExpiry(today, it)?.let { d -> d in 0..30 } == true }
        val within90 = leases.count { daysToExpiry(today, it)?.let { d -> d in 0..90 } == true }
        if (within90 > 0) {
            flags += Flag("$within90 개 계약이 90일 내 만기 - 재계약 협상 착수 권장", if (within30 > 0) "HIGH" else "MEDIUM")
        }
        if (agg.topTenantPct != null && agg.topTenantPct >= 30 && agg.topTenantName != null) {
            flags += Flag(
                "임차인 집중도 높음 - '${agg.topTenantName}' 월임대료 비중 ${agg.topTenantPct}% (분산 필요)",
                if (agg.topTenantPct >= 50) "HIGH" else "MEDIUM",
            )
        }
        if (agg.waltYears != null && agg.waltYears < 2.0) {
            flags += Flag("가중평균 잔여 임대기간(WALT) ${agg.waltYears}년으로 짧음 - 만기 분산·조기 재계약 검토", "MEDIUM")
        }
        val missingEnd = leases.count { it.leaseEndDate == null }
        if (missingEnd > 0) flags += Flag("$missingEnd 개 계약 만기일 미입력 - 만기 관리 공백(계약서 확인)", "MEDIUM")

        val missingRent = leases.count { (it.monthlyRentManwon ?: 0.0) <= 0 }
        if (missingRent > 0) flags += Flag("$missingRent 개 계약 월임대료 미입력 - 렌트롤 집계 정확도 저하", "LOW")
        return flags
    }

    /** 한 임대차의 일정 이벤트(만기·인상·렌트프리 종료). 날짜 없는 이벤트는 제외. */
    fun events(lease: Lease, today: LocalDate): List<Event> {
        val id = lease.id ?: return emptyList()
        val out = mutableListOf<Event>()
        fun add(type: LeaseEventType, due: LocalDate?) {
            if (due == null) return
            out += Event(id, lease.buildingId, lease.tenantName, type, due, ChronoUnit.DAYS.between(today, due))
        }
        add(LeaseEventType.EXPIRY, lease.leaseEndDate)
        add(LeaseEventType.ESCALATION, lease.nextEscalationDate)
        if (lease.rentFreeMonths != null && lease.rentFreeMonths!! > 0 && lease.leaseStartDate != null) {
            add(LeaseEventType.RENT_FREE_END, lease.leaseStartDate!!.plusMonths(lease.rentFreeMonths!!.toLong()))
        }
        return out
    }

    private fun remainingYears(today: LocalDate, end: LocalDate): Double =
        maxOf(0L, ChronoUnit.DAYS.between(today, end)) / DAYS_PER_YEAR

    private fun round1(v: Double): Double = Math.round(v * 10.0) / 10.0
    private fun round2(v: Double): Double = Math.round(v * 100.0) / 100.0
}
