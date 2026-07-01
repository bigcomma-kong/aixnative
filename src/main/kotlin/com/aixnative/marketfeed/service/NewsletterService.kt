package com.aixnative.marketfeed.service

import com.aixnative.account.service.EmailService
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import com.aixnative.marketfeed.domain.MarketBriefing
import com.aixnative.marketfeed.domain.MarketFeedItem
import com.aixnative.marketfeed.domain.NewsSubscriber
import com.aixnative.marketfeed.domain.NewsletterEmail
import com.aixnative.marketfeed.domain.NewsletterSendLog
import com.aixnative.marketfeed.repository.MarketBriefingRepository
import com.aixnative.marketfeed.repository.MarketFeedRepository
import com.aixnative.marketfeed.repository.NewsSubscriberRepository
import com.aixnative.marketfeed.repository.NewsletterSendLogRepository
import com.aixnative.marketfeed.web.NewsSubscriberView
import com.aixnative.marketfeed.web.NewsletterSendLogView

/**
 * 마켓 브리핑 메일 구독(무료). 구독은 로그인 사용자 본인 이메일로, 해지는 메일 푸터 토큰 링크.
 * 매 수집 후(스케줄러 경로) 최신 브리핑 + 상위 딜을 활성 구독자에게 발송 → 재방문 유도.
 * 발송 자체는 무료(과금은 사용자가 사이트에서 분석을 돌릴 때만).
 */
@Service
class NewsletterService(
    private val subscribers: NewsSubscriberRepository,
    private val briefings: MarketBriefingRepository,
    private val cards: MarketFeedRepository,
    private val sendLog: NewsletterSendLogRepository,
    private val emailService: EmailService,
    private val objectMapper: ObjectMapper,
    @Value("\${app.base-url:http://localhost:8080}") private val baseUrl: String,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val dateFmt = DateTimeFormatter.ofPattern("yyyy년 M월 d일", Locale.KOREAN)

    /** 구독(idempotent). 이미 있으면 active=true 로 되살린다. */
    @Transactional
    fun subscribe(email: String) {
        val normalized = email.trim().lowercase()
        val existing = subscribers.findByEmail(normalized)
        if (existing != null) {
            existing.active = true
        } else {
            subscribers.save(NewsSubscriber(email = normalized, unsubToken = UUID.randomUUID().toString().replace("-", "")))
        }
    }

    @Transactional
    fun unsubscribeByEmail(email: String) {
        subscribers.findByEmail(email.trim().lowercase())?.let { it.active = false }
    }

    /** 메일 푸터 토큰 1클릭 해지. 성공 여부 반환. */
    @Transactional
    fun unsubscribeByToken(token: String): Boolean {
        val sub = subscribers.findByUnsubToken(token) ?: return false
        sub.active = false
        return true
    }

    @Transactional(readOnly = true)
    fun isSubscribed(email: String): Boolean =
        subscribers.findByEmail(email.trim().lowercase())?.active == true

    /**
     * 최신 브리핑 + 상위 딜을 활성 구독자 전원에게 발송. 구독자/브리핑 없으면 0.
     * 발송 실패는 개별 graceful — 한 명 실패가 전체를 막지 않는다. 발송 건마다 로그를 남긴다.
     */
    @Transactional
    fun broadcastLatest(): Int {
        val briefing = briefings.findTopByOrderByGeneratedAtDesc() ?: return 0
        val recipients = subscribers.findAllByActiveTrue()
        if (recipients.isEmpty()) return 0

        val topCards = cards.findAllByOrderByPublishedAtDescIdDesc(PageRequest.of(0, TOP_CARDS))
        val subject = subjectFor(briefing)
        var sent = 0
        for (sub in recipients) {
            val unsubUrl = "$baseUrl/api/newsletter/unsubscribe?token=${sub.unsubToken}"
            val html = buildHtml(briefing, topCards, greetingOf(sub.email), unsubUrl)
            val ok = runCatching { emailService.sendNewsletterHtml(sub.email, subject, html) }
                .onFailure { log.warn("[newsletter] 발송 실패 to={}: {}", sub.email, it.message) }
                .isSuccess
            if (ok) sent++
            sendLog.save(
                NewsletterSendLog(
                    email = sub.email,
                    subject = subject.take(300),
                    status = if (ok) "SENT" else "FAILED",
                    briefingId = briefing.id,
                ),
            )
        }
        log.info("[newsletter] {}/{}명 발송", sent, recipients.size)
        return sent
    }

    /** 관리자 — 구독자 전체(최신 가입순). */
    @Transactional(readOnly = true)
    fun listSubscribers(): List<NewsSubscriberView> =
        subscribers.findAll().sortedByDescending { it.id }.map {
            NewsSubscriberView(it.email, it.active, it.createdAt)
        }

    /** 관리자 — 활성 구독자 수. */
    @Transactional(readOnly = true)
    fun activeCount(): Long = subscribers.countByActiveTrue()

    /** 관리자 — 최근 발송 로그. */
    @Transactional(readOnly = true)
    fun recentSendLog(limit: Int): List<NewsletterSendLogView> =
        sendLog.findAllByOrderBySentAtDescIdDesc(PageRequest.of(0, limit.coerceIn(1, 500)))
            .map { NewsletterSendLogView(it.email, it.subject, it.status, it.sentAt) }

    /**
     * 관리자 — 최신 브리핑으로 만든 뉴스레터 HTML 미리보기(발송 없음). 브리핑 없으면 null.
     * 구독취소 링크는 더미("#"), 인사말은 "미리보기".
     */
    @Transactional(readOnly = true)
    fun previewHtml(): String? {
        val briefing = briefings.findTopByOrderByGeneratedAtDesc() ?: return null
        val topCards = cards.findAllByOrderByPublishedAtDescIdDesc(PageRequest.of(0, TOP_CARDS))
        return buildHtml(briefing, topCards, "미리보기", "#")
    }

    /**
     * 관리자 — 지정 주소로 최신 브리핑 1건 테스트 발송(구독 여부 무관, 구독자/로그 영향 없음).
     * 브리핑이 있으면 true. 인사말은 이메일 앞부분, 구독취소는 더미.
     */
    @Transactional(readOnly = true)
    fun sendTest(toEmail: String): Boolean {
        val briefing = briefings.findTopByOrderByGeneratedAtDesc() ?: return false
        val topCards = cards.findAllByOrderByPublishedAtDescIdDesc(PageRequest.of(0, TOP_CARDS))
        val html = buildHtml(briefing, topCards, greetingOf(toEmail), "#")
        emailService.sendNewsletterHtml(toEmail, subjectFor(briefing) + " (테스트)", html)
        return true
    }

    private fun subjectFor(briefing: MarketBriefing): String =
        "[AixNative] 오늘의 시장 브리핑" + (briefing.headline?.let { " — $it" } ?: "")

    /** 인사말 — 이름 컬럼이 없으므로 전체 이메일 주소를 그대로 사용(앞부분만 X). */
    private fun greetingOf(email: String): String = email.trim().ifBlank { "구독자" }

    /** 브리핑 엔티티 + 딜카드 → 인라인 CSS HTML. sections/watchlist/risks JSON 을 파싱해 모두 렌더. */
    private fun buildHtml(
        briefing: MarketBriefing,
        topCards: List<MarketFeedItem>,
        greetingName: String,
        unsubUrl: String,
    ): String {
        val dateLabel = briefing.briefingDate.format(dateFmt)
        return NewsletterEmail.render(
            dateLabel = dateLabel,
            greetingName = greetingName,
            headline = briefing.headline,
            outlook = briefing.outlook,
            sections = parseList(briefing.sectionsJson),
            watchlist = parseList(briefing.watchlistJson),
            risks = parseList(briefing.risksJson),
            articleCount = briefing.articleCount,
            topCards = topCards,
            appUrl = baseUrl,
            unsubUrl = unsubUrl,
        )
    }

    private inline fun <reified T> parseList(json: String?): List<T> {
        if (json.isNullOrBlank()) return emptyList()
        return runCatching { objectMapper.readValue(json, object : TypeReference<List<T>>() {}) }
            .getOrDefault(emptyList())
    }

    private companion object {
        const val TOP_CARDS = 6
    }
}
