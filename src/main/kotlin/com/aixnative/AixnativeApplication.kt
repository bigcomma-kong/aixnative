package com.aixnative

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication
import org.springframework.boot.web.servlet.support.SpringBootServletInitializer
import org.springframework.data.jpa.repository.config.EnableJpaAuditing

// SpringBootServletInitializer 상속: 외부 톰캣/WAS 에 WAR 로 올렸을 때 앱을 부팅시켜 준다.
// main() 은 그대로 유지되므로 `java -jar` 단독 실행(내장 톰캣)도 동일하게 동작.
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableJpaAuditing
class AixnativeApplication : SpringBootServletInitializer() {
    override fun configure(builder: SpringApplicationBuilder): SpringApplicationBuilder =
        builder.sources(AixnativeApplication::class.java)
}


fun main(args: Array<String>) {
    runApplication<AixnativeApplication>(*args)
}
