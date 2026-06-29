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

        send(toEmail, subject, body, "인증")
    }

    fun sendPasswordReset(toEmail: String, resetUrl: String) {
        val subject = "[aixnative] 비밀번호 재설정 안내"
        val body = buildString {
            appendLine("비밀번호 재설정을 요청하셨습니다. 아래 링크를 클릭해 새 비밀번호를 설정해 주세요.")
            appendLine()
            appendLine(resetUrl)
            appendLine()
            appendLine("링크는 일정 시간 후 만료되며 한 번만 사용할 수 있습니다.")
            appendLine("본인이 요청하지 않았다면 이 메일을 무시하셔도 됩니다. 비밀번호는 변경되지 않습니다.")
        }
        send(toEmail, subject, body, "비밀번호 재설정")
    }

    /** 마켓 브리핑 뉴스레터 발송(무료). SMTP 미설정 시 로그 폴백, 실패해도 예외 미전파. */
    fun sendNewsletter(toEmail: String, subject: String, body: String) =
        send(toEmail, subject, body, "뉴스레터")

    /** SMTP 미설정 시 링크를 로그로 남기고, 설정 시 발송. 어떤 경우에도 호출자에게 예외를 던지지 않는다. */
    private fun send(toEmail: String, subject: String, body: String, kind: String) {
        val sender = mailSenderProvider.ifAvailable
        if (sender == null) {
            // SMTP 미설정 — 운영 전이거나 dev. 링크를 로그로 남겨 수동 처리/디버깅 가능.
            log.warn("[email] SMTP 미설정 — {} 메일 본문 로그 출력 (to={}):\n{}", kind, toEmail, body)
            return
        }
        val msg = SimpleMailMessage().apply {
            setFrom(from)
            setTo(toEmail)
            setSubject(subject)
            setText(body)
        }
        runCatching { sender.send(msg) }
            .onFailure { log.error("[email] {} 메일 발송 실패 (to={}): {}", kind, toEmail, it.message) }
    }
}
