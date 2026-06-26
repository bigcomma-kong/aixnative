package com.aixnative

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication
import org.springframework.data.jpa.repository.config.EnableJpaAuditing

@SpringBootApplication
@ConfigurationPropertiesScan
@EnableJpaAuditing
class AixnativeApplication

fun main(args: Array<String>) {
    runApplication<AixnativeApplication>(*args)
}
