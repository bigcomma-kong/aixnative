package com.aixnative.property.domain

import com.aixnative.common.tenant.BaseTenantEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import java.time.LocalDate

/**
 * 임대차 계약 1건 - [Building] 에 속한 임차인 한 명의 계약. 테넌트 스코프([BaseTenantEntity]).
 * 계약서에서 AI 추출([com.aixnative.property.domain.LeaseExtract]) 후 사용자가 검토·저장한다.
 * 금액 단위는 만원, 면적은 평, 비율은 %. 상태(진행/예정/만료)는 저장하지 않고 날짜로 파생 계산한다.
 */
@Entity
@Table(name = "lease")
class Lease(
    @Column(name = "building_id", nullable = false)
    var buildingId: Long,

    @Column(name = "tenant_name", nullable = false, length = 200)
    var tenantName: String,

    /** 층/호(예: '10F', '지하1층'). */
    @Column(name = "unit_label", length = 100)
    var unitLabel: String? = null,

    /** 임대면적(평). */
    @Column(name = "area_pyeong")
    var areaPyeong: Double? = null,

    /** 월 임대료(만원). */
    @Column(name = "monthly_rent_manwon")
    var monthlyRentManwon: Double? = null,

    /** 보증금(만원). */
    @Column(name = "deposit_manwon")
    var depositManwon: Double? = null,

    /** 월 관리비(만원). */
    @Column(name = "mgmt_fee_manwon")
    var mgmtFeeManwon: Double? = null,

    @Column(name = "lease_start_date")
    var leaseStartDate: LocalDate? = null,

    @Column(name = "lease_end_date")
    var leaseEndDate: LocalDate? = null,

    /** 렌트프리(개월). */
    @Column(name = "rent_free_months")
    var rentFreeMonths: Int? = null,

    /** 임대료 인상률(%). */
    @Column(name = "escalation_pct")
    var escalationPct: Double? = null,

    /** 다음 인상 예정일. */
    @Column(name = "next_escalation_date")
    var nextEscalationDate: LocalDate? = null,

    /** 원문 계약서 텍스트(추출 근거). */
    @Column(name = "source_text", length = 8000)
    var sourceText: String? = null,

    @Column(length = 1000)
    var notes: String? = null,
) : BaseTenantEntity()

/** 임대차 상태 - 저장하지 않고 [leaseEndDate] 와 기준일로 파생. */
enum class LeaseStatus { ACTIVE, UPCOMING, EXPIRED, UNKNOWN }
