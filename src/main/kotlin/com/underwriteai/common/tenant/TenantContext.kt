package com.underwriteai.common.tenant

import com.underwriteai.common.web.UnauthorizedException

/**
 * Holds the current request's tenant/user, populated by the JWT auth filter.
 * Every tenant-scoped query/mutation must read [requireTenantId] so that all
 * data access is constrained to the caller's tenant (IDOR 차단).
 *
 * v1: 1 user = 1 tenant. The structure already separates tenant from user so a
 * team/company plan (multiple users → one tenant) can be added without rework.
 */
object TenantContext {

    data class Current(val tenantId: Long, val userId: Long, val email: String)

    private val holder = ThreadLocal<Current?>()

    fun set(current: Current) = holder.set(current)

    fun clear() = holder.remove()

    fun currentOrNull(): Current? = holder.get()

    fun require(): Current =
        holder.get() ?: throw UnauthorizedException()

    fun requireTenantId(): Long = require().tenantId

    fun requireUserId(): Long = require().userId
}
