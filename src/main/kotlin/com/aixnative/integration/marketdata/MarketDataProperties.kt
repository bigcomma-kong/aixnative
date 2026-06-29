package com.aixnative.integration.marketdata

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * 실측 시장데이터 공공 API 키 (전부 신규 발급 + env 외부화 — MASTERN 키 복사 금지).
 * 모든 값은 빈 문자열 기본 → 미설정 시 해당 소스는 graceful degrade(빈 결과)로 건너뛴다.
 *
 *   ECOS(한국은행)        ecos-key       — 기준금리·국고채       (data.go.kr 아님, ecos.bok.or.kr)
 *   R-ONE(한국부동산원)    reb-key        — 권역 공실률·임대료·수익률
 *   국토부 RTMS 실거래가    data-go-kr-key — 상업업무용·토지 거래 comps
 *   V-World               vworld-key     — 개별공시지가·용도지역 (PNU 필요)
 *   juso(도로명주소)       juso-key       — 주소→법정동코드(10)+번지 (PNU 조립용 지오코더)
 *
 * 시군구코드(5)는 내장 표([LawdCode])로 처리하지만, 공시지가/용도지역은 필지 PNU(19)가 필요해
 * juso 지오코더로 법정동코드(10)+번+지+산 을 얻는다. (Kakao 비즈앱 게이트 회피.)
 */
@ConfigurationProperties(prefix = "marketdata")
data class MarketDataProperties(
    val ecosKey: String = "",
    val rebKey: String = "",
    val dataGoKrKey: String = "",
    val vworldKey: String = "",
    val jusoKey: String = "",
    // V-World 키에 등록한 운영 도메인(예: aixnative.com). 서버 호출은 Referer 가 없어 V-World 가
    // INCORRECT_KEY 로 거부하므로, 등록 도메인을 domain 파라미터로 보내 인증을 통과시킨다(필수).
    val vworldDomain: String = "",
    // V-World NED 국토정보(ned/data, 개별공시지가) 사용 여부. 키에 해당 NED API 활용신청 + 도메인 일치 시 동작.
    val vworldNedEnabled: Boolean = false,
) {
    val ecosEnabled: Boolean get() = ecosKey.isNotBlank()
    val rebEnabled: Boolean get() = rebKey.isNotBlank()
    val rtmsEnabled: Boolean get() = dataGoKrKey.isNotBlank()

    /** 용도지역은 V-World 인증키(req/data)만으로 동작(자체 지오코더 사용 — juso 불필요). */
    val landUseEnabled: Boolean get() = vworldKey.isNotBlank()

    /** 개별공시지가는 V-World NED 등록 + juso 지오코더(PNU)까지 필요. */
    val landPriceEnabled: Boolean get() = vworldNedEnabled && vworldKey.isNotBlank() && jusoKey.isNotBlank()
}
