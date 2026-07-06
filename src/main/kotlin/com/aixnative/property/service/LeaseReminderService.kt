package com.aixnative.property.service

import com.aixnative.account.repository.UserRepository
import com.aixnative.account.service.EmailService
import com.aixnative.property.domain.Lease
import com.aixnative.property.domain.LeaseAnalytics
import com.aixnative.property.domain.LeaseEventType
import com.aixnative.property.domain.LeaseReminderSent
import com.aixnative.property.repository.BuildingRepository
import com.aixnative.property.repository.LeaseReminderSentRepository
import com.aixnative.property.repository.LeaseRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** 리마인더 배치 실행 요약(크론 응답). */
data class ReminderRunReport(
    val scannedLeases: Int,
    val dueEvents: Int,
    val emailsSent: Int,
    val skippedDuplicates: Int,
)

/** 한 임대차의 임박 이벤트(내부용). */
private data class DueEvent(
    val lease: Lease,
    val type: LeaseEventType,
    val dueDate: LocalDate,
    val daysUntil: Long,
    val label: String,
)

/**
 * 임대차 리마인더 배치 - Cloud Scheduler 가 토큰 엔드포인트를 깨우면, 만기/인상/렌트프리 종료가
 * lead-days 이내로 다가온 임대차를 스캔해 소유자에게 이메일 다이제스트를 보낸다.
 * (lease, event, dueDate) 단위로 [LeaseReminderSent] 에 기록해 중복 발송을 막는다(멱등).
 * TenantContext 없이 도는 배치라 각 임대차의 owner/tenant 로 스코프해 처리한다.
 */
@Service
class LeaseReminderService(
    private val leaseRepository: LeaseRepository,
    private val buildingRepository: BuildingRepository,
    private val reminderSentRepository: LeaseReminderSentRepository,
    private val userRepository: UserRepository,
    private val emailService: EmailService,
    private val props: PropertyProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional
    fun runReminders(): ReminderRunReport {
        val today = LocalDate.now(SEOUL)
        val leadDays = props.reminderLeadDays
        val allLeases = leaseRepository.findAll()

        // 임박 이벤트를 소유자별로 모은다(소유자당 다이제스트 1통).
        val byOwner = LinkedHashMap<Long, MutableList<DueEvent>>()
        var dueCount = 0
        var skipped = 0
        for (lease in allLeases) {
            for (ev in dueEvents(lease, today, leadDays)) {
                dueCount++
                val leaseId = lease.id ?: continue
                if (reminderSentRepository.existsByLeaseIdAndEventTypeAndDueDate(leaseId, ev.type, ev.dueDate)) {
                    skipped++
                    continue
                }
                byOwner.getOrPut(lease.ownerUserId) { mutableListOf() }.add(ev)
            }
        }

        var sent = 0
        for ((ownerUserId, events) in byOwner) {
            val user = userRepository.findById(ownerUserId).orElse(null)
            if (user == null) {
                log.warn("[reminder] 소유자(userId={}) 없음 - 스킵", ownerUserId)
                continue
            }
            val html = buildDigestHtml(events, today)
            emailService.sendNewsletterHtml(user.email, REMINDER_SUBJECT, html)
            sent++
            // 발송 시도 후 멱등 기록(재실행 시 중복 방지). EmailService 는 fail-soft.
            for (ev in events) {
                val leaseId = ev.lease.id ?: continue
                reminderSentRepository.save(
                    LeaseReminderSent(
                        leaseId = leaseId,
                        eventType = ev.type,
                        dueDate = ev.dueDate,
                        sentAt = Instant.now(),
                    ).apply { this.tenantId = ev.lease.tenantId; this.ownerUserId = ev.lease.ownerUserId },
                )
            }
        }

        log.info(
            "[reminder] 스캔 {} 건, 임박 {} 건, 발송 {} 통, 중복 스킵 {} 건",
            allLeases.size, dueCount, sent, skipped,
        )
        return ReminderRunReport(allLeases.size, dueCount, sent, skipped)
    }

    /** 임대차의 임박 이벤트(0 <= D-day <= leadDays). 일정 계산은 [LeaseAnalytics] 를 공유. */
    private fun dueEvents(lease: Lease, today: LocalDate, leadDays: Long): List<DueEvent> =
        LeaseAnalytics.events(lease, today)
            .filter { it.daysUntil in 0..leadDays }
            .map { DueEvent(lease, it.type, it.dueDate, it.daysUntil, EVENT_LABEL[it.type] ?: it.type.name) }

    private fun buildDigestHtml(events: List<DueEvent>, today: LocalDate): String {
        val rows = events.sortedBy { it.dueDate }.joinToString("") { ev ->
            val building = buildingRepository.findById(ev.lease.buildingId).orElse(null)
            val bname = esc(building?.name ?: "-")
            "<tr>" +
                "<td style='padding:8px 10px;border-bottom:1px solid #eee'>${ev.dueDate} (D-${ev.daysUntil})</td>" +
                "<td style='padding:8px 10px;border-bottom:1px solid #eee'>${esc(ev.label)}</td>" +
                "<td style='padding:8px 10px;border-bottom:1px solid #eee'>$bname · ${esc(ev.lease.tenantName)}</td>" +
                "</tr>"
        }
        return "<div style='font-family:-apple-system,Malgun Gothic,sans-serif;color:#1a1a2e;max-width:640px'>" +
            "<h2 style='font-size:18px'>임대차 리마인더 - ${events.size}건 임박</h2>" +
            "<p style='color:#555;font-size:14px'>기준일 $today. ${props.reminderLeadDays}일 이내로 다가온 임대 일정입니다.</p>" +
            "<table style='width:100%;border-collapse:collapse;font-size:14px'>" +
            "<thead><tr style='background:#f4f4f8'>" +
            "<th style='text-align:left;padding:8px 10px'>일자</th>" +
            "<th style='text-align:left;padding:8px 10px'>이벤트</th>" +
            "<th style='text-align:left;padding:8px 10px'>건물 · 임차인</th>" +
            "</tr></thead><tbody>$rows</tbody></table>" +
            "<p style='color:#888;font-size:12px;margin-top:16px'>aixnative 자산관리(PM) · 이 메일은 임대 일정 알림입니다.</p>" +
            "</div>"
    }

    private fun esc(s: String): String = buildString(s.length) {
        for (c in s) when (c) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            '\'' -> append("&#39;")
            else -> append(c)
        }
    }

    private companion object {
        val SEOUL: ZoneId = ZoneId.of("Asia/Seoul")
        const val REMINDER_SUBJECT = "[aixnative] 임대차 리마인더 - 다가오는 만기·인상 일정"

        /** 이벤트 유형 → 리마인더 라벨. */
        val EVENT_LABEL: Map<LeaseEventType, String> = mapOf(
            LeaseEventType.EXPIRY to "계약 만기",
            LeaseEventType.ESCALATION to "임대료 인상 예정",
            LeaseEventType.RENT_FREE_END to "렌트프리 종료",
        )
    }
}
