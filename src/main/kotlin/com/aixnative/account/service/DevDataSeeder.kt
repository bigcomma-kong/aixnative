package com.aixnative.account.service

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import com.aixnative.account.repository.UserRepository
import com.aixnative.account.web.SignupRequest

/**
 * dev(h2) 전용 데이터 시드. 앱 기동 시 관리자 계정이 없으면 생성한다.
 * 운영(PostgreSQL = 기본 프로파일)에서는 절대 동작하지 않음(@Profile("h2")).
 *
 * 계정: app.admin-email (기본 admin@aixnative.com) / app.dev-seed.admin-password (기본 admin123).
 * admin-email 로 가입하면 AuthService 가 자동으로 ADMIN 권한을 부여한다.
 */
@Component
@Profile("h2")
class DevDataSeeder(
    private val users: UserRepository,
    private val authService: AuthService,
    @Value("\${app.admin-email:admin@aixnative.com}") private val adminEmail: String,
    @Value("\${app.dev-seed.admin-password:admin123}") private val adminPassword: String,
) : ApplicationRunner {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun run(args: ApplicationArguments) {
        if (adminEmail.isBlank() || users.existsByEmail(adminEmail)) return
        authService.signup(SignupRequest(adminEmail, adminPassword))
        log.warn("[dev-seed] 관리자 계정 생성: {} / (app.dev-seed.admin-password) - dev h2 전용", adminEmail)
    }
}
