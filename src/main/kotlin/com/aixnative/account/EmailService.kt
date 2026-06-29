package com.aixnative.account

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Value
import org.springframework.mail.SimpleMailMessage
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.stereotype.Service

/**
 * Sends transactional email. Uses Spring [JavaMailSender] when SMTP is configured
 * (env `SPRING_MAIL_HOST`/`SPRING_MAIL_USERNAME`/…); otherwise logs the link so
 * dev/staging keeps working without a provider. Never throws to the caller — a
 * failed send must not break signup.
 */
@Service
class EmailService(
    private val mailSenderProvider: ObjectProvider<JavaMailSender>,
    @Value("\${app.mail.from:no-reply@aixnative.com}") private val from: String,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun sendVerification(toEmail: String, verifyUrl: String) {
        val subject = "[aixnative] 이메일 인증을 완료해 주세요"
        val body = buildString {
            appendLine("aixnative 가입을 완료하려면 아래 링크를 클릭해 이메일을 인증해 주세요.")
            appendLine()
            appendLine(verifyUrl)
            appendLine()
            appendLine("인증을 마치면 무료 분석 크레딧이 지급됩니다. 링크는 일정 시간 후 만료됩니다.")
            appendLine("본인이 요청하지 않았다면 이 메일을 무시하셔도 됩니다.")
        }

        val sender = mailSenderProvider.ifAvailable
        if (sender == null) {
            // SMTP 미설정 — 운영 전이거나 dev. 링크를 로그로 남겨 수동 인증/디버깅 가능.
            log.warn("[email] SMTP 미설정 — 인증 링크 로그 출력 (to={}): {}", toEmail, verifyUrl)
            return
        }
        val msg = SimpleMailMessage().apply {
            setFrom(from)
            setTo(toEmail)
            setSubject(subject)
            setText(body)
        }
        runCatching { sender.send(msg) }
            .onFailure { log.error("[email] 인증 메일 발송 실패 (to={}): {}", toEmail, it.message) }
    }
}
