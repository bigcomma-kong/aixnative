package com.underwriteai.ai

/**
 * One LLM provider. The [AiServiceManager] sorts configured providers by
 * [priority] (lower first) and falls back across them on failure.
 *
 * Phase 1 exposes a single text completion. Structured analysis (prompt
 * templates + JSON parsing via the ported AiPromptBuilder) arrives in Phase 2.
 */
interface AiProvider {
    /** Display name, e.g. "Claude". */
    val name: String

    /** Selection order — lower wins. */
    val priority: Int

    /** True when the provider has the credentials it needs to be called. */
    fun isConfigured(): Boolean

    /** Send a single prompt and return the raw completion text. Throws on failure. */
    fun complete(prompt: String): String
}

/** Result of a successful AI call: which provider answered + the text. */
data class AiResult(val provider: String, val text: String)
