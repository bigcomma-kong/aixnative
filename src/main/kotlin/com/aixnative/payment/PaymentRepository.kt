package com.aixnative.payment

import org.springframework.data.jpa.repository.JpaRepository

interface PaymentRepository : JpaRepository<Payment, Long> {
    fun findByOrderId(orderId: String): Payment?
    fun findByTenantIdAndOwnerUserIdOrderByIdDesc(tenantId: Long, ownerUserId: Long): List<Payment>
}
