package com.aixnative.payment.service

import com.aixnative.account.repository.UserRepository
import com.aixnative.billing.service.CreditService
import com.aixnative.billing.domain.Plan
import com.aixnative.common.tenant.TenantContext
import com.aixnative.common.web.BadRequestException
import com.aixnative.common.web.ForbiddenException
import com.aixnative.common.web.NotFoundException
import com.aixnative.common.web.ServiceUnavailableException
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID
import com.aixnative.payment.domain.CreditPack
import com.aixnative.payment.domain.Payment
import com.aixnative.payment.domain.PaymentStatus
import com.aixnative.payment.repository.PaymentRepository
import com.aixnative.payment.web.ConfirmRequest
import com.aixnative.payment.web.ConfirmResponse
import com.aixnative.payment.web.CreateOrderResponse
import com.aixnative.payment.web.CreditPackView
import com.aixnative.payment.web.PaymentConfigView
import com.aixnative.payment.web.PaymentHistoryView

/**
 * 크레딧 충전 결제(토스). 보안 핵심:
 *  1) 금액·크레딧은 서버 팩 기준(클라 값 불신) — 주문 시 고정.
 *  2) 승인은 서버에서 토스 API 로 검증(클라 성공을 신뢰 안 함).
 *  3) orderId 유니크 + 상태 전이(PENDING→CONFIRMED 1회)로 중복충전 차단(멱등).
 */
@Service
class PaymentService(
    private val payments: PaymentRepository,
    private val creditService: CreditService,
    private val users: UserRepository,
    private val toss: TossClient,
    private val props: TossProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun packs(): List<CreditPackView> =
        CreditPack.entries.map { CreditPackView(it.name, it.credits, it.amountKrw, it.label) }

    fun config(): PaymentConfigView = PaymentConfigView(props.clientKey, props.isConfigured())

    /** 주문 생성 — 팩을 골라 PENDING 결제를 만든다. 금액/크레딧은 서버 팩에서 확정. */
    @Transactional
    fun createOrder(packId: String): CreateOrderResponse {
        if (!props.isConfigured()) throw ServiceUnavailableException("결제가 아직 설정되지 않았습니다.")
        val pack = CreditPack.fromId(packId) ?: throw BadRequestException("알 수 없는 상품입니다.")
        val current = TenantContext.require()
        val orderId = "aix_" + UUID.randomUUID().toString().replace("-", "")
        val payment = Payment(
            orderId = orderId,
            packId = pack.name,
            credits = pack.credits,
            amountKrw = pack.amountKrw,
        ).apply {
            tenantId = current.tenantId
            ownerUserId = current.userId
        }
        payments.save(payment)
        return CreateOrderResponse(
            orderId = orderId,
            orderName = pack.label,
            amountKrw = pack.amountKrw,
            customerKey = "u${current.userId}",
        )
    }

    /** 결제 승인 — 토스 검증 통과 시 CONFIRMED + 크레딧 충전(멱등). */
    @Transactional
    fun confirm(req: ConfirmRequest): ConfirmResponse {
        val current = TenantContext.require()
        val order = payments.findByOrderId(req.orderId) ?: throw NotFoundException("주문을 찾을 수 없습니다.")
        if (order.tenantId != current.tenantId || order.ownerUserId != current.userId) {
            throw ForbiddenException("본인 주문이 아닙니다.")
        }
        val label = CreditPack.fromId(order.packId)?.label ?: order.packId

        // 멱등 — 이미 충전된 주문이면 다시 지급하지 않는다.
        if (order.status == PaymentStatus.CONFIRMED) {
            return ConfirmResponse(order.credits, creditService.balance(current.tenantId, current.userId), label)
        }
        if (order.status != PaymentStatus.PENDING) throw BadRequestException("처리할 수 없는 주문 상태입니다.")
        // 위변조 차단 — 클라이언트가 보낸 금액이 서버 주문 금액과 다르면 거부.
        if (req.amount != order.amountKrw) throw BadRequestException("결제 금액이 주문과 일치하지 않습니다.")

        val result = toss.confirm(req.paymentKey, req.orderId, order.amountKrw)
        if (!result.ok) {
            order.status = PaymentStatus.FAILED
            payments.save(order)
            throw BadRequestException(result.message ?: "결제 승인에 실패했습니다.")
        }

        order.status = PaymentStatus.CONFIRMED
        order.paymentKey = req.paymentKey
        order.method = result.method
        order.approvedAt = Instant.now()
        payments.save(order)

        // 원장에 충전 경로를 남긴다(사용 현황·관리자에서 "어떻게 충전됐는지" 표시). 예: "스타터팩 · 카드 · 9,900원"
        val chargeRef = "$label · ${result.method ?: "토스결제"} · ${"%,d".format(order.amountKrw)}원"
        val balance = creditService.grantPurchase(current.tenantId, current.userId, order.credits, chargeRef)
        // 첫 결제 시 플랜을 PAID 로(표시용).
        users.findById(current.userId).ifPresent { if (it.plan != Plan.PAID) it.plan = Plan.PAID }
        log.info("[payment] 충전 완료 orderId={} userId={} +{}크레딧", order.orderId, current.userId, order.credits)
        return ConfirmResponse(order.credits, balance, label)
    }

    @Transactional(readOnly = true)
    fun history(): List<PaymentHistoryView> {
        val current = TenantContext.require()
        return payments.findByTenantIdAndOwnerUserIdOrderByIdDesc(current.tenantId, current.userId).map {
            PaymentHistoryView(
                orderId = it.orderId,
                packLabel = CreditPack.fromId(it.packId)?.label ?: it.packId,
                credits = it.credits,
                amountKrw = it.amountKrw,
                status = it.status,
                method = it.method,
                approvedAt = it.approvedAt,
                createdAt = it.createdAt,
            )
        }
    }
}
