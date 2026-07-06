package com.aixnative.headline.service

import com.aixnative.headline.domain.HeadlineItem
import com.aixnative.headline.repository.HeadlineRepository
import com.aixnative.headline.web.HeadlineGroup
import com.aixnative.headline.web.HeadlineView
import com.aixnative.marketfeed.service.NewsTextFilter
import com.aixnative.marketfeed.service.RssNewsCollector
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * 업계 헤드라인 보드 — 수집(기존 [RssNewsCollector] 파서 재사용)과 조회(매체별 그룹핑)를 담당.
 * 딜 카드 파이프라인과 저장소가 분리돼 있어 수익 surface(market_feed)에 영향을 주지 않는다.
 * 수집은 [com.aixnative.marketfeed.service.MarketFeedIngestService] 인제스트에 얹혀 함께 돈다.
 */
@Service
class HeadlineService(
    private val collector: RssNewsCollector,
    private val repository: HeadlineRepository,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 헤드라인 수집·적재. 기존 인제스트에서 호출(graceful). 저장분 수 반환.
     * @param purge true 면 자동 수집분을 지우고 새로 채운다(오염 정리, 기본 false=누적).
     */
    @Transactional
    fun ingest(purge: Boolean = false): Int {
        if (purge) {
            val removed = repository.deleteByDedupKeyIsNotNull()
            log.info("[headline] purge=true — {}건 삭제 후 재수집", removed)
        }

        val raw = collector.collectHeadlines()
        val cutoff = Instant.now().minus(RECENT_DAYS, ChronoUnit.DAYS)

        // 정제(제목 정리·노이즈 제거) → 인-배치 중복제거(정규화 링크) → 최근분만.
        val deduped = LinkedHashMap<String, HeadlineItem>()
        for (item in raw) {
            val title = HeadlineTextCleaner.clean(item.title, item.source) ?: continue
            if (item.publishedAt != null && item.publishedAt.isBefore(cutoff)) continue
            val key = NewsTextFilter.normalizeLink(item.link)
            if (key.isBlank()) continue
            deduped.putIfAbsent(
                key,
                HeadlineItem(
                    title = title.take(TITLE_MAX),
                    source = HeadlineTextCleaner.outletOf(item.source),
                    sourceUrl = item.link,
                    publishedAt = item.publishedAt ?: Instant.now(),
                    dedupKey = key,
                ),
            )
        }

        // 매체당 상한(다양성 확보).
        val perSource = HashMap<String, Int>()
        val capped = deduped.values.filter {
            val n = perSource.getOrDefault(it.source, 0)
            if (n >= MAX_PER_SOURCE) false else { perSource[it.source] = n + 1; true }
        }

        // DB 중복제거: 이미 있는 dedup_key 제외 후 저장.
        val keys = capped.mapNotNull { it.dedupKey }
        val existing = if (keys.isEmpty()) emptySet() else
            repository.findByDedupKeyIn(keys).mapNotNull { it.dedupKey }.toHashSet()

        var inserted = 0
        for (item in capped) {
            if (item.dedupKey in existing) continue
            repository.save(item)
            inserted++
        }
        log.info("[headline] fetched={} candidates={} inserted={}", raw.size, capped.size, inserted)
        return inserted
    }

    /** 매체별로 묶은 최신 헤드라인(보드 화면용). 매체 순서는 [SOURCE_ORDER] 고정, 그 안은 발행 최신순. */
    @Transactional(readOnly = true)
    fun grouped(): List<HeadlineGroup> {
        val items = repository.findTop120ByOrderByPublishedAtDescIdDesc()
        return items.groupBy { it.source }
            .map { (source, list) ->
                HeadlineGroup(
                    source = source,
                    items = list.map { HeadlineView(title = it.title, url = it.sourceUrl, publishedAt = it.publishedAt) },
                )
            }
            .sortedBy { g -> SOURCE_ORDER.indexOf(g.source).let { if (it < 0) Int.MAX_VALUE else it } }
    }

    private companion object {
        /** 헤드라인 수집 윈도(일) — 니치 매체라 딜카드(72h)보다 넉넉히 잡아 보드가 비지 않게. */
        const val RECENT_DAYS = 21L
        const val MAX_PER_SOURCE = 25
        const val TITLE_MAX = 300

        /** 보드 매체 노출 순서(CRE 전문 → 딜 전문). */
        val SOURCE_ORDER = listOf("SPI", "코어비트", "딜사이트")
    }
}
