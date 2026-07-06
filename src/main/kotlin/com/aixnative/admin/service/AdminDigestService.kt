package com.aixnative.admin.service

import com.aixnative.account.repository.UserRepository
import com.aixnative.account.service.EmailService
import com.aixnative.ai.domain.RunStatus
import com.aixnative.ai.service.AiToolRunService
import com.aixnative.analytics.repository.UserEventRepository
import com.aixnative.billing.domain.CreditReason
import com.aixnative.billing.repository.CreditLedgerRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import java.time.ZoneId

/** 다이제스트 발송 결과(트리거 응답용). */
data class DigestReport(val sent: Boolean, val to: String, val newSignups: Int, val runsToday: Int, val failedToday: Int)

/**
 * 관리자 일일 운영 다이제스트. 하루 한 번(Cloud Scheduler) 트리거되어
 * 신규가입·분석·실패·크레딧·행동 퍼널을 요약해 관리자 이메일로 보낸다.
 * "대시보드를 안 봐도 운영 상태를 파악"하는 것이 목적. SMTP 미설정 시 로그 폴백(EmailService).
 */
@Service
class AdminDigestService(
    private val users: UserRepository,
    private val runs: AiToolRunService,
    private val ledger: CreditLedgerRepository,
    private val events: UserEventRepository,
    private val emailService: EmailService,
    @Value("\${app.admin-email:}") private val adminEmail: String,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val zone = ZoneId.of("Asia/Seoul")

    @Transactional(readOnly = true)
    fun sendDailyDigest(): DigestReport {
        if (adminEmail.isBlank()) {
            log.warn("[digest] app.admin-email 미설정 — 다이제스트 미발송")
            return DigestReport(sent = false, to = "", newSignups = 0, runsToday = 0, failedToday = 0)
        }
        val today = LocalDate.now(zone)
        val startToday = today.atStartOfDay(zone).toInstant()

        val allUsers = users.findAll()
        val newSignups = allUsers.count { it.createdAt?.isAfter(startToday) == true }
        val activeToday = allUsers.count { it.lastLoginAt?.isAfter(startToday) == true }

        val runsToday = runs.listAllAdmin().filter { it.createdAt?.isAfter(startToday) == true }
        val failedToday = runsToday.count { it.status == RunStatus.FAILED }
        val byTool = runsToday.groupingBy { it.tool }.eachCount().toList().sortedByDescending { it.second }

        // 최근 원장에서 오늘분만 필터(초기 저볼륨 기준 충분). 관리자 수동 지급/차감·분석 소모 파악.
        val ledgerToday = ledger.findTop300ByOrderByIdDesc().filter { it.createdAt?.isAfter(startToday) == true }
        val grantedToday = ledgerToday.filter { it.reason == CreditReason.ADMIN_ADJUST && it.delta > 0 }.sumOf { it.delta }
        val spentToday = -ledgerToday.filter { it.reason == CreditReason.AI_ANALYSIS }.sumOf { it.delta }
        val purchasedToday = ledgerToday.filter { it.reason == CreditReason.PURCHASE }.sumOf { it.delta }

        val funnelToday = events.funnelSince(startToday).sortedByDescending { it.cnt }

        val body = buildString {
            appendLine("AixNative 운영 다이제스트 - $today")
            appendLine("=".repeat(40))
            appendLine()
            appendLine("[사용자]")
            appendLine("- 전체: ${allUsers.size}명 (인증 ${allUsers.count { it.emailVerified }}명)")
            appendLine("- 오늘 신규 가입: ${newSignups}명")
            appendLine("- 오늘 접속(활성): ${activeToday}명")
            appendLine()
            appendLine("[AI 분석 (오늘)]")
            appendLine("- 실행: ${runsToday.size}건 (성공 ${runsToday.size - failedToday} / 실패 $failedToday)")
            if (byTool.isNotEmpty()) {
                byTool.forEach { (tool, n) -> appendLine("  · $tool: ${n}건") }
            }
            appendLine()
            appendLine("[크레딧 (오늘)]")
            appendLine("- 관리자 수동 지급: +$grantedToday")
            appendLine("- 결제 충전: +$purchasedToday")
            appendLine("- 분석 소모: -$spentToday")
            appendLine()
            appendLine("[행동 퍼널 (오늘)]")
            if (funnelToday.isEmpty()) {
                appendLine("- 기록 없음")
            } else {
                funnelToday.forEach { appendLine("- ${it.event}: ${it.cnt}") }
            }
            appendLine()
            if (failedToday > 0) {
                appendLine("주의: 오늘 분석 실패 ${failedToday}건 - 관리자 콘솔에서 상세 확인 권장.")
            }
            appendLine()
            appendLine("크레딧 요청 메일은 admin@aixnative.com 수신함을 확인하세요.")
        }

        emailService.sendNewsletter(adminEmail, "[AixNative] 운영 다이제스트 $today", body)
        log.info("[digest] 발송(to={}) 신규={} 분석={} 실패={}", adminEmail, newSignups, runsToday.size, failedToday)
        return DigestReport(sent = true, to = adminEmail, newSignups = newSignups, runsToday = runsToday.size, failedToday = failedToday)
    }
}
