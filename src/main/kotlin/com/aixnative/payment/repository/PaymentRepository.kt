package com.aixnative.payment.repository

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import com.aixnative.payment.domain.Payment
import com.aixnative.payment.domain.PaymentStatus

interface PaymentRepository : JpaRepository<Payment, Long> {
    fun findByOrderId(orderId: String): Payment?
    fun findByTenantIdAndOwnerUserIdOrderByIdDesc(tenantId: Long, ownerUserId: Long): List<Payment>

    /** 관리자 통계 — 특정 상태 결제 건수. */
    fun countByStatus(status: PaymentStatus): Long

    /** 관리자 통계 — 특정 상태 결제 총액(원). */
    @Query("select coalesce(sum(p.amountKrw), 0) from Payment p where p.status = :status")
    fun sumAmountByStatus(@Param("status") status: PaymentStatus): Long
}
