package com.aixnative.marketfeed.service

import com.aixnative.common.tenant.TenantContext
import com.aixnative.common.web.NotFoundException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import com.aixnative.marketfeed.domain.DealWatch
import com.aixnative.marketfeed.repository.DealWatchRepository
import com.aixnative.marketfeed.repository.MarketFeedRepository
import com.aixnative.marketfeed.web.DealWatchView

/**
 * 관심 딜(찜) — 시장 피드 카드를 사용자별로 저장/해제/조회. 전부 테넌트 스코프(IDOR 차단).
 * 무료 액션(크레딧 미차감) — 재방문 → 분석(크레딧) 전환을 노리는 퍼널.
 */
@Service
class DealWatchService(
    private val watches: DealWatchRepository,
    private val feed: MarketFeedRepository,
) {
    /** 카드 찜(idempotent). 이미 있으면 그대로 반환. 카드 표시 필드를 비정규화 저장. */
    @Transactional
    fun add(feedItemId: Long): DealWatchView {
        val current = TenantContext.require()
        val existing = watches.findByTenantIdAndOwnerUserIdAndFeedItemId(current.tenantId, current.userId, feedItemId)
        if (existing != null) return existing.toView()

        val card = feed.findById(feedItemId).orElseThrow { NotFoundException("딜을 찾을 수 없습니다.") }
        val watch = DealWatch(
            feedItemId = feedItemId,
            title = card.title.take(300),
            summary = card.summary?.take(1000),
            assetType = card.assetType,
            location = card.location,
            sourceText = card.sourceText?.take(4000),
            sourceUrl = card.sourceUrl,
        ).apply {
            tenantId = current.tenantId
            ownerUserId = current.userId
        }
        return watches.save(watch).toView()
    }

    /** 찜 해제(원본 카드 id 기준). 없으면 조용히 무시. */
    @Transactional
    fun remove(feedItemId: Long) {
        val current = TenantContext.require()
        watches.findByTenantIdAndOwnerUserIdAndFeedItemId(current.tenantId, current.userId, feedItemId)
            ?.let { watches.delete(it) }
    }

    /** 내 관심 딜 목록(최신순). */
    @Transactional(readOnly = true)
    fun listMine(): List<DealWatchView> {
        val current = TenantContext.require()
        return watches.findByTenantIdAndOwnerUserIdOrderByIdDesc(current.tenantId, current.userId).map { it.toView() }
    }

    /** 내가 찜한 카드 id 집합(피드의 ⭐ 채움 상태 표시용). */
    @Transactional(readOnly = true)
    fun myFeedItemIds(): List<Long> {
        val current = TenantContext.require()
        return watches.findByTenantIdAndOwnerUserIdOrderByIdDesc(current.tenantId, current.userId).map { it.feedItemId }
    }

    private fun DealWatch.toView() = DealWatchView(
        id = id ?: 0,
        feedItemId = feedItemId,
        title = title,
        summary = summary,
        assetType = assetType,
        location = location,
        sourceText = sourceText,
        sourceUrl = sourceUrl,
        createdAt = createdAt,
    )
}
