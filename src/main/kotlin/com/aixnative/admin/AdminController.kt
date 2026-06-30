package com.aixnative.admin

import com.aixnative.account.UserRepository
import com.aixnative.account.UserRole
import com.aixnative.account.UserStatus
import com.aixnative.ai.AiToolRunService
import com.aixnative.billing.CreditService
import com.aixnative.billing.Plan
import com.aixnative.common.tenant.TenantContext
import com.aixnative.common.web.ApiResponse
import com.aixnative.common.web.BadRequestException
import com.aixnative.common.web.NotFoundException
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

data class AdminUser(
    val id: Long,
    val email: String,
    val tenantId: Long,
    val plan: Plan,
    val role: UserRole,
    val status: UserStatus,
    val emailVerified: Boolean,
    val creditBalance: Int,
    val createdAt: Instant?,
)

data class RoleChangeRequest(val role: UserRole)
data class CreditAdjustRequest(val delta: Int)
data class StatusChangeRequest(val status: UserStatus)

data class AdminRun(
    val id: Long,
    val tenantId: Long,
    val ownerUserId: Long,
    val ownerEmail: String?,
    val tool: String,
    val status: String,
    val dealName: String?,
    val createdAt: Instant?,
)

data class AdminCreditEntry(
    val id: Long,
    val tenantId: Long,
    val userId: Long,
    val ownerEmail: String?,
    val delta: Int,
    val reason: String,
    val ref: String?,
    val createdAt: Instant?,
)

data class AdminRunDetail(
    val id: Long,
    val tenantId: Long,
    val ownerUserId: Long,
    val ownerEmail: String?,
    val tool: String,
    val status: String,
    val dealName: String?,
    val createdAt: Instant?,
    val requestJson: String?,
    val resultJson: String?,
)

/**
 * 어드민 전용. SecurityConfig 의 admin 경로 → hasRole("ADMIN") 가드로 보호.
 * 운영 감독 목적상 의도적으로 테넌트 격리를 넘어 전체를 조회/관리한다(일반 사용자 경로와 분리).
 */
@RestController
@RequestMapping("/api/admin")
class AdminController(
    private val users: UserRepository,
    private val creditService: CreditService,
    private val runs: AiToolRunService,
    private val adminUserService: AdminUserService,
) {

    @GetMapping("/users")
    @Transactional(readOnly = true)
    fun listUsers(): ApiResponse<List<AdminUser>> {
        val list = users.findAll().map { u -> u.toAdminUser() }
        return ApiResponse.ok(list)
    }

    /** 관리자 등록/해제. 자기 자신은 변경 불가(셀프 잠금 방지). */
    @PostMapping("/users/{id}/role")
    @Transactional
    fun changeRole(@PathVariable id: Long, @RequestBody req: RoleChangeRequest): ApiResponse<AdminUser> {
        val current = TenantContext.require()
        if (current.userId == id) throw BadRequestException("자기 자신의 권한은 변경할 수 없습니다.")
        val user = users.findById(id).orElseThrow { NotFoundException("사용자를 찾을 수 없습니다.") }
        user.role = req.role
        users.save(user)
        return ApiResponse.ok(user.toAdminUser())
    }

    /** 계정 차단(DISABLED)/해제(ACTIVE). 차단 시 로그인 거부. 자기 자신은 변경 불가. */
    @PostMapping("/users/{id}/status")
    fun changeStatus(@PathVariable id: Long, @RequestBody req: StatusChangeRequest): ApiResponse<AdminUser> {
        val user = adminUserService.setStatus(id, req.status)
        return ApiResponse.ok(user.toAdminUser())
    }

    /** 계정 영구 삭제 + 연관 데이터 정리. 자기 자신은 삭제 불가. */
    @DeleteMapping("/users/{id}")
    fun deleteUser(@PathVariable id: Long): ApiResponse<Map<String, Boolean>> {
        adminUserService.delete(id)
        return ApiResponse.ok(mapOf("deleted" to true))
    }

    /** 크레딧 자유 가감(+/-). 원장에 ADMIN_ADJUST 로 기록(조정한 관리자 식별 포함). */
    @PostMapping("/users/{id}/credits")
    @Transactional
    fun adjustCredits(@PathVariable id: Long, @RequestBody req: CreditAdjustRequest): ApiResponse<AdminUser> {
        if (req.delta == 0) throw BadRequestException("조정 수량은 0이 아니어야 합니다.")
        val current = TenantContext.require()
        val user = users.findById(id).orElseThrow { NotFoundException("사용자를 찾을 수 없습니다.") }
        creditService.adminAdjust(user.tenantId, id, req.delta, ref = "관리자 ${current.email}")
        return ApiResponse.ok(user.toAdminUser())
    }

    /** 전 사용자 크레딧 원장 — 최근순. 충전 경로(ref)·지급/차감 사유 포함(운영 감독). */
    @GetMapping("/credits")
    @Transactional(readOnly = true)
    fun listCredits(): ApiResponse<List<AdminCreditEntry>> {
        val emailById = users.findAll().associate { requireNotNull(it.id) to it.email }
        val list = creditService.recentLedgerAdmin().map { e ->
            AdminCreditEntry(
                id = requireNotNull(e.id),
                tenantId = e.tenantId,
                userId = e.userId,
                ownerEmail = emailById[e.userId],
                delta = e.delta,
                reason = e.reason.name,
                ref = e.ref,
                createdAt = e.createdAt,
            )
        }
        return ApiResponse.ok(list)
    }

    /** 전 테넌트 모든 분석 데이터(요약). */
    @GetMapping("/runs")
    @Transactional(readOnly = true)
    fun listRuns(): ApiResponse<List<AdminRun>> {
        val emailById = users.findAll().associate { requireNotNull(it.id) to it.email }
        val list = runs.listAllAdmin().map { r ->
            val id = requireNotNull(r.id)
            AdminRun(
                id = id,
                tenantId = r.tenantId,
                ownerUserId = r.ownerUserId,
                ownerEmail = emailById[r.ownerUserId],
                tool = r.tool,
                status = r.status.name,
                dealName = r.dealName,
                createdAt = r.createdAt,
            )
        }
        return ApiResponse.ok(list)
    }

    /** 분석 데이터 단건 상세(입력/결과 JSON 포함 — 점검용). */
    @GetMapping("/runs/{id}")
    @Transactional(readOnly = true)
    fun runDetail(@PathVariable id: Long): ApiResponse<AdminRunDetail> {
        val r = runs.getAdmin(id)
        val ownerEmail = users.findById(r.ownerUserId).map { it.email }.orElse(null)
        return ApiResponse.ok(
            AdminRunDetail(
                id = requireNotNull(r.id),
                tenantId = r.tenantId,
                ownerUserId = r.ownerUserId,
                ownerEmail = ownerEmail,
                tool = r.tool,
                status = r.status.name,
                dealName = r.dealName,
                createdAt = r.createdAt,
                requestJson = r.requestJson,
                resultJson = r.resultJson,
            ),
        )
    }

    private fun com.aixnative.account.User.toAdminUser(): AdminUser {
        val id = requireNotNull(this.id)
        return AdminUser(
            id = id,
            email = this.email,
            tenantId = this.tenantId,
            plan = this.plan,
            role = this.role,
            status = this.status,
            emailVerified = this.emailVerified,
            creditBalance = creditService.balance(this.tenantId, id),
            createdAt = this.createdAt,
        )
    }
}
