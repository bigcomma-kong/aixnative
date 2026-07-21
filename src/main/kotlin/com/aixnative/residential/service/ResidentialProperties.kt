package com.aixnative.residential.service

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * 주거·소비자 입지 모듈 설정 - 무료 입지 리포트(유입 top-of-funnel)용 외부 소스.
 *
 * 데이터 배관 대부분은 기존 자산 재사용:
 *   - data.go.kr 실거래/단지/청약 → [com.aixnative.integration.marketdata.service.MarketDataProperties.dataGoKrKey]
 *     (API 별 "활용신청"만 추가, 키는 동일). 미설정/미승인 시 graceful degrade.
 *   - 지오코딩·POI → 카카오 로컬 REST([kakaoRestKey], 로그인용 카카오 앱 REST 키 재사용 가능).
 *
 * 전부 키 미설정 시 해당 소스만 조용히 빈 결과 → 리포트는 가능한 부분만 채운다(부분 실패 허용).
 */
@ConfigurationProperties(prefix = "residential")
data class ResidentialProperties(
    /** 카카오 로컬(주소검색·카테고리/키워드 POI) REST API 키. 빈 값이면 지오코딩·POI 비활성. */
    val kakaoRestKey: String = "",
    /** 공동주택 단지(K-apt) 조회 사용 여부(data.go.kr 활용신청 후 on). */
    val kaptEnabled: Boolean = true,
    /** 청약홈 분양정보 조회 사용 여부(Phase 3, data.go.kr 활용신청 후 on). */
    val cheongyakEnabled: Boolean = true,
) {
    val kakaoEnabled: Boolean get() = kakaoRestKey.isNotBlank()
}
