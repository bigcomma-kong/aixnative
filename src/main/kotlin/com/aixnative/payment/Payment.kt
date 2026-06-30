package com.aixnative.payment

import com.aixnative.common.tenant.BaseTenantEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Table
import java.time.Instant

enum class PaymentStatus { PENDING, CONFIRMED, FAILED, CANCELED }

/**
 * 결제 주문/승인 1건. 테넌트 스코프([BaseTenantEntity]).
 * 주문 생성 시 PENDING 으로 저장(금액·크레딧은 서버 팩 기준 — 위변조 차단), 승인 검증 성공 시 CONFIRMED + 크레딧 지급.
 */
@Entity
@Table(name = "payment")
class Payment(
    /** 우리가 생성한 주문번호(토스 orderId). 멱등·중복충전 방지의 유니크 키. */
    @Column(name = "order_id", nullable = false, unique = true, length = 64)
    var orderId: String,

    @Column(name = "pack_id", nullable = false, length = 30)
    var packId: String,

    @Column(nullable = false)
    var credits: Int,

    @Column(name = "amount_krw", nullable = false)
    var amountKrw: Int,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var status: PaymentStatus = PaymentStatus.PENDING,

    /** 토스 결제키(승인 후 기록). */
    @Column(name = "payment_key", length = 200)
    var paymentKey: String? = null,

    @Column(length = 30)
    var method: String? = null,

    @Column(name = "approved_at")
    var approvedAt: Instant? = null,
) : BaseTenantEntity()
