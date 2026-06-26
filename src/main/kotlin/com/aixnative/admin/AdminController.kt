package com.aixnative.admin

import com.aixnative.account.UserRepository
import com.aixnative.billing.CreditService
import com.aixnative.billing.Plan
import com.aixnative.account.UserRole
import com.aixnative.common.web.ApiResponse
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

data class AdminUser(
    val id: Long,
    val email: String,
    val tenantId: Long,
    val plan: Plan,
    val role: UserRole,
    val creditBalance: Int,
    val createdAt: Instant?,
)

/**
 * 어드민 전용. SecurityConfig 의 admin 경로 → hasRole("ADMIN") 가드로 보호.
 * 운영 감독 목적상 의도적으로 테넌트 격리를 넘어 전체를 조회한다(일반 사용자 경로와 분리).
 */
@RestController
@RequestMapping("/api/admin")
class AdminController(
    private val users: UserRepository,
    private val creditService: CreditService,
) {

    @GetMapping("/users")
    @Transactional(readOnly = true)
    fun listUsers(): ApiResponse<List<AdminUser>> {
        val list = users.findAll().map { u ->
            val id = requireNotNull(u.id)
            AdminUser(
                id = id,
                email = u.email,
                tenantId = u.tenantId,
                plan = u.plan,
                role = u.role,
                creditBalance = creditService.balance(u.tenantId, id),
                createdAt = u.createdAt,
            )
        }
        return ApiResponse.ok(list)
    }
}
