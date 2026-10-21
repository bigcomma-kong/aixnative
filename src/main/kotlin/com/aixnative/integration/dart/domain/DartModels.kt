package com.aixnative.integration.dart.domain

import java.math.BigDecimal

/** DART 고유번호 목록 1건(corpCode.xml). [stockCode] 가 있으면 상장사. */
data class DartCorp(val corpCode: String, val corpName: String, val stockCode: String?)

/** 기업개황(company.json) 중 화면·판단에 쓰는 필드. */
data class DartCompanyOverview(
    val corpCode: String,
    val corpName: String,
    val stockCode: String?,
    val ceoName: String?,
    /** Y 유가증권 · K 코스닥 · N 코넥스 · E 기타. */
    val corpClass: String?,
    val bizrNo: String?,
    val address: String?,
    val establishDate: String?,
    val fiscalMonth: String?,
) {
    val corpClassLabel: String
        get() = when (corpClass) {
            "Y" -> "유가증권시장"
            "K" -> "코스닥"
            "N" -> "코넥스"
            else -> "기타법인"
        }
}

/**
 * 한 사업연도의 주요 재무 계정(원 단위). DART 단일회사 전체 재무제표(fnlttSinglAcntAll) 사업보고서 기준.
 * 계정이 없으면 null - 0 으로 채우면 비율이 그럴듯하게 틀린다.
 */
data class DartFinancials(
    val year: Int,
    /** CFS 연결 / OFS 별도. */
    val fsDiv: String,
    val totalAssets: BigDecimal? = null,
    val totalLiabilities: BigDecimal? = null,
    val totalEquity: BigDecimal? = null,
    val currentAssets: BigDecimal? = null,
    val currentLiabilities: BigDecimal? = null,
    val inventory: BigDecimal? = null,
    val revenue: BigDecimal? = null,
    val operatingIncome: BigDecimal? = null,
    val netIncome: BigDecimal? = null,
    val operatingCashFlow: BigDecimal? = null,
    val priorRevenue: BigDecimal? = null,
    val priorOperatingIncome: BigDecimal? = null,
) {
    val fsDivLabel: String get() = if (fsDiv == "CFS") "연결" else "별도"
}
