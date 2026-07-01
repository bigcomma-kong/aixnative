package com.aixnative.payment.domain

/**
 * 판매하는 크레딧 팩(서버 권위 — 금액·크레딧은 절대 클라이언트 값을 신뢰하지 않는다).
 * 일회성 충전 모델. 가격 조정은 이 enum 만 고치면 된다.
 */
enum class CreditPack(
    val credits: Int,
    val amountKrw: Int,
    val label: String,
) {
    STARTER(10, 9_900, "스타터 10크레딧"),     // 990원/크레딧
    PRO(50, 44_500, "프로 50크레딧"),          // 890원/크레딧
    BUSINESS(100, 79_000, "비즈니스 100크레딧"), // 790원/크레딧
    ;

    companion object {
        fun fromId(id: String): CreditPack? = entries.firstOrNull { it.name == id }
    }
}
