package com.aixnative.common.web

import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

/**
 * Verifies the global error mapping in isolation (no Spring context / security).
 * A malformed JSON body must surface as a 400 client error, never a generic 500.
 */
class GlobalExceptionHandlerTest {

    data class Payload(val name: String, val amount: Int)

    @RestController
    class StubController {
        @PostMapping("/echo")
        fun echo(@RequestBody body: Payload): Payload = body
    }

    private val mockMvc: MockMvc = MockMvcBuilders
        .standaloneSetup(StubController())
        .setControllerAdvice(GlobalExceptionHandler())
        .build()

    @Test
    fun `malformed JSON body returns 400 with friendly message`() {
        mockMvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/echo")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{ not valid json ")
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.error").exists())
    }

    @Test
    fun `invalid UTF-8 bytes in body returns 400 not 500`() {
        // 0xBB is an invalid UTF-8 start byte — the exact failure seen in the smoke test.
        val badBytes = byteArrayOf('{'.code.toByte(), 0xBB.toByte(), '}'.code.toByte())
        mockMvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/echo")
                .contentType(MediaType.APPLICATION_JSON)
                .content(badBytes)
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.success").value(false))
    }
}
