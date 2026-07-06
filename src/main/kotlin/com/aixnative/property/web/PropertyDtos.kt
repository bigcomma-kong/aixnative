package com.aixnative.property.web

import com.aixnative.property.domain.LeaseExtract
import com.fasterxml.jackson.databind.JsonNode
import jakarta.validation.constraints.NotBlank
import java.time.Instant
import java.time.LocalDate

// ── 건물 ──────────────────────────────────────────────────────────────────

data class BuildingRequest(
    @field:NotBlank(message = "건물명을 입력하세요.")
    val name: String? = null,
    val address: String? = null,
    val assetType: String? = null,
    val gfaPyeong: Double? = null,
    val notes: String? = null,
)

data class BuildingView(
    val id: Long,
    val name: String,
    val address: String?,
    val assetType: String?,
    val gfaPyeong: Double?,
    val notes: String?,
    val leaseCount: Int,
    val createdAt: Instant?,
)

// ── 임대차 ────────────────────────────────────────────────────────────────

data class LeaseRequest(
    val buildingId: Long? = null,
    @field:NotBlank(message = "임차인명을 입력하세요.")
    val tenantName: String? = null,
    val unitLabel: String? = null,
    val areaPyeong: Double? = null,
    val monthlyRentManwon: Double? = null,
    val depositManwon: Double? = null,
    val mgmtFeeManwon: Double? = null,
    val leaseStartDate: LocalDate? = null,
    val leaseEndDate: LocalDate? = null,
    val rentFreeMonths: Int? = null,
    val escalationPct: Double? = null,
    val nextEscalationDate: LocalDate? = null,
    val sourceText: String? = null,
    val notes: String? = null,
)

data class LeaseView(
    val id: Long,
    val buildingId: Long,
    val tenantName: String,
    val unitLabel: String?,
    val areaPyeong: Double?,
    val monthlyRentManwon: Double?,
    val depositManwon: Double?,
    val mgmtFeeManwon: Double?,
    val leaseStartDate: LocalDate?,
    val leaseEndDate: LocalDate?,
    val rentFreeMonths: Int?,
    val escalationPct: Double?,
    val nextEscalationDate: LocalDate?,
    val notes: String?,
    /** ACTIVE | UPCOMING | EXPIRED | UNKNOWN (날짜 파생). */
    val status: String,
    /** 만기까지 남은 일수(음수면 이미 만료). 만기일 없으면 null. */
    val daysToExpiry: Long?,
)

// ── 계약서 AI 추출 ─────────────────────────────────────────────────────────

data class LeaseExtractRequest(
    @field:NotBlank(message = "임대차 계약서 텍스트를 입력하세요.")
    val text: String? = null,
)

data class LeaseExtractResponse(
    val extract: LeaseExtract? = null,
    val raw: String? = null,
    val provider: String,
    val creditBalance: Int,
)

// ── 렌트롤(집계) ───────────────────────────────────────────────────────────

/** 리스크 플래그 - 언더라이팅 sections flags 와 동일 shape(label/severity). */
data class RiskFlag(val label: String, val severity: String)

data class RentRollResponse(
    val buildingId: Long,
    val buildingName: String,
    val leaseCount: Int,
    val totalMonthlyRentManwon: Double,
    val totalDepositManwon: Double,
    val totalMgmtFeeManwon: Double,
    val annualRentManwon: Double,
    /** 가중평균 잔여 임대기간(년) - 월임대료 가중. 만기일 있는 계약만. */
    val waltYears: Double?,
    /** 최대 임차인명 + 월임대료 비중(%). */
    val topTenantName: String?,
    val topTenantPct: Double?,
    /** 평당 월임대료(만원/평) - 면적 있는 계약 가중평균. */
    val avgRentPerPyeongManwon: Double?,
    val leases: List<LeaseView>,
    val flags: List<RiskFlag>,
)

// ── 다가오는 일정(캘린더) ────────────────────────────────────────────────

data class CalendarEventView(
    val leaseId: Long,
    val buildingId: Long,
    val tenantName: String,
    /** EXPIRY | ESCALATION | RENT_FREE_END. */
    val eventType: String,
    val dueDate: LocalDate,
    /** 오늘 기준 남은 일수(음수면 지남). */
    val daysUntil: Long,
    val title: String,
)

data class CalendarResponse(val events: List<CalendarEventView>)

// ── AM 제출 보고서(AI) ──────────────────────────────────────────────────

data class AmReportRequest(val buildingId: Long? = null)

data class AmReportResponse(
    val buildingId: Long,
    val buildingName: String,
    val analysis: JsonNode? = null,
    val analysisRaw: String? = null,
    val provider: String,
    val creditBalance: Int,
    val generatedAt: Instant?,
    val disclaimer: String,
)
