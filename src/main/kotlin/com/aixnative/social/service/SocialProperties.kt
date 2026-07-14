package com.aixnative.social.service

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * 공감랭킹 소셜 자동게시 설정.
 *
 * 소재 수집(구글뉴스 RSS)은 키 0개로 동작하고, 캡션 생성은 Claude(운영 계정) 사용.
 * 게시(인스타/유튜브)는 계정 키가 설정될 때만 활성(graceful) - 미설정 시 승인까지만 하고 게시는 대기.
 *
 * 트리거는 Cloud Scheduler → 토큰 보호 엔드포인트(`POST /api/ingest/social-post`, 헤더 X-Ingest-Token).
 * [ingestToken] 미설정 시 인입 엔드포인트 비활성(403) - 관리자 수동 트리거만 가능.
 */
@ConfigurationProperties(prefix = "social")
data class SocialProperties(
    /** Cloud Scheduler 호출 검증용 공유 시크릿(X-Ingest-Token). 빈 값이면 인입 엔드포인트 비활성. */
    val ingestToken: String = "",
    /** 콘텐츠 주제 목록(콤마 구분). 각 주제마다 랭킹 카드 1건 생성 시도. */
    val topics: List<String> = listOf("부동산 투자", "재테크", "경제 이슈"),
    /** 주제당 소재 수집 상한(구글뉴스 상위 N). */
    val maxSourcesPerTopic: Int = 12,
    /** 랭킹 카드 1장당 슬라이드(항목) 수 상한. */
    val rankSize: Int = 5,
    /** 소재 수집 윈도(시간) - 이 시간 이내 발행분만 후보. */
    val recentHours: Long = 168,
    /** 인스타그램 게시 설정. */
    val instagram: Instagram = Instagram(),
    /** 카드 이미지 렌더러(Node satori) 호출 설정. */
    val render: Render = Render(),
) {
    val ingestEndpointEnabled: Boolean get() = ingestToken.isNotBlank()

    /**
     * 카드 이미지 렌더 - JVM 이 Node 스크립트(render/render-card.mjs)를 프로세스로 호출.
     * node 미설치/스크립트 부재 시 렌더가 실패해도 게시물은 DRAFT 로 남는다(graceful).
     */
    data class Render(
        val nodeBin: String = "node",
        val scriptPath: String = "render/render-card.mjs",
        val timeoutMs: Long = 30_000,
    )

    data class Instagram(
        /** IG Graph API 장기 액세스 토큰. 빈 값이면 게시 비활성(승인까지만). */
        val accessToken: String = "",
        /** IG 비즈니스 계정 ID(Facebook 페이지 연결). */
        val businessAccountId: String = "",
        val graphApiUrl: String = "https://graph.facebook.com/v21.0",
    ) {
        fun isConfigured(): Boolean = accessToken.isNotBlank() && businessAccountId.isNotBlank()
    }
}
