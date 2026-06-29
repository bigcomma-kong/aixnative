package com.aixnative.marketfeed

import com.aixnative.account.EmailService
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

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
    private val emailService: EmailService,
    @Value("\${app.base-url:http://localhost:8080}") private val baseUrl: String,
) {
    private val log = LoggerFactory.getLogger(javaClass)

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
     * 발송 실패는 개별 graceful — 한 명 실패가 전체를 막지 않는다.
     */
    @Transactional(readOnly = true)
    fun broadcastLatest(): Int {
        val briefing = briefings.findTopByOrderByGeneratedAtDesc() ?: return 0
        val recipients = subscribers.findAllByActiveTrue()
        if (recipients.isEmpty()) return 0

        val topCards = cards.findAllByOrderByPublishedAtDescIdDesc(PageRequest.of(0, TOP_CARDS))
        val subject = "[aixnative] 오늘의 시장 브리핑" + (briefing.headline?.let { " — $it" } ?: "")
        var sent = 0
        for (sub in recipients) {
            val body = buildBody(briefing, topCards, sub.unsubToken)
            runCatching { emailService.sendNewsletter(sub.email, subject, body) }
                .onSuccess { sent++ }
                .onFailure { log.warn("[newsletter] 발송 실패 to={}: {}", sub.email, it.message) }
        }
        log.info("[newsletter] {}명에게 브리핑 발송", sent)
        return sent
    }

    private fun buildBody(briefing: MarketBriefing, topCards: List<MarketFeedItem>, token: String): String = buildString {
        briefing.headline?.let { appendLine(it); appendLine() }
        briefing.outlook?.let { appendLine(it); appendLine() }
        if (topCards.isNotEmpty()) {
            appendLine("오늘의 딜")
            appendLine("──────────")
            topCards.forEach { c ->
                appendLine("• ${c.title}${c.assetType?.let { " [$it]" } ?: ""}")
            }
            appendLine()
        }
        appendLine("전체 딜 보기 · AI 분석: $baseUrl")
        appendLine()
        appendLine("─".repeat(20))
        appendLine("구독 해지: $baseUrl/api/newsletter/unsubscribe?token=$token")
        appendLine("aixnative — 본 메일은 투자자문이 아닙니다.")
    }

    private companion object {
        const val TOP_CARDS = 6
    }
}
