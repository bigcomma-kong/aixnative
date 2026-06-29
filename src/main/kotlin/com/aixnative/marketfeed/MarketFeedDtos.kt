package com.aixnative.marketfeed

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.time.Instant

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

/** AI 심층 시장 리포트(크레딧 소비, Claude). 무료 브리핑(Mistral)보다 깊은 온디맨드 분석. */
data class DeepReportRequest(val focus: String? = null)
data class DeepReportSection(val title: String? = null, val body: String? = null)
data class DeepReportPick(val title: String? = null, val why: String? = null)
data class MarketDeepReportView(
    val headline: String?,
    val summary: String?,
    val sections: List<DeepReportSection>,
    val picks: List<DeepReportPick>,
    val provider: String,
    val creditBalance: Int,
    val disclaimer: String,
)
