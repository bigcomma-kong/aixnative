package com.aixnative.marketfeed.web

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.time.Instant
import com.aixnative.marketfeed.domain.MarketFeedItem

/** 피드 카드 응답(읽기). */
data class MarketFeedItemView(
    val id: Long,
    val title: String,
    val summary: String?,
    val assetType: String?,
    val location: String?,
    val sourceText: String?,
    val sourceUrl: String?,
    val publishedAt: Instant?,
    val origin: String?,
)

/** 관심 딜(찜) — 저장된 카드 스냅샷. */
data class DealWatchView(
    val id: Long,
    val feedItemId: Long,
    val title: String,
    val summary: String?,
    val assetType: String?,
    val location: String?,
    val sourceText: String?,
    val sourceUrl: String?,
    val createdAt: Instant?,
)

data class DealWatchRequest(val feedItemId: Long)

/** 피드 페이지 응답 — 더보기(아카이브) 페이지네이션용. */
data class MarketFeedPage(
    val items: List<MarketFeedItemView>,
    val page: Int,
    val hasMore: Boolean,
)

/** 관리자 생성 요청(쓰기, ADMIN). */
data class MarketFeedCreateRequest(
    @field:NotBlank
    @field:Size(max = 200)
    val title: String,

    @field:Size(max = 1000)
    val summary: String? = null,

    @field:Size(max = 20)
    val assetType: String? = null,

    @field:Size(max = 120)
    val location: String? = null,

    @field:Size(max = 4000)
    val sourceText: String? = null,

    @field:Size(max = 500)
    val sourceUrl: String? = null,

    /** 미지정 시 생성 시각으로 채움. */
    val publishedAt: Instant? = null,
)

fun MarketFeedItem.toView(): MarketFeedItemView =
    MarketFeedItemView(
        id = id ?: 0,
        title = title,
        summary = summary,
        assetType = assetType,
        location = location,
        sourceText = sourceText,
        sourceUrl = sourceUrl,
        publishedAt = publishedAt,
        origin = origin,
    )

/** 마켓 브리핑(뉴스레터 강점) 응답 — sections/watchlist/risks 는 파싱된 배열로 반환. */
data class BriefingSection(val topic: String? = null, val summary: String? = null, val impact: String? = null)
data class BriefingWatch(val item: String? = null, val why: String? = null)
data class BriefingRisk(val signal: String? = null, val severity: String? = null, val mitigation: String? = null)

/** 지난 브리핑 아카이브 목록 항목(가벼운 메타). 본문은 /briefing/{id} 로 조회. */
data class BriefingHistoryItem(
    val id: Long,
    val briefingDate: String?,
    val headline: String?,
    val articleCount: Int?,
    val generatedAt: Instant?,
)

data class MarketBriefingView(
    val id: Long,
    val briefingDate: String?,
    val headline: String?,
    val outlook: String?,
    val sections: List<BriefingSection>,
    val watchlist: List<BriefingWatch>,
    val risks: List<BriefingRisk>,
    val articleCount: Int?,
    val provider: String?,
    val generatedAt: Instant?,
)

/** 관리자 — 뉴스레터 구독자/발송 로그. */
data class NewsSubscriberView(val email: String, val active: Boolean, val createdAt: Instant?)
data class NewsletterSendLogView(val email: String, val subject: String?, val status: String, val sentAt: Instant?)

/** AI 심층 시장 리포트(크레딧 소비, Claude). 무료 브리핑(Mistral)보다 깊은 온디맨드 분석. */
data class DeepReportRequest(val focus: String? = null)
data class DeepReportSection(val title: String? = null, val body: String? = null, val bullets: List<String>? = null)

/** 픽 — 무료 브리핑과 달리 확신도·핵심 리스크까지. */
data class DeepReportPick(
    val title: String? = null,
    val why: String? = null,
    val conviction: String? = null, // 높음 | 중간 | 낮음
    val risk: String? = null,
)

/** 섹터 스코어보드 — 심층 리포트만의 정량 차별점. */
data class DeepSector(
    val name: String? = null,
    val stance: String? = null, // 비중확대 | 중립 | 비중축소
    val score: Int? = null,     // 0~100 모멘텀
    val note: String? = null,
)

/** 시나리오 — 기본/낙관/비관 분기. */
data class DeepScenario(
    val name: String? = null,   // 기본 | 낙관 | 비관
    val narrative: String? = null,
)

/** 지난 심층 리포트 목록 항목(가벼운 메타). 본문은 /deep-report/{id} 로 조회. */
data class DeepReportHistoryItem(
    val id: Long,
    val headline: String?,
    val generatedAt: Instant?,
)

data class MarketDeepReportView(
    val headline: String?,
    val summary: String?,
    val marketTempScore: Int?,      // 0~100 시장 과열도
    val marketTempLabel: String?,   // 침체 | 중립 | 과열 등
    val sectors: List<DeepSector>,
    val scenarios: List<DeepScenario>,
    val sections: List<DeepReportSection>,
    val picks: List<DeepReportPick>,
    val contrarian: String?,        // 시장이 놓치고 있는 점
    val provider: String,
    val creditBalance: Int,
    val disclaimer: String,
)
