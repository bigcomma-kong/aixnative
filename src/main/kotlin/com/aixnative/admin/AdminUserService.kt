package com.aixnative.admin

import com.aixnative.account.EmailVerificationTokenRepository
import com.aixnative.account.PasswordResetTokenRepository
import com.aixnative.account.TenantRepository
import com.aixnative.account.User
import com.aixnative.account.UserRepository
import com.aixnative.account.UserStatus
import com.aixnative.ai.AiToolRunRepository
import com.aixnative.billing.CreditLedgerRepository
import com.aixnative.common.tenant.TenantContext
import com.aixnative.common.web.BadRequestException
import com.aixnative.common.web.NotFoundException
import com.aixnative.marketfeed.DealWatchRepository
import com.aixnative.marketfeed.NewsSubscriberRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 관리자 계정 운영 — 차단(DISABLED)/해제(ACTIVE) 및 계정 영구 삭제.
 * 삭제는 1유저=1테넌트 전제에서 사용자의 연관 데이터(원장·런·관심딜·토큰·구독)와 테넌트까지 정리한다.
 * FK 제약이 없어 고아 행이 남지 않도록 명시적으로 지운다.
 */
@Service
class AdminUserService(
    private val users: UserRepository,
    private val tenants: TenantRepository,
    private val ledger: CreditLedgerRepository,
    private val runs: AiToolRunRepository,
    private val watches: DealWatchRepository,
    private val emailTokens: EmailVerificationTokenRepository,
    private val resetTokens: PasswordResetTokenRepository,
    private val subscribers: NewsSubscriberRepository,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** 차단/해제. 자기 자신은 잠금 방지를 위해 변경 불가. */
    @Transactional
    fun setStatus(id: Long, status: UserStatus): User {
        val current = TenantContext.require()
        if (current.userId == id) throw BadRequestException("자기 자신의 계정 상태는 변경할 수 없습니다.")
        val user = users.findById(id).orElseThrow { NotFoundException("사용자를 찾을 수 없습니다.") }
        user.status = status
        return users.save(user)
    }

    /** 계정 영구 삭제 + 연관 데이터 정리. 자기 자신은 삭제 불가. */
    @Transactional
    fun delete(id: Long) {
        val current = TenantContext.require()
        if (current.userId == id) throw BadRequestException("자기 자신의 계정은 삭제할 수 없습니다.")
        val user = users.findById(id).orElseThrow { NotFoundException("사용자를 찾을 수 없습니다.") }
        val tenantId = user.tenantId

        // 연관 데이터 정리(FK 없음 → 고아 방지).
        ledger.deleteByUserId(id)
        runs.deleteByOwnerUserId(id)
        watches.deleteByOwnerUserId(id)
        emailTokens.deleteByUserId(id)
        resetTokens.deleteByUserId(id)
        subscribers.deleteByEmail(user.email)
        users.deleteById(id)

        // 1유저=1테넌트 — 이 테넌트를 쓰는 다른 사용자가 없으면 테넌트도 제거.
        if (users.findAll().none { it.tenantId == tenantId }) {
            tenants.deleteById(tenantId)
        }
        log.info("[admin] 계정 삭제 완료: userId={}, email={}", id, user.email)
    }
}
