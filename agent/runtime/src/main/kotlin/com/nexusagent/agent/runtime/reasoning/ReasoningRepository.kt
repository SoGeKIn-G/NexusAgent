package com.nexusagent.agent.runtime.reasoning

import android.content.Context
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import com.nexusagent.core.model.DecisionCodec

/**
 * The reasoning layer's public surface.
 *
 * Owns one [HttpClient] for the process. Ktor clients hold a connection pool and a
 * dispatcher; creating one per request would spend more time on TLS handshakes than on
 * inference, which on a per-step loop is the difference between a demo that feels alive
 * and one that feels broken.
 */
class ReasoningRepository(context: Context) {

    val settingsStore = AgentSettingsStore(context.applicationContext)
    val settings: Flow<AgentSettings> = settingsStore.settings

    private val httpClient = HttpClient(OkHttp) {
        expectSuccess = false // 4xx/5xx are classified by the provider, not thrown here.

        install(ContentNegotiation) {
            json(DecisionCodec.json)
        }
        install(HttpTimeout) {
            // Generous but bounded. A hung request stalls the whole agent loop, and the
            // orchestrator's wall-clock guard should not be the first thing to notice.
            requestTimeoutMillis = 45_000
            connectTimeoutMillis = 15_000
            socketTimeoutMillis = 45_000
        }
    }

    /**
     * Built per call rather than cached, so a provider or model change in Settings takes
     * effect on the very next step instead of after a restart.
     */
    private suspend fun provider(): LlmProvider {
        val current = settings.first()
        return when (current.provider) {
            ProviderId.GEMINI -> GeminiProvider(
                client = httpClient,
                apiKeyProvider = settingsStore::apiKey,
                model = current.model.orDefault(GeminiProvider.DEFAULT_MODEL),
            )

            ProviderId.ANTHROPIC -> AnthropicProvider(
                client = httpClient,
                apiKeyProvider = settingsStore::apiKey,
                model = current.model.orDefault(AnthropicProvider.DEFAULT_MODEL),
            )

            // Not just OpenAI: any endpoint speaking /v1/chat/completions. The base URL
            // is user-configurable, so this single implementation covers Groq,
            // OpenRouter, Together, a local Ollama, and OpenAI itself.
            ProviderId.OPENAI -> OpenAiProvider(
                client = httpClient,
                apiKeyProvider = settingsStore::apiKey,
                model = current.model.ifBlank { OpenAiProvider.DEFAULT_MODEL },
                baseUrl = current.baseUrl,
            )
        }
    }

    /**
     * Guards against a stored model belonging to the *other* provider.
     *
     * Switching provider in Settings leaves the previously-saved model id in place, and
     * sending "gemini-2.5-flash" to Anthropic is a 404 that reads like a broken key.
     * Not applied to the OpenAI-compatible path, where model ids are arbitrary and vary
     * per backend - there is no family prefix to check against.
     */
    private fun String.orDefault(default: String): String =
        if (isBlank() || !default.matchesFamily(this)) default else this

    private fun String.matchesFamily(candidate: String): Boolean {
        val prefix = substringBefore('-')
        return candidate.startsWith(prefix)
    }

    suspend fun decide(turn: AgentTurn): LlmResult = provider().decide(turn)

    suspend fun hasApiKey(): Boolean = !settingsStore.apiKey().isNullOrBlank()
}
