package com.aixnative.common.tenant

import jakarta.persistence.Column
import jakarta.persistence.EntityListeners
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.MappedSuperclass
import org.springframework.data.annotation.CreatedDate
import org.springframework.data.jpa.domain.support.AuditingEntityListener
import java.time.Instant

/**
 * Base for all tenant-scoped domain entities. Carries [tenantId] + [ownerUserId]
 * on day one so every row is attributable to a tenant and isolated from others.
 */
@MappedSuperclass
@EntityListeners(AuditingEntityListener::class)
abstract class BaseTenantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null

    @Column(name = "tenant_id", nullable = false)
    var tenantId: Long = 0

    @Column(name = "owner_user_id", nullable = false)
    var ownerUserId: Long = 0

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant? = null
}
