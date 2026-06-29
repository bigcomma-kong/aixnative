package com.aixnative.marketfeed.ingest

import com.aixnative.marketfeed.MarketFeedItem
import com.aixnative.marketfeed.MarketFeedRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * 시장 인텔리전스 수집 오케스트레이터. 한 번의 실행이:
 *  1) 공개 RSS·구글뉴스에서 기사 수집(결정론·무료),
 *  2) 정규화·중복제거·노이즈 필터·최근시간 필터,
 *  3) 딜 카드([MarketFeedItem])로 적재(DB 중복은 dedup_key 로 차단),
 *  4) (선택) 무료 AI 마켓 브리핑 합성 — [briefingGenerator] 가 있고 키가 설정된 경우만.
 *
 * Claude 미사용(과금 분석은 사용자 클릭 시에만). 브리핑은 무료 제공자에 격리.
 */
@Service
class MarketFeedIngestService(
    private val collector: RssNewsCollector,
    private val repository: MarketFeedRepository,
    private val props: MarketFeedProperties,
    private val briefingGenerator: MarketBriefingGenerator,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * @param purge true 면 자동 수집 카드를 모두 지우고 새로 채운다(오염 데이터 1회성 정리).
     *   기본 false — 스케줄 실행은 누적(이력 보존). 수동 등록 ADMIN 카드는 purge 영향 없음.
     */
    @Transactional
    fun ingest(purge: Boolean = false): IngestReport {
        val errors = ArrayList<String>()

        if (purge) {
            val removed = repository.deleteByDedupKeyIsNotNull()
            log.info("[ingest] purge=true — 자동 카드 {}건 삭제 후 재수집", removed)
        }

        val raw = runCatching { collector.collect() }
            .onFailure { errors += "수집 실패: ${it.message}" }
            .getOrDefault(emptyList())

        val cutoff = Instant.now().minus(props.recentHours, ChronoUnit.HOURS)

        // 인-배치 중복제거(정규화 링크) → 최근시간 + 관련성 필터 → 발행 최신순.
        val deduped = LinkedHashMap<String, NewsItem>()
        for (item in raw) {
            val key = NewsTextFilter.normalizeLink(item.link)
            if (key.isNotBlank()) deduped.putIfAbsent(key, item)
        }
        val filtered = deduped.values
            .filter { it.publishedAt == null || it.publishedAt.isAfter(cutoff) }
            .filter { NewsTextFilter.isRelevant(it) }
            .sortedByDescending { it.publishedAt ?: Instant.EPOCH }

        // 소스(+섹터)당 상한 + 전체 상한. 구글뉴스는 섹터별로 캡을 나눠 다양성 확보.
        val perSource = HashMap<String, Int>()
        val capped = filtered.filter {
            val capKey = "${it.source}:${it.sectorHint ?: ""}"
            val n = perSource.getOrDefault(capKey, 0)
            if (n >= props.maxPerSource) false else { perSource[capKey] = n + 1; true }
        }.take(props.maxCards)

        // DB 중복제거: 이미 있는 dedup_key 제외 후 저장.
        val keys = capped.map { NewsTextFilter.normalizeLink(it.link) }
        val existing = if (keys.isEmpty()) emptySet() else
            repository.findByDedupKeyIn(keys).mapNotNull { it.dedupKey }.toHashSet()

        var inserted = 0
        var skipped = 0
        for (item in capped) {
            val key = NewsTextFilter.normalizeLink(item.link)
            if (key in existing) { skipped++; continue }
            repository.save(item.toEntity(key))
            inserted++
        }
        log.info("[ingest] fetched={} filtered={} inserted={} skipped={}", raw.size, capped.size, inserted, skipped)

        // 마켓 브리핑(무료 AI) — graceful.
        var briefingDone = false
        var briefingProvider: String? = null
        if (props.briefingEnabled && filtered.isNotEmpty()) {
            runCatching { briefingGenerator.generate(filtered) }
                .onSuccess { res -> briefingDone = res != null; briefingProvider = res }
                .onFailure { errors += "브리핑 실패: ${it.message}"; log.warn("[ingest] 브리핑 실패", it) }
        }

        return IngestReport(
            fetched = raw.size,
            afterFilter = capped.size,
            inserted = inserted,
            skippedDuplicate = skipped,
            briefingGenerated = briefingDone,
            briefingProvider = briefingProvider,
            errors = errors,
        )
    }

    private fun NewsItem.toEntity(dedupKey: String): MarketFeedItem =
        MarketFeedItem(
            title = title.take(TITLE_MAX),
            summary = summary.ifBlank { null },
            assetType = NewsTextFilter.classifyAssetType(this),
            location = NewsTextFilter.guessLocation(this),
            sourceText = listOf(title, summary).filter { it.isNotBlank() }.joinToString("\n").take(SOURCE_TEXT_MAX),
            sourceUrl = link,
            publishedAt = publishedAt ?: Instant.now(),
            origin = source,
            dedupKey = dedupKey,
        )

    private companion object {
        const val TITLE_MAX = 200
        const val SOURCE_TEXT_MAX = 4000
    }
}
