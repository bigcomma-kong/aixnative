package com.aixnative.property.domain

import com.aixnative.common.tenant.BaseTenantEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table

/**
 * 관리 대상 건물 - PM(자산관리)이 AM 위탁으로 관리하는 자산 1채. 테넌트 스코프([BaseTenantEntity]).
 * 임대차([Lease])의 상위 그룹. 렌트롤·리스크·AM 보고서는 건물 단위로 집계된다.
 */
@Entity
@Table(name = "building")
class Building(
    @Column(nullable = false, length = 200)
    var name: String,

    @Column(length = 300)
    var address: String? = null,

    /** 오피스 | 물류 | 호텔 | 리테일 (자유 입력이지만 정규화 권장). */
    @Column(name = "asset_type", length = 20)
    var assetType: String? = null,

    /** 연면적(평). */
    @Column(name = "gfa_pyeong")
    var gfaPyeong: Double? = null,

    @Column(length = 1000)
    var notes: String? = null,
) : BaseTenantEntity()
