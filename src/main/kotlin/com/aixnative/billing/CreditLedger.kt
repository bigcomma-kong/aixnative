package com.aixnative.billing

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EntityListeners
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.annotation.CreatedDate
import org.springframework.data.jpa.domain.support.AuditingEntityListener
import java.time.Instant

/**
 * Append-only credit ledger entry. Balance = SUM(delta) for a (tenant, user).
 * Never updated or deleted — corrections are new compensating rows.
 */
@Entity
@Table(name = "credit_ledger")
@EntityListeners(AuditingEntityListener::class)
class CreditLedger(
    @Column(name = "tenant_id", nullable = false)
    var tenantId: Long,

    @Column(name = "user_id", nullable = false)
    var userId: Long,

    @Column(nullable = false)
    var delta: Int,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    var reason: CreditReason,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant? = null
}
