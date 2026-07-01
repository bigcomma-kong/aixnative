package com.aixnative.payment.web

import java.time.Instant
import com.aixnative.payment.domain.PaymentStatus

/** 판매 팩(프론트 가격표). */
data class CreditPackView(val id: String, val credits: Int, val amountKrw: Int, val label: String)

/** 프론트 결제 SDK 초기화용 — clientKey 만 공개(secretKey 는 절대 노출 안 함). configured=false 면 결제 비활성. */
data class PaymentConfigView(val clientKey: String, val configured: Boolean)

/** 주문 생성 요청 — 팩 id 만 받는다(금액은 서버가 결정). */
data class CreateOrderRequest(val packId: String)

/** 주문 생성 결과 — 토스 위젯에 넘길 값. amount/orderName 은 서버 권위 값. */
data class CreateOrderResponse(
    val orderId: String,
    val orderName: String,
    val amountKrw: Int,
    val customerKey: String,
)

/** 승인 요청 — 토스 successUrl 쿼리(paymentKey/orderId/amount)를 그대로 전달. */
data class ConfirmRequest(val paymentKey: String, val orderId: String, val amount: Int)

/** 승인 결과 — 충전 후 잔액. */
data class ConfirmResponse(val credits: Int, val creditBalance: Int, val orderName: String)

/** 결제 이력 1건. */
data class PaymentHistoryView(
    val orderId: String,
    val packLabel: String,
    val credits: Int,
    val amountKrw: Int,
    val status: PaymentStatus,
    val method: String?,
    val approvedAt: Instant?,
    val createdAt: Instant?,
)
