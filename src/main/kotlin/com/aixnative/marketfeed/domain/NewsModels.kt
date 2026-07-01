package com.aixnative.marketfeed.domain

import java.time.Instant

/** 수집된 기사 1건(소스 무관 정규화 형태). */
data class NewsItem(
    val title: String,
    val summary: String,
    val link: String,
    val publishedAt: Instant?,
    /** 출처 라벨 — 'RSS:한국경제' | 'GOOGLE_NEWS' 등. */
    val source: String,
    /** 동음이의 게이트가 필요한 느슨한 소스(구글뉴스 등)인지 — 부동산 앵커 필터 강하게 적용. */
    val loose: Boolean = false,
    /** 섹터 힌트(딜 검색에서 유래) — office|logistics|hotel|retail|datacenter|reit|pf. */
    val sectorHint: String? = null,
)

/** 한 번의 수집 실행 결과 요약(관리자/스케줄러 응답). */
data class IngestReport(
    val fetched: Int,
    val afterFilter: Int,
    val inserted: Int,
    val skippedDuplicate: Int,
    val briefingGenerated: Boolean,
    val briefingProvider: String? = null,
    val errors: List<String> = emptyList(),
)
