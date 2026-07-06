package com.aixnative.property.web

import com.aixnative.billing.service.RequiresCredit
import com.aixnative.common.web.ApiResponse
import com.aixnative.property.service.PropertyService
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * 자산관리(PM) - 임대차 관리. 모두 인증 필요(JWT), 모든 조회/저장은 현재 테넌트로 스코프(IDOR 차단).
 * 표·차트·캘린더·리스크 조회는 무료(코드 계산), 계약서 추출·AM 보고서만 크레딧(AI).
 */
@RestController
@RequestMapping("/api/property")
class PropertyController(private val service: PropertyService) {

    // ── 건물 ──
    @GetMapping("/buildings")
    fun listBuildings(): ApiResponse<List<BuildingView>> = ApiResponse.ok(service.listBuildings())

    @PostMapping("/buildings")
    fun createBuilding(@Valid @RequestBody req: BuildingRequest): ApiResponse<BuildingView> =
        ApiResponse.ok(service.createBuilding(req))

    @PutMapping("/buildings/{id}")
    fun updateBuilding(@PathVariable id: Long, @Valid @RequestBody req: BuildingRequest): ApiResponse<BuildingView> =
        ApiResponse.ok(service.updateBuilding(id, req))

    @DeleteMapping("/buildings/{id}")
    fun deleteBuilding(@PathVariable id: Long): ApiResponse<Map<String, Boolean>> {
        service.deleteBuilding(id)
        return ApiResponse.ok(mapOf("deleted" to true))
    }

    // ── 임대차 ──
    @GetMapping("/leases")
    fun listLeases(@RequestParam buildingId: Long): ApiResponse<List<LeaseView>> =
        ApiResponse.ok(service.listLeases(buildingId))

    @PostMapping("/leases")
    fun createLease(@Valid @RequestBody req: LeaseRequest): ApiResponse<LeaseView> =
        ApiResponse.ok(service.saveLease(null, req))

    @PutMapping("/leases/{id}")
    fun updateLease(@PathVariable id: Long, @Valid @RequestBody req: LeaseRequest): ApiResponse<LeaseView> =
        ApiResponse.ok(service.saveLease(id, req))

    @DeleteMapping("/leases/{id}")
    fun deleteLease(@PathVariable id: Long): ApiResponse<Map<String, Boolean>> {
        service.deleteLease(id)
        return ApiResponse.ok(mapOf("deleted" to true))
    }

    // ── 계약서 AI 추출(크레딧) ──
    @RequiresCredit
    @PostMapping("/extract-lease")
    fun extractLease(@Valid @RequestBody req: LeaseExtractRequest): ApiResponse<LeaseExtractResponse> =
        ApiResponse.ok(service.extractLease(req))

    // ── 렌트롤·캘린더(무료) ──
    @GetMapping("/rent-roll")
    fun rentRoll(@RequestParam buildingId: Long): ApiResponse<RentRollResponse> =
        ApiResponse.ok(service.rentRoll(buildingId))

    @GetMapping("/calendar")
    fun calendar(@RequestParam(required = false) buildingId: Long?): ApiResponse<CalendarResponse> =
        ApiResponse.ok(service.calendar(buildingId))

    // ── AM 제출 보고서(크레딧) ──
    @RequiresCredit
    @PostMapping("/am-report")
    fun amReport(@RequestBody req: AmReportRequest): ApiResponse<AmReportResponse> {
        val buildingId = req.buildingId ?: throw com.aixnative.common.web.BadRequestException("건물을 선택하세요.")
        return ApiResponse.ok(service.amReport(buildingId))
    }
}
