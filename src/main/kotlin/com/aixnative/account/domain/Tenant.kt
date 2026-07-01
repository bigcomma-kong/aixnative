package com.aixnative.account.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EntityListeners
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.annotation.CreatedDate
import org.springframework.data.jpa.domain.support.AuditingEntityListener
import java.time.Instant

/**
 * Tenant = isolation boundary. v1: one tenant per user; later a team/company
 * plan maps multiple users to one tenant without schema change.
 */
@Entity
@Table(name = "tenants")
@EntityListeners(AuditingEntityListener::class)
class Tenant(
    @Column(nullable = false)
    var name: String = "",
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant? = null
}
