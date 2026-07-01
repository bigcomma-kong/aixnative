package com.aixnative.marketfeed.service

import com.aixnative.ai.service.MistralClient
import com.aixnative.marketfeed.domain.MarketBriefing
import com.aixnative.marketfeed.repository.MarketBriefingRepository
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import com.aixnative.marketfeed.domain.NewsItem

/**
 * 마켓 브리핑 합성 — 무료 [MistralClient] **직접** 호출(Claude 폴백 격리, 과금 0).
 * 키 미설정 시 [generate] 가 null 반환(graceful, 딜 카드 수집은 그대로 진행).
 *
 * 입력 = 수집·필터된 기사 풀, 출력 = 헤드라인·전망·토픽섹션·워치리스트·리스크(JSON) 1건 저장.
 */
@Component
class MarketBriefingGenerator(
    private val mistralClient: MistralClient,
    private val repository: MarketBriefingRepository,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** @return 사용한 제공자명(예: "Mistral") 또는 null(무료 AI 미설정/실패로 생략). */
    @Transactional
    fun generate(articles: List<NewsItem>): String? {
        if (!mistralClient.isConfigured()) {
            log.info("[briefing] 무료 AI(Mistral) 미설정 — 브리핑 생략(딜 카드는 적재됨)")
            return null
        }
        val sample = articles.take(MAX_ARTICLES)
        val raw = mistralClient.complete(buildPrompt(sample))
        val json = extractJson(raw) ?: run {
            log.warn("[briefing] 응답에서 JSON 추출 실패")
            return null
        }
        val node = objectMapper.readTree(json)
        val briefing = MarketBriefing(
            briefingDate = LocalDate.now(),
            headline = node.path("headline").asText("").ifBlank { null }?.take(500),
            outlook = node.path("outlook").asText("").ifBlank { null },
            sectionsJson = node.path("sections").takeIf { it.isArray }?.toString() ?: "[]",
            watchlistJson = node.path("watchlist").takeIf { it.isArray }?.toString() ?: "[]",
            risksJson = node.path("risks").takeIf { it.isArray }?.toString() ?: "[]",
            articleCount = articles.size,
            aiProvider = "Mistral",
        )
        repository.save(briefing)
        log.info("[briefing] 생성 완료 (기사 {}건, Mistral)", articles.size)
        return "Mistral"
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
