package com.aixnative.property.service

import com.aixnative.ai.service.AiServiceManager
import com.aixnative.billing.domain.ToolPricing
import com.aixnative.billing.service.CreditGate
import com.aixnative.billing.service.CreditService
import com.aixnative.common.Disclaimer
import com.aixnative.common.tenant.TenantContext
import com.aixnative.common.web.BadRequestException
import com.aixnative.common.web.InsufficientCreditsException
import com.aixnative.common.web.NotFoundException
import com.aixnative.common.web.ServiceUnavailableException
import com.aixnative.property.domain.Building
import com.aixnative.property.domain.Lease
import com.aixnative.property.domain.LeaseAnalytics
import com.aixnative.property.domain.LeaseEventType
import com.aixnative.property.domain.LeaseExtract
import com.aixnative.property.repository.BuildingRepository
import com.aixnative.property.repository.LeaseRepository
import com.aixnative.property.web.AmReportResponse
import com.aixnative.property.web.BuildingRequest
import com.aixnative.property.web.BuildingView
import com.aixnative.property.web.CalendarEventView
import com.aixnative.property.web.CalendarResponse
import com.aixnative.property.web.LeaseExtractRequest
import com.aixnative.property.web.LeaseExtractResponse
import com.aixnative.property.web.LeaseRequest
import com.aixnative.property.web.LeaseView
import com.aixnative.property.web.RentRollResponse
import com.aixnative.property.web.RiskFlag
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import java.time.ZoneId

/**
 * 자산관리(PM) - 건물·임대차 저장형 관리. CRUD·렌트롤 집계·다가오는 일정(캘린더)·리스크(결정론)·
 * 계약서 AI 추출·AM 제출 보고서(AI). 모든 조회/저장은 현재 테넌트로 스코프(IDOR 차단).
 * 표·차트·캘린더·리스크는 무료(코드 계산), 계약서 추출·AM 보고서만 크레딧(AI 1회 호출).
 */
@Service
class PropertyService(
    private val buildingRepository: BuildingRepository,
    private val leaseRepository: LeaseRepository,
    private val aiServiceManager: AiServiceManager,
    private val creditGate: CreditGate,
    private val creditService: CreditService,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    // ── 건물 CRUD ──────────────────────────────────────────────────────────

    fun listBuildings(): List<BuildingView> {
        val cur = TenantContext.require()
        val buildings = buildingRepository.findByTenantIdAndOwnerUserIdOrderByIdDesc(cur.tenantId, cur.userId)
        return buildings.map { b ->
            val leases = leaseRepository.findByTenantIdAndOwnerUserIdAndBuildingIdOrderByIdDesc(
                cur.tenantId, cur.userId, requireNotNull(b.id),
            )
            b.toView(leases.size)
        }
    }

    @Transactional
    fun createBuilding(req: BuildingRequest): BuildingView {
        val cur = TenantContext.require()
        val name = req.name?.trim()?.takeIf { it.isNotBlank() }
            ?: throw BadRequestException("건물명을 입력하세요.")
        val entity = Building(
            name = name,
            address = req.address?.trim()?.takeIf { it.isNotBlank() },
            assetType = req.assetType?.trim()?.takeIf { it.isNotBlank() },
            gfaPyeong = req.gfaPyeong,
            notes = req.notes?.trim()?.takeIf { it.isNotBlank() },
        ).apply { tenantId = cur.tenantId; ownerUserId = cur.userId }
        return buildingRepository.save(entity).toView(0)
    }

    @Transactional
    fun updateBuilding(id: Long, req: BuildingRequest): BuildingView {
        val cur = TenantContext.require()
        val b = buildingRepository.findByIdAndTenantId(id, cur.tenantId)
            ?: throw NotFoundException("건물을 찾을 수 없습니다.")
        req.name?.trim()?.takeIf { it.isNotBlank() }?.let { b.name = it }
        b.address = req.address?.trim()?.takeIf { it.isNotBlank() }
        b.assetType = req.assetType?.trim()?.takeIf { it.isNotBlank() }
        b.gfaPyeong = req.gfaPyeong
        b.notes = req.notes?.trim()?.takeIf { it.isNotBlank() }
        val leases = leaseRepository.findByTenantIdAndOwnerUserIdAndBuildingIdOrderByIdDesc(cur.tenantId, cur.userId, id)
        return buildingRepository.save(b).toView(leases.size)
    }

    @Transactional
    fun deleteBuilding(id: Long) {
        val cur = TenantContext.require()
        val b = buildingRepository.findByIdAndTenantId(id, cur.tenantId)
            ?: throw NotFoundException("건물을 찾을 수 없습니다.")
        leaseRepository.deleteByBuildingId(requireNotNull(b.id))
        buildingRepository.delete(b)
    }

    // ── 임대차 CRUD ────────────────────────────────────────────────────────

    fun listLeases(buildingId: Long): List<LeaseView> {
        val cur = TenantContext.require()
        requireBuilding(buildingId) // 소유·존재 검증(IDOR 차단)
        val today = today()
        return leaseRepository
            .findByTenantIdAndOwnerUserIdAndBuildingIdOrderByIdDesc(cur.tenantId, cur.userId, buildingId)
            .map { it.toView(today) }
    }

    @Transactional
    fun saveLease(id: Long?, req: LeaseRequest): LeaseView {
        val cur = TenantContext.require()
        val tenantName = req.tenantName?.trim()?.takeIf { it.isNotBlank() }
            ?: throw BadRequestException("임차인명을 입력하세요.")
        val buildingId = req.buildingId ?: throw BadRequestException("건물을 선택하세요.")
        requireBuilding(buildingId) // 대상 건물이 내 것인지 검증

        val entity = if (id != null) {
            leaseRepository.findByIdAndTenantId(id, cur.tenantId)
                ?: throw NotFoundException("임대차를 찾을 수 없습니다.")
        } else {
            Lease(buildingId = buildingId, tenantName = tenantName)
                .apply { tenantId = cur.tenantId; ownerUserId = cur.userId }
        }
        entity.apply {
            this.buildingId = buildingId
            this.tenantName = tenantName
            unitLabel = req.unitLabel?.trim()?.takeIf { it.isNotBlank() }
            areaPyeong = req.areaPyeong
            monthlyRentManwon = req.monthlyRentManwon
            depositManwon = req.depositManwon
            mgmtFeeManwon = req.mgmtFeeManwon
            leaseStartDate = req.leaseStartDate
            leaseEndDate = req.leaseEndDate
            rentFreeMonths = req.rentFreeMonths
            escalationPct = req.escalationPct
            nextEscalationDate = req.nextEscalationDate
            sourceText = req.sourceText?.take(MAX_SOURCE_CHARS)
            notes = req.notes?.trim()?.takeIf { it.isNotBlank() }
        }
        return leaseRepository.save(entity).toView(today())
    }

    @Transactional
    fun deleteLease(id: Long) {
        val cur = TenantContext.require()
        val lease = leaseRepository.findByIdAndTenantId(id, cur.tenantId)
            ?: throw NotFoundException("임대차를 찾을 수 없습니다.")
        leaseRepository.delete(lease)
    }

    // ── 계약서 AI 추출(크레딧) ──────────────────────────────────────────────

    /** 계약서 텍스트 → 구조화 임대차 필드. 성공 시에만 1 크레딧 차감(실패는 무과금·503). */
    fun extractLease(req: LeaseExtractRequest): LeaseExtractResponse {
        if (!aiServiceManager.hasConfiguredProvider()) {
            throw ServiceUnavailableException("AI 분석 서비스가 설정되지 않았습니다(API 키 미설정).")
        }
        val text = req.text?.takeIf { it.isNotBlank() }
            ?: throw BadRequestException("임대차 계약서 텍스트를 입력하세요.")
        val ai = try {
            creditGate.charge(ToolPricing.costOf(LEASE_EXTRACT)) {
                aiServiceManager.complete(PropertyPrompts.leaseExtract(text.take(MAX_EXTRACT_CHARS)))
            }
        } catch (e: InsufficientCreditsException) {
            throw e
        } catch (e: Exception) {
            log.error("[Property] 계약서 추출 실패", e)
            throw ServiceUnavailableException("계약서 추출 호출에 실패했습니다: ${rootMessage(e)}")
        }
        val parsed = tryParseJson(ai.text)
        val extract = parsed?.let { runCatching { objectMapper.treeToValue(it, LeaseExtract::class.java) }.getOrNull() }
        val cur = TenantContext.require()
        return LeaseExtractResponse(
            extract = extract,
            raw = if (extract == null) ai.text else null,
            provider = ai.provider,
            creditBalance = creditService.balance(cur.tenantId, cur.userId),
        )
    }

    // ── 렌트롤(무료 집계) ──────────────────────────────────────────────────

    fun rentRoll(buildingId: Long): RentRollResponse {
        val cur = TenantContext.require()
        val building = requireBuilding(buildingId)
        val today = today()
        val leases = leaseRepository
            .findByTenantIdAndOwnerUserIdAndBuildingIdOrderByIdDesc(cur.tenantId, cur.userId, buildingId)
        return buildRentRoll(building, leases, today)
    }

    private fun buildRentRoll(building: Building, leases: List<Lease>, today: LocalDate): RentRollResponse {
        val agg = LeaseAnalytics.aggregate(leases, today)
        return RentRollResponse(
            buildingId = requireNotNull(building.id),
            buildingName = building.name,
            leaseCount = leases.size,
            totalMonthlyRentManwon = agg.totalMonthlyRent,
            totalDepositManwon = agg.totalDeposit,
            totalMgmtFeeManwon = agg.totalMgmtFee,
            annualRentManwon = agg.annualRent,
            waltYears = agg.waltYears,
            topTenantName = agg.topTenantName,
            topTenantPct = agg.topTenantPct,
            avgRentPerPyeongManwon = agg.avgRentPerPyeong,
            leases = leases.map { it.toView(today) },
            flags = LeaseAnalytics.riskFlags(leases, today, agg).map { RiskFlag(it.label, it.severity) },
        )
    }

    // ── 다가오는 일정(무료 캘린더) ─────────────────────────────────────────

    /** 다가오는 임대 일정(만기·인상·렌트프리 종료). buildingId 지정 시 해당 건물만. */
    fun calendar(buildingId: Long?): CalendarResponse {
        val cur = TenantContext.require()
        val today = today()
        val leases = if (buildingId != null) {
            requireBuilding(buildingId)
            leaseRepository.findByTenantIdAndOwnerUserIdAndBuildingIdOrderByIdDesc(cur.tenantId, cur.userId, buildingId)
        } else {
            leaseRepository.findByTenantIdAndOwnerUserId(cur.tenantId, cur.userId)
        }
        return CalendarResponse(calendarEvents(leases, today))
    }

    /** 임대차들의 일정 이벤트를 표시 윈도(과거 [RECENT_WINDOW_DAYS]일 ~ 향후 [HORIZON_DAYS]일)로 필터·정렬해 뷰로. */
    private fun calendarEvents(leases: List<Lease>, today: LocalDate): List<CalendarEventView> =
        leases.flatMap { LeaseAnalytics.events(it, today) }
            .filter { it.daysUntil >= -RECENT_WINDOW_DAYS && it.daysUntil <= HORIZON_DAYS }
            .sortedBy { it.dueDate }
            .map { it.toView() }

    private fun LeaseAnalytics.Event.toView() = CalendarEventView(
        leaseId = leaseId,
        buildingId = buildingId,
        tenantName = tenantName,
        eventType = type.name,
        dueDate = dueDate,
        daysUntil = daysUntil,
        title = "${EVENT_TITLE[type] ?: type.name} · $tenantName",
    )

    // ── AM 제출 보고서(크레딧) ──────────────────────────────────────────────

    fun amReport(buildingId: Long): AmReportResponse {
        if (!aiServiceManager.hasConfiguredProvider()) {
            throw ServiceUnavailableException("AI 분석 서비스가 설정되지 않았습니다(API 키 미설정).")
        }
        val cur = TenantContext.require()
        val building = requireBuilding(buildingId)
        val today = today()
        val leases = leaseRepository
            .findByTenantIdAndOwnerUserIdAndBuildingIdOrderByIdDesc(cur.tenantId, cur.userId, buildingId)
        if (leases.isEmpty()) throw BadRequestException("보고서를 만들 임대차가 없습니다. 계약을 먼저 추가하세요.")

        val rentRoll = buildRentRoll(building, leases, today)
        val events = calendarEvents(leases, today)
        val dataText = buildAmReportData(building, rentRoll, events, today)

        val ai = try {
            creditGate.charge(ToolPricing.costOf(PM_AM_REPORT)) {
                aiServiceManager.complete(PropertyPrompts.amReport(dataText, building.name))
            }
        } catch (e: InsufficientCreditsException) {
            throw e
        } catch (e: Exception) {
            log.error("[Property] AM 보고서 생성 실패 (buildingId={})", buildingId, e)
            throw ServiceUnavailableException("AM 보고서 생성에 실패했습니다: ${rootMessage(e)}")
        }
        val parsed = tryParseJson(ai.text)
        return AmReportResponse(
            buildingId = requireNotNull(building.id),
            buildingName = building.name,
            analysis = parsed,
            analysisRaw = if (parsed == null) ai.text else null,
            provider = ai.provider,
            creditBalance = creditService.balance(cur.tenantId, cur.userId),
            generatedAt = java.time.Instant.now(),
            disclaimer = Disclaimer.TEXT,
        )
    }

    /** AM 보고서 프롬프트용 <DATA> - 건물·렌트롤·일정·리스크를 확정 수치로 조립(AI 는 서술만). */
    private fun buildAmReportData(
        building: Building,
        rr: RentRollResponse,
        events: List<CalendarEventView>,
        today: LocalDate,
    ): String {
        val sb = StringBuilder()
        sb.append("[건물]\n")
        sb.append("이름: ${building.name}\n")
        building.address?.let { sb.append("주소: $it\n") }
        building.assetType?.let { sb.append("자산유형: $it\n") }
        building.gfaPyeong?.let { sb.append("연면적: ${it}평\n") }
        sb.append("기준일: $today\n\n")

        sb.append("[렌트롤 집계 - 코드 확정]\n")
        sb.append("임차 건수: ${rr.leaseCount}\n")
        sb.append("총 월임대료: ${rr.totalMonthlyRentManwon}만원 (연 환산 ${rr.annualRentManwon}만원)\n")
        sb.append("총 보증금: ${rr.totalDepositManwon}만원, 총 월관리비: ${rr.totalMgmtFeeManwon}만원\n")
        rr.waltYears?.let { sb.append("WALT(가중평균 잔여기간): ${it}년\n") }
        if (rr.topTenantName != null && rr.topTenantPct != null) {
            sb.append("최대 임차인: ${rr.topTenantName} (월임대료 비중 ${rr.topTenantPct}%)\n")
        }
        rr.avgRentPerPyeongManwon?.let { sb.append("평당 월임대료: ${it}만원/평\n") }
        sb.append("\n")

        sb.append("[임차인별 명세 - 코드 확정]\n")
        for (l in rr.leases) {
            sb.append("- ${l.tenantName}")
            l.unitLabel?.let { sb.append(" ($it)") }
            l.areaPyeong?.let { sb.append(" · ${it}평") }
            l.monthlyRentManwon?.let { sb.append(" · 월 ${it}만원") }
            l.depositManwon?.let { sb.append(" · 보증금 ${it}만원") }
            l.leaseStartDate?.let { sb.append(" · 시작 $it") }
            l.leaseEndDate?.let { sb.append(" · 만기 $it") }
            sb.append(" · 상태 ${l.status}")
            l.daysToExpiry?.let { sb.append(" (만기까지 ${it}일)") }
            sb.append("\n")
        }
        sb.append("\n")

        if (events.isNotEmpty()) {
            sb.append("[다가오는 일정 - 코드 확정]\n")
            for (e in events) {
                sb.append("- ${e.dueDate} (D${if (e.daysUntil >= 0) "-${e.daysUntil}" else "+${-e.daysUntil}"}) ${e.title}\n")
            }
            sb.append("\n")
        }

        if (rr.flags.isNotEmpty()) {
            sb.append("[식별된 리스크 - 코드 확정]\n")
            for (f in rr.flags) sb.append("- [${f.severity}] ${f.label}\n")
        }
        return sb.toString()
    }

    // ── 헬퍼 ───────────────────────────────────────────────────────────────

    private fun requireBuilding(buildingId: Long): Building {
        val cur = TenantContext.require()
        return buildingRepository.findByIdAndTenantId(buildingId, cur.tenantId)
            ?.takeIf { it.ownerUserId == cur.userId }
            ?: throw NotFoundException("건물을 찾을 수 없습니다.")
    }

    private fun Building.toView(leaseCount: Int) = BuildingView(
        id = requireNotNull(id),
        name = name,
        address = address,
        assetType = assetType,
        gfaPyeong = gfaPyeong,
        notes = notes,
        leaseCount = leaseCount,
        createdAt = createdAt,
    )

    private fun Lease.toView(today: LocalDate) = LeaseView(
        id = requireNotNull(id),
        buildingId = buildingId,
        tenantName = tenantName,
        unitLabel = unitLabel,
        areaPyeong = areaPyeong,
        monthlyRentManwon = monthlyRentManwon,
        depositManwon = depositManwon,
        mgmtFeeManwon = mgmtFeeManwon,
        leaseStartDate = leaseStartDate,
        leaseEndDate = leaseEndDate,
        rentFreeMonths = rentFreeMonths,
        escalationPct = escalationPct,
        nextEscalationDate = nextEscalationDate,
        notes = notes,
        status = LeaseAnalytics.status(this, today).name,
        daysToExpiry = LeaseAnalytics.daysToExpiry(today, this),
    )

    private fun today(): LocalDate = LocalDate.now(SEOUL)

    /** 모델이 코드펜스/잡설을 섞어도 첫 JSON 객체를 추출해 파싱. 실패하면 null. */
    private fun tryParseJson(text: String): JsonNode? {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return runCatching { objectMapper.readTree(text.substring(start, end + 1)) }
            .onFailure { log.warn("AI 응답 JSON 파싱 실패: {}", it.message) }
            .getOrNull()
    }

    private fun rootMessage(e: Throwable): String {
        var cur: Throwable? = e
        var msg = e.message
        while (cur?.cause != null && cur.cause !== cur) {
            cur = cur.cause
            cur?.message?.takeIf { it.isNotBlank() }?.let { msg = it }
        }
        return (msg ?: e.javaClass.simpleName).take(300)
    }

    private companion object {
        val SEOUL: ZoneId = ZoneId.of("Asia/Seoul")
        const val MAX_EXTRACT_CHARS = 8000
        const val MAX_SOURCE_CHARS = 8000
        const val HORIZON_DAYS = 365L
        const val RECENT_WINDOW_DAYS = 30L
        const val LEASE_EXTRACT = "LEASE_EXTRACT"
        const val PM_AM_REPORT = "PM_AM_REPORT"

        /** 일정 이벤트 유형 → 표시 제목. */
        val EVENT_TITLE: Map<LeaseEventType, String> = mapOf(
            LeaseEventType.EXPIRY to "만기",
            LeaseEventType.ESCALATION to "임대료 인상",
            LeaseEventType.RENT_FREE_END to "렌트프리 종료",
        )
    }
}
