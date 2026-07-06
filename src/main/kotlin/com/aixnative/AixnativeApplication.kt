package com.aixnative

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication
import org.springframework.boot.web.servlet.support.SpringBootServletInitializer
import org.springframework.data.jpa.repository.config.EnableJpaAuditing
import java.util.TimeZone

// SpringBootServletInitializer 상속: 외부 톰캣/WAS 에 WAR 로 올렸을 때 앱을 부팅시켜 준다.
// main() 은 그대로 유지되므로 `java -jar` 단독 실행(내장 톰캣)도 동일하게 동작.
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableJpaAuditing
class AixnativeApplication : SpringBootServletInitializer() {
    override fun configure(builder: SpringApplicationBuilder): SpringApplicationBuilder {
        applyKstDefaultTimeZone() // WAR(외부 WAS) 부팅 경로에서도 KST 적용
        return builder.sources(AixnativeApplication::class.java)
    }

    companion object {
        /**
         * 앱 전역 기본 타임존을 한국(KST)으로 고정.
         * '오늘' 계산·로그가 서버 UTC 가 아닌 KST 기준으로 동작한다.
         * DB timestamp 저장은 application.yml 의 `hibernate.jdbc.time_zone: UTC` 로 UTC 를 유지하므로
         * 기존 데이터·절대시각 정합성은 그대로다(표시·계산만 KST).
         */
        fun applyKstDefaultTimeZone() {
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Seoul"))
        }
    }
}


fun main(args: Array<String>) {
    AixnativeApplication.applyKstDefaultTimeZone() // java -jar(내장 톰캣) 부팅 경로
    runApplication<AixnativeApplication>(*args)
}
