package com.aixnative.marketfeed.service

import com.aixnative.marketfeed.domain.MarketFeedItem
import com.aixnative.marketfeed.repository.MarketFeedRepository
import org.slf4j.LoggerFactory
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.temporal.ChronoUnit
import com.aixnative.marketfeed.domain.IngestReport
import com.aixnative.marketfeed.domain.NewsItem

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
    private val newsletterService: com.aixnative.marketfeed.service.NewsletterService,
    private val headlineService: com.aixnative.headline.service.HeadlineService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * @param purge true 면 자동 수집 카드를 모두 지우고 새로 채운다(오염 데이터 1회성 정리).
     *   기본 false — 스케줄 실행은 누적(이력 보존). 수동 등록 ADMIN 카드는 purge 영향 없음.
     * @param notify true 면 브리핑 생성 시 구독자에게 메일 발송(스케줄 경로). 관리자 테스트는 기본 false.
     */
    @Transactional
    fun ingest(purge: Boolean = false, notify: Boolean = false): IngestReport {
        val errors = ArrayList<String>()

        if (purge) {
            val removed = repository.deleteByDedupKeyIsNotNull()
            log.info("[ingest] purge=true — 자동 카드 {}건 삭제 후 재수집", removed)
        }

        val collection = runCatching { collector.collect() }
            .onFailure { errors += "수집 실패: ${it.message}" }
            .getOrDefault(RssNewsCollector.CollectionResult.EMPTY)
        val raw = collection.items

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

        // 업계 헤드라인 수집(별도 저장소·화면). 딜 카드와 무관 — 실패해도 전체 인제스트는 계속.
        val headlinesInserted = runCatching { headlineService.ingest(purge) }
            .onFailure { errors += "헤드라인 수집 실패: ${it.message}"; log.warn("[ingest] 헤드라인 수집 실패", it) }
            .getOrDefault(0)

        // 마켓 브리핑(무료 AI) — graceful. 입력 = 단일 fetch(filtered)가 아니라 **누적 DB 최근 풀**(cutoff 이내 저장 카드).
        // → 하루 수집이 스로틀나도 어제까지 쌓인 카드가 남아 분석 건수가 15건으로 무너지지 않는다.
        val briefingPool = repository
            .findByPublishedAtAfterOrderByPublishedAtDescIdDesc(cutoff, PageRequest.of(0, BRIEFING_POOL_MAX))
            .map { it.toNewsItem() }
        var briefingDone = false
        var briefingProvider: String? = null
        if (props.briefingEnabled && briefingPool.isNotEmpty()) {
            runCatching { briefingGenerator.generate(briefingPool) }
                .onSuccess { res -> briefingDone = res != null; briefingProvider = res }
                .onFailure { errors += "브리핑 실패: ${it.message}"; log.warn("[ingest] 브리핑 실패", it) }
        }

        // 구글뉴스 스로틀(조용한 축소) 가시화 — 빈응답이 있으면 관리자 응답에 한 줄로 노출.
        if (collection.googleQueriesThin > 0) {
            errors += "구글뉴스 빈응답/실패 ${collection.googleQueriesThin}/${collection.googleQueriesTotal} (스로틀 의심)"
        }

        // 구독자 메일 발송(무료 재방문 유도) — 스케줄 경로(notify=true)에서 브리핑이 생성됐을 때만.
        if (notify && briefingDone) {
            runCatching { newsletterService.broadcastLatest() }
                .onFailure { errors += "메일 발송 실패: ${it.message}"; log.warn("[ingest] 뉴스레터 발송 실패", it) }
        }

        return IngestReport(
            fetched = raw.size,
            afterFilter = capped.size,
            inserted = inserted,
            skippedDuplicate = skipped,
            headlinesInserted = headlinesInserted,
            briefingPoolSize = briefingPool.size,
            googleQueriesTotal = collection.googleQueriesTotal,
            googleQueriesThin = collection.googleQueriesThin,
            briefingGenerated = briefingDone,
            briefingProvider = briefingProvider,
            errors = errors,
        )
    }

    /** 저장된 카드 → 브리핑 입력용 NewsItem(누적 풀 분석). 링크·출처는 프롬프트 표기에만 쓰인다. */
    private fun MarketFeedItem.toNewsItem(): NewsItem =
        NewsItem(
            title = title,
            summary = summary ?: "",
            link = sourceUrl ?: "",
            publishedAt = publishedAt,
            source = origin ?: "DB",
        )

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
        /** 브리핑 분석용 누적 최근 풀 상한(최신순). 프롬프트엔 이 중 앞부분만 투입되지만 '종합 커버리지'로 표기. */
        const val BRIEFING_POOL_MAX = 150
    }
}
