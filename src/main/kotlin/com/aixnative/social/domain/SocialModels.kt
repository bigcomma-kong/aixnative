package com.aixnative.social.domain

/** 수집된 소재 기사 1건(랭킹 후보). */
data class SourceArticle(
    val title: String,
    val summary: String,
    val link: String,
    val source: String,
    /** 소재 대표 이미지(유튜브 썸네일·뉴스 대표컷 등). 슬라이드 배경 합성용. 없으면 null. */
    val imageUrl: String? = null,
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

/**
 * 스토리 장면 1컷(커뮤니티 핫글 각색). STORY 게시물의 slides_json 에 직렬화(RankSlide 자리 재사용).
 *  - caption: 하단 자막박스 문구
 *  - imagePrompt: ImageEngine 입력(영어, 실명/특정인물 금지)
 *  - imageUrl: 스톡 사진 URL(렌더 시점에 fetch - 랭킹과 동일, 큰 base64 미저장). 우선 사용.
 *  - imageB64: 생성형(Gemini) 결과(프리픽스 없는 base64). imageUrl 없을 때 폴백.
 *  - 둘 다 null 이면 렌더러가 타이포형 폴백.
 */
data class StoryScene(
    val caption: String,
    val imagePrompt: String,
    val imageUrl: String? = null,
    val imageB64: String? = null,
)

/**
 * 스토리 게시물의 slides_json 직렬화 래퍼(장면 목록 + 아웃트로).
 * RANKING 의 slides_json=RankSlide[] 와 달리 STORY 는 이 객체를 담는다(별도 컬럼 불필요).
 */
data class StoryScript(
    val scenes: List<StoryScene>,
    val outro: String? = null,
)

/**
 * 스토리 게시물 1건 초안([StorySource] 반환) - 커뮤니티 핫글/트렌드 검색어/랭킹뉴스 공통.
 * url 은 딥페치 대상(본문 각색 소스). sourceType 으로 커뮤니티/트렌드/뉴스 배지를 구분.
 */
data class StoryDraft(
    val board: String,          // 출처 라벨("에펨코리아 포텐"·"지금 뜨는 검색어"·"네이버 랭킹뉴스")
    val url: String,            // 원문/기사 링크(딥페치 대상)
    val title: String,          // 핫글 제목 또는 검색어/기사 제목
    val engagement: String?,    // 참여수·검색량 텍스트(없으면 null)
    val riskLevel: RiskLevel,   // 커뮤니티 HIGH, 트렌드/뉴스 MEDIUM
    val dedupSuffix: String,    // "story:{board}:{urlHash}" 등
    val sourceType: SourceType = SourceType.COMMUNITY,
)

/** 랭킹 카드 슬라이드 1장(Claude 큐레이션 결과). slides_json 으로 직렬화. */
data class RankSlide(
    val rank: Int,
    val title: String,
    val summary: String,
    val sourceName: String,
    val sourceUrl: String,
    /** 슬라이드 배경 합성용 이미지 URL(소재에서 결정론적으로 주입). 없으면 디자인형 폴백. */
    val imageUrl: String? = null,
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
