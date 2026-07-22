package com.aixnative.residential.domain

import com.aixnative.common.tenant.BaseTenantEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table

/**
 * AI 동네 브리핑(크레딧 과금) 결과 - 사용자별 저장, 마이페이지 조회용. 테넌트 스코프([BaseTenantEntity]).
 * 무료 리포트(비인증·휘발)와 달리 유료 브리핑만 영속.
 */
@Entity
@Table(name = "residential_brief")
class ResidentialBrief(
    @Column(nullable = false, length = 300)
    var query: String,

    @Column(length = 40)
    var region: String? = null,

    @Column(name = "brief_text", columnDefinition = "TEXT", nullable = false)
    var briefText: String,
) : BaseTenantEntity()
