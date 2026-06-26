package com.aixnative.ai

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Pure unit tests — no Spring, no network. Fakes stand in for providers. */
class AiServiceManagerTest {

    private class FakeProvider(
        override val name: String,
        override val priority: Int,
        private val configured: Boolean = true,
        private val behavior: (String) -> String,
    ) : AiProvider {
        override fun isConfigured() = configured
        override fun complete(prompt: String): String = behavior(prompt)
    }

    private fun manager(vararg providers: AiProvider, autoFallback: Boolean = true) =
        AiServiceManager(
            providers.toList(),
            AiServiceProperties(autoFallback = autoFallback, providerTimeoutMs = 300, overallDeadlineMs = 1_000),
        )

    @Test
    fun `picks the lowest-priority configured provider`() {
        val mgr = manager(
            FakeProvider("Claude", priority = 0) { "primary" },
            FakeProvider("Mistral", priority = 1) { "secondary" },
        )
        val result = mgr.complete("hello")
        assertEquals("Claude", result.provider)
        assertEquals("primary", result.text)
    }

    @Test
    fun `falls back to the next provider when the first throws`() {
        val mgr = manager(
            FakeProvider("Claude", priority = 0) { error("boom") },
            FakeProvider("Mistral", priority = 1) { "recovered" },
        )
        val result = mgr.complete("hello")
        assertEquals("Mistral", result.provider)
        assertEquals("recovered", result.text)
    }

    @Test
    fun `falls back when the first provider exceeds its timeout`() {
        val mgr = manager(
            FakeProvider("Slow", priority = 0) { Thread.sleep(2_000); "too late" },
            FakeProvider("Fast", priority = 1) { "on time" },
        )
        val result = mgr.complete("hello")
        assertEquals("Fast", result.provider)
    }

    @Test
    fun `skips unconfigured providers`() {
        val mgr = manager(
            FakeProvider("Unset", priority = 0, configured = false) { "never" },
            FakeProvider("Claude", priority = 1) { "used" },
        )
        assertEquals("Claude", mgr.complete("hello").provider)
    }

    @Test
    fun `throws when no provider is configured`() {
        val mgr = manager(
            FakeProvider("Unset", priority = 0, configured = false) { "never" },
        )
        assertFailsWith<IllegalStateException> { mgr.complete("hello") }
    }
}
