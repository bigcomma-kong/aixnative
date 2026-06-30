package com.aixnative.payment

import com.aixnative.common.web.ApiResponse
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * 크레딧 충전 결제(토스). 모든 엔드포인트는 인증 필요(SecurityConfig).
 * 금액/크레딧은 서버 팩이 결정하고, 승인은 서버에서 토스 API 로 검증한다(클라 신뢰 안 함).
 */
@RestController
@RequestMapping("/api/payments")
class PaymentController(
    private val service: PaymentService,
) {
    /** 프론트 결제 SDK 초기화용 — clientKey + 결제 활성 여부. */
    @GetMapping("/config")
    fun config(): ApiResponse<PaymentConfigView> = ApiResponse.ok(service.config())

    /** 판매 팩(가격표). */
    @GetMapping("/packs")
    fun packs(): ApiResponse<List<CreditPackView>> = ApiResponse.ok(service.packs())

    /** 주문 생성 — 팩 선택 → 결제창에 넘길 orderId/금액 반환. */
    @PostMapping("/order")
    fun order(@RequestBody req: CreateOrderRequest): ApiResponse<CreateOrderResponse> =
        ApiResponse.ok(service.createOrder(req.packId))

    /** 결제 승인 — 토스 successUrl 콜백(paymentKey/orderId/amount) 서버 검증 후 충전. */
    @PostMapping("/confirm")
    fun confirm(@RequestBody req: ConfirmRequest): ApiResponse<ConfirmResponse> =
        ApiResponse.ok(service.confirm(req))

    /** 내 결제 이력. */
    @GetMapping("/history")
    fun history(): ApiResponse<List<PaymentHistoryView>> = ApiResponse.ok(service.history())
}
