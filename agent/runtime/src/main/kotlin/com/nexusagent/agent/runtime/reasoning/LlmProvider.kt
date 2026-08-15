package com.nexusagent.agent.runtime.reasoning

import com.nexusagent.core.model.AgentDecision
import com.nexusagent.core.model.RunContext

/**
 * Everything the model needs to choose one next action.
 *
 * Note what is absent: the full screen history. Only a compact trace of prior
 * *decisions* is carried forward, never prior screen dumps. Accumulating snapshots would
 * make cost grow quadratically with step count and, worse, would bury the current screen
 * under stale ones the model then reasons about by mistake.
 */
data class AgentTurn(
    val goal: String,
    val run: RunContext,
    val screenJson: String,
    val packageName: String,
    val maxSteps: Int,
    val shortcutCatalogue: String,
    /** Attached only when the tree is too sparse to ground on, or an action fell flat. */
    val screenshotBase64Jpeg: String? = null,
    /** Fed back after a malformed response so the model can correct itself. */
    val lastError: String? = null,
)

sealed interface LlmResult {
    data class Success(
        val decision: AgentDecision,
        val promptTokens: Int?,
        val responseTokens: Int?,
        val latencyMs: Long,
    ) : LlmResult

    /** Well-formed HTTP response, unusable content. Retryable with a correction hint. */
    data class InvalidResponse(val message: String, val raw: String) : LlmResult

    /**
     * Network, auth, or quota problem. [retryable] tells the loop whether to bother.
     *
     * [retryAfterMs] carries the provider's own instruction when it gives one - a 429 from
     * Gemini states the exact wait. Obeying that beats guessing with backoff, which either
     * retries too early (and stays throttled) or too late (and stalls the run).
     */
    data class Error(
        val message: String,
        val retryable: Boolean,
        val retryAfterMs: Long? = null,
    ) : LlmResult
}

enum class ProviderId(val displayName: String) {
    GEMINI("Google Gemini"),
    ANTHROPIC("Anthropic Claude"),
    OPENAI("OpenAI"),
}

/**
 * A reasoning backend.
 *
 * The whole point of this interface is that it has exactly one method with no
 * provider-specific concepts in its signature. Swapping Gemini for Claude, or for an
 * on-device Gemma running through MediaPipe, is a new implementation and nothing else -
 * no changes to perception, execution, or the orchestrator.
 */
interface LlmProvider {
    val id: ProviderId

    /** False providers get tree-only turns; screenshots are never sent to them. */
    val supportsVision: Boolean

    suspend fun decide(turn: AgentTurn): LlmResult
}
