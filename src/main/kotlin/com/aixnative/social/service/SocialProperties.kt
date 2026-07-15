package com.aixnative.social.service

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * 공감랭킹 소셜 자동게시 설정.
 *
 * 소재 수집은 다중 소스(유튜브 인기·구글 트렌드·언론사 RSS·커뮤니티)로, 유튜브만 API 키가 필요하고
 * 나머지는 키 0개. 캡션 생성은 Claude(운영 계정). 게시(인스타/유튜브)는 계정 키가 설정될 때만 활성(graceful).
 *
 * 트리거는 Cloud Scheduler → 토큰 보호 엔드포인트(`POST /api/ingest/social-post`, 헤더 X-Ingest-Token).
 * [ingestToken] 미설정 시 인입 엔드포인트 비활성(403) - 관리자 수동 트리거만 가능.
 */
@ConfigurationProperties(prefix = "social")
data class SocialProperties(
    /** Cloud Scheduler 호출 검증용 공유 시크릿(X-Ingest-Token). 빈 값이면 인입 엔드포인트 비활성. */
    val ingestToken: String = "",
    /** 랭킹 카드 1장당 슬라이드(항목) 수 상한. */
    val rankSize: Int = 5,
    /** 소재 수집 윈도(시간) - 이 시간 이내 발행분만 후보. */
    val recentHours: Long = 168,
    /**
     * 유튜브 Data API v3 키(YOUTUBE_API_KEY). 빈 값이면 유튜브 인기영상 소스만 graceful 비활성.
     * 무료 쿼터 1만/일, 인기영상 조회는 호출당 1 unit. GCP 콘솔 aixnative 프로젝트에서 신규 발급.
     */
    val youtubeApiKey: String = "",
    /**
     * 유튜브 인기영상 카테고리 ID 목록(regionCode=KR). 카테고리별로 카드 1건 생성.
     * 기본: 엔터24·스포츠17·음악10·과학기술28. env(SOCIAL_YOUTUBE_CATEGORIES)로 조정.
     */
    val youtubeCategories: List<YoutubeCategory> = listOf(
        YoutubeCategory("24", "엔터"),
        YoutubeCategory("17", "스포츠"),
        YoutubeCategory("10", "음악"),
        YoutubeCategory("28", "과학기술"),
    ),
    /**
     * 언론사 직접 RSS 피드 목록(분야명|URL). 구글뉴스와 달리 Cloud Run 송신 IP 에서도 동작.
     * 기동 시 헬스체크 후 사용. env(SOCIAL_NEWS_FEEDS)로 조정.
     */
    val newsFeeds: List<NewsFeed> = listOf(
        NewsFeed("IT", "https://feeds.feedburner.com/zdkorea"),
    ),
    /**
     * 커뮤니티 정적 수집 대상(이름|URL). 공식 RSS 부재·JS 렌더가 많아 막히면 graceful 빈결과.
     * 항상 리스크 HIGH 로 표기(관리자 승인 단계에서 판단). 기본 비활성(빈 목록).
     */
    val communityTargets: List<CommunityTarget> = emptyList(),
    /**
     * 커뮤니티 유저 업로드 사진을 슬라이드 배경으로 쓸지. 저작권·초상권 리스크 최고라 기본 false.
     * true 여도 각 슬라이드에 출처 표기. (유튜브 썸네일·뉴스 대표컷은 이 값과 무관하게 항상 사용.)
     */
    val useCommunityImages: Boolean = false,
    /**
     * 스토리 모드 커뮤니티 대상(핫글 리스트 페이지: label=게시판명, url=인기/베스트 게시판).
     * 각 대상에서 상위 핫글 각각을 별도 스토리 게시물로. 기본 빈 목록(명시적 on). 리스크 HIGH 강제.
     */
    val communityStoryTargets: List<CommunityTarget> = emptyList(),
    /** 대상당 스토리로 만들 상위 핫글 수. */
    val storyPostsPerTarget: Int = 3,
    /** 스토리 1건당 장면 수 상한(표지·아웃트로 제외). */
    val storyMaxScenes: Int = 6,
    /**
     * 완전 자동 게시. true 면 스케줄러 트리거(POST /api/ingest/social-post) 수집분을
     * 승인 없이 렌더 후 바로 게시한다(계정 연동 시). 기본 false(반자동 - 관리자 승인 필요).
     * 관리자 수동 트리거는 이 값과 무관하게 항상 승인 대기(검토용).
     */
    val autoPublish: Boolean = false,
    /** 인스타그램 게시 설정. */
    val instagram: Instagram = Instagram(),
    /** 카드 이미지 렌더러(Node satori) 호출 설정. */
    val render: Render = Render(),
) {
    val ingestEndpointEnabled: Boolean get() = ingestToken.isNotBlank()
    val youtubeEnabled: Boolean get() = youtubeApiKey.isNotBlank()

    /** 유튜브 인기영상 카테고리(id + 한글 라벨). */
    data class YoutubeCategory(val id: String = "", val label: String = "")

    /** 언론사 직접 RSS 피드(분야 라벨 + URL). */
    data class NewsFeed(val label: String = "", val url: String = "")

    /** 커뮤니티 정적 수집 대상(이름 + URL). */
    data class CommunityTarget(val label: String = "", val url: String = "")

    /**
     * 카드 이미지 렌더 - JVM 이 Node 스크립트(render/render-card.mjs)를 프로세스로 호출.
     * node 미설치/스크립트 부재 시 렌더가 실패해도 게시물은 DRAFT 로 남는다(graceful).
     */
    data class Render(
        val nodeBin: String = "node",
        val scriptPath: String = "render/render-card.mjs",
        // 캐러셀(표지+항목 N장) + 원격 이미지 프리페치라 단일 렌더보다 여유 필요.
        val timeoutMs: Long = 90_000,
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
