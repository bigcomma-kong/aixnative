package com.aixnative.marketfeed.service

import com.aixnative.ai.service.ClaudeClient
import com.aixnative.marketfeed.domain.MarketBriefing
import com.aixnative.marketfeed.repository.MarketBriefingRepository
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import com.aixnative.marketfeed.domain.NewsItem

/**
 * 마켓 브리핑 합성 — [ClaudeClient] **직접** 호출(라우터 미경유).
 * 사용자 크레딧이 걸리지 않는 배치라 저렴한 배치 모델([ClaudeClient.completeBatch])을 쓰고,
 * 키 미설정 시 [generate] 가 null 반환(graceful, 딜 카드 수집은 그대로 진행).
 *
 * 입력 = 수집·필터된 기사 풀, 출력 = 헤드라인·전망·토픽섹션·워치리스트·리스크(JSON) 1건 저장.
 *
 * ⚠ 트랜잭션은 반드시 **REQUIRES_NEW** — 호출자([MarketFeedIngestService.ingest])의 트랜잭션에
 * 참여하면 여기서 난 예외가 그 트랜잭션을 rollback-only 로 마킹하고, 호출자가 runCatching 으로
 * 삼켜도 커밋 시점에 UnexpectedRollbackException 이 터지면서 **그날 수집한 기사까지 통째로 롤백**된다.
 * (2026-09-06~09-10 브리핑·수집 동시 유실의 실제 원인. 브리핑 실패는 브리핑만 실패해야 한다.)
 */
@Component
class MarketBriefingGenerator(
    private val claudeClient: ClaudeClient,
    private val repository: MarketBriefingRepository,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** @return 사용한 제공자명("Claude") 또는 null(AI 미설정/응답 파싱 실패로 생략). */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun generate(articles: List<NewsItem>): String? {
        if (!claudeClient.isConfigured()) {
            log.info("[briefing] Claude 미설정 — 브리핑 생략(딜 카드는 적재됨)")
            return null
        }
        val sample = articles.take(MAX_ARTICLES)
        val raw = claudeClient.completeBatch(buildPrompt(sample))
        val json = extractJson(raw) ?: run {
            log.warn("[briefing] 응답에서 JSON 추출 실패")
            return null
        }
        val node = objectMapper.readTree(json)
        val briefing = MarketBriefing(
            briefingDate = LocalDate.now(java.time.ZoneId.of("Asia/Seoul")), // 서버 UTC가 아닌 KST 영업일 기준
            headline = node.path("headline").asText("").ifBlank { null }?.take(500),
            outlook = node.path("outlook").asText("").ifBlank { null },
            sectionsJson = node.path("sections").takeIf { it.isArray }?.toString() ?: "[]",
            watchlistJson = node.path("watchlist").takeIf { it.isArray }?.toString() ?: "[]",
            risksJson = node.path("risks").takeIf { it.isArray }?.toString() ?: "[]",
            articleCount = articles.size,
            aiProvider = "Claude",
        )
        repository.save(briefing)
        log.info("[briefing] 생성 완료 (기사 {}건, Claude)", articles.size)
        return "Claude"
    }

    private fun buildPrompt(articles: List<NewsItem>): String {
        val lines = articles.mapIndexed { i, a ->
            "${i + 1}. [${a.source}] ${a.title}${if (a.summary.isNotBlank()) " — ${a.summary.take(200)}" else ""}"
        }.joinToString("\n")
        return """
            당신은 상업용 부동산(CRE) 시장 애널리스트입니다. 아래 한국 부동산 뉴스 헤드라인들을 종합해
            기관 투자자용 일일 시장 브리핑을 작성하세요. 추측을 보태지 말고 제공된 기사 범위에서만 요약하세요.

            반드시 아래 스키마의 **JSON 객체 하나만** 출력하세요(코드블록·설명 금지):
            {
              "headline": "오늘 시장을 한 줄로 (80자 이내)",
              "outlook": "단기 전망 2~3문장",
              "sections": [{"topic":"주제","summary":"요약","impact":"투자 시사점"}],
              "watchlist": [{"item":"주목 대상","why":"이유"}],
              "risks": [{"signal":"리스크 신호","severity":"LOW|MEDIUM|HIGH","mitigation":"대응"}]
            }
            sections 3~5개, watchlist 2~4개, risks 1~3개. 모든 값은 한국어.

            <기사>
            $lines
            </기사>
        """.trimIndent()
    }

    /** 코드펜스/잡텍스트를 걷어내고 첫 '{' ~ 마지막 '}' 구간을 JSON 으로 추출. */
    private fun extractJson(raw: String): String? {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        return if (start in 0 until end) raw.substring(start, end + 1) else null
    }

    private companion object {
        const val MAX_ARTICLES = 30
    }
}
