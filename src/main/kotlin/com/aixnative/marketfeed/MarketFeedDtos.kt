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
    )
