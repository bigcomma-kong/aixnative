package com.underwriteai

import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles

@SpringBootTest
@ActiveProfiles("test")
class UnderwriteAiApplicationTests {

    @Test
    fun contextLoads() {
        // Verifies the full wiring boots and Flyway V1 applies on H2.
    }
}
