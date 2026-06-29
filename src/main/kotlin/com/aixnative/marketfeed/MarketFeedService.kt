package com.aixnative.marketfeed

import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/**
 * 시장 인텔리전스 피드. 글로벌 콘텐츠라 테넌트 스코프가 없다.
 * 읽기는 누구나(인증), 쓰기는 ADMIN 전용(컨트롤러에서 SecurityConfig 로 게이트).
 */
@Service
class MarketFeedService(
    private val repository: MarketFeedRepository,
) {
    /** 최신 피드 N개(기본 [DEFAULT_LIMIT], 최대 [MAX_LIMIT]). */
    @Transactional(readOnly = true)
    fun latest(limit: Int = DEFAULT_LIMIT): List<MarketFeedItemView> {
        val capped = limit.coerceIn(1, MAX_LIMIT)
        return repository.findAllByOrderByPublishedAtDescIdDesc(PageRequest.of(0, capped))
            .map { it.toView() }
    }

    @Transactional
    fun create(req: MarketFeedCreateRequest): MarketFeedItemView {
        val item = MarketFeedItem(
            title = req.title.trim(),
            summary = req.summary?.trim()?.ifBlank { null },
            assetType = req.assetType?.trim()?.ifBlank { null },
            location = req.location?.trim()?.ifBlank { null },
            sourceText = req.sourceText?.trim()?.ifBlank { null },
            sourceUrl = req.sourceUrl?.trim()?.ifBlank { null },
            publishedAt = req.publishedAt ?: Instant.now(),
        )
        return repository.save(item).toView()
    }

    @Transactional
    fun delete(id: Long) {
        repository.deleteById(id)
    }

    private companion object {
        const val DEFAULT_LIMIT = 30
        const val MAX_LIMIT = 100
    }
}
