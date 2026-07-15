package com.aixnative.social.domain

/** 수집된 소재 기사 1건(주제 랭킹 후보). */
data class SourceArticle(
    val title: String,
    val summary: String,
    val link: String,
    val source: String,
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
