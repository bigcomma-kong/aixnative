package com.aixnative.common.config

import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Flyway 부팅 전략 - migrate 전에 repair 를 먼저 수행한다.
 *
 * repair 는 이미 적용된 마이그레이션의 저장된 체크섬을 현재 파일과 재동기화하고, 실패로 기록된
 * 항목을 정리한다. 마이그레이션의 실행 SQL(DDL)은 그대로 두고 주석·공백만 고친 경우
 * (예: 문구 정리)에도 체크섬이 바뀌어 validate 가 부팅을 막는데, 이를 방지한다.
 * DDL 자체를 바꾸는 것은 여전히 새 버전(Vn+1)으로 추가해야 한다.
 */
@Configuration
class FlywayConfig {

    @Bean
    fun flywayMigrationStrategy(): FlywayMigrationStrategy = FlywayMigrationStrategy { flyway ->
        flyway.repair()
        flyway.migrate()
    }
}
