package com.aixnative.marketfeed.ingest

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * 시장 인텔리전스 자동 수집 설정. 수집 자체는 키 0개(공개 RSS·구글뉴스)로 동작하고,
 * 마켓 브리핑 합성만 무료 Mistral 키가 있을 때 켜진다(graceful).
 *
 * 트리거는 Cloud Run(min-instances=0)에 맞춰 Cloud Scheduler → 토큰 보호 엔드포인트 방식.
 * [ingestToken] 미설정 시 인입 엔드포인트는 비활성(403) — 관리자 수동 트리거만 가능.
 */
@ConfigurationProperties(prefix = "marketfeed")
data class MarketFeedProperties(
    /** Cloud Scheduler 호출 검증용 공유 시크릿(X-Ingest-Token). 빈 값이면 인입 엔드포인트 비활성. */
    val ingestToken: String = "",
    /** 수집 윈도(시간) — 이 시간 이내 발행분만 카드화. */
    val recentHours: Long = 72,
    /** 소스(섹터)당 카드 상한. */
    val maxPerSource: Int = 15,
    /** 전체 신규 카드 상한(한 번의 수집에서). */
    val maxCards: Int = 90,
    /** 구글뉴스 섹터 딜 검색 사용. */
    val googleNewsEnabled: Boolean = true,
    /** 마켓 브리핑(AI 다이제스트) 생성 시도. 무료 AI 미설정 시 자동 생략. */
    val briefingEnabled: Boolean = true,
) {
    val ingestEndpointEnabled: Boolean get() = ingestToken.isNotBlank()
}
