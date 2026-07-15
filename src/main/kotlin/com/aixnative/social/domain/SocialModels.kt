package com.aixnative.social.domain

/** 수집된 소재 기사 1건(랭킹 후보). */
data class SourceArticle(
    val title: String,
    val summary: String,
    val link: String,
    val source: String,
)

/**
 * 한 장의 카드 초안 - 한 소스가 만든 랭킹 카드 재료(제목·출처·리스크·소재 목록).
 * [CardSource.produce] 가 반환하고, 오케스트레이터가 Claude 큐레이션 → SocialPost 로 만든다.
 */
data class CardDraft(
    val title: String,           // "오늘 유튜브 엔터 인기 TOP 5"
    val sourceType: SourceType,
    val riskLevel: RiskLevel,
    val dedupSuffix: String,     // 중복 차단 키 접두("youtube:엔터")
    val topic: String,           // 캡션 프롬프트용 주제 라벨
    val articles: List<SourceArticle>,
)

/** 랭킹 카드 슬라이드 1장(Claude 큐레이션 결과). slides_json 으로 직렬화. */
data class RankSlide(
    val rank: Int,
    val title: String,
    val summary: String,
    val sourceName: String,
    val sourceUrl: String,
)

/** 출처 근거(저작권) - source_refs_json 으로 직렬화. */
data class SourceRef(
    val name: String,
    val url: String,
)

/** 한 번의 소셜 수집·생성 실행 결과 요약(관리자/스케줄러 응답). */
data class SocialIngestReport(
    val topicsRequested: Int,
    val sourcesFetched: Int,
    val postsCreated: Int,
    val skippedDuplicate: Int,
    val rendered: Int,
    /** 완전 자동 게시(auto-publish)로 실제 게시된 건수. 반자동이면 0. */
    val published: Int = 0,
    val errors: List<String> = emptyList(),
)
