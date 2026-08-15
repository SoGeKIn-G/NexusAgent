package com.nexusagent.core.model

/**
 * The agent's finite state machine.
 *
 * A run moves Planning → Executing → Verifying → Planning until it terminates. Every
 * transition is explicit, which is what makes the loop debuggable and what lets the
 * guards in [RunGuards] reason about it.
 */
sealed interface AgentState {

    data object Idle : AgentState

    /** Microphone open, capturing the spoken goal. */
    data class Listening(val partialTranscript: String = "") : AgentState

    data class Planning(val run: RunContext) : AgentState

    /**
     * Waiting out a provider-imposed delay before trying again.
     *
     * Distinct from [Planning] on purpose. A rate-limit backoff can hold a run for a
     * minute or more, and reporting that as "Thinking" makes a working agent look hung -
     * the user presses Run again, the orchestrator drops the duplicate because one is
     * already active, and nothing appears to happen at all.
     */
    data class Retrying(
        val run: RunContext,
        val reason: String,
        val attempt: Int,
        val maxAttempts: Int,
        val retryInMillis: Long,
    ) : AgentState

    data class Executing(val run: RunContext, val decision: AgentDecision) : AgentState

    /** Waiting for the screen to settle, then re-observing to confirm the action landed. */
    data class Verifying(val run: RunContext, val decision: AgentDecision) : AgentState

    /** A destructive action (send / pay / delete) is gated behind explicit user approval. */
    data class AwaitingConfirmation(
        val run: RunContext,
        val decision: AgentDecision,
        val prompt: String,
    ) : AgentState

    data class AwaitingUserInput(val run: RunContext, val question: String) : AgentState

    data class Done(val run: RunContext, val summary: String) : AgentState

    data class Failed(val run: RunContext, val reason: String) : AgentState
}

data class RunContext(
    val runId: Long,
    val goal: String,
    val stepIndex: Int = 0,
    val startedAtMillis: Long,
    val history: List<StepRecord> = emptyList(),
)

data class StepRecord(
    val index: Int,
    val thought: String,
    val action: AgentAction,
    val result: ActionResult,
    val screenSignature: Int,
    val latencyMs: Long,
    val stats: SnapshotStats?,
)

/**
 * Termination guards.
 *
 * A non-deterministic agent with gesture privileges needs hard limits far more than a
 * normal app does: without these it can loop forever, burn API quota, and tap things
 * indefinitely. These are safety guards and cost guards at the same time.
 */
data class RunGuards(
    val maxSteps: Int = 25,
    val maxWallClockMillis: Long = 3 * 60 * 1000L,
    /** Same (screen, action) pair this many times in a row means the agent is stuck. */
    val repeatThreshold: Int = 3,
    /** How many recent steps to inspect for a ping-pong cycle. */
    val oscillationWindow: Int = 6,
    val confirmDestructiveActions: Boolean = true,
) {
    fun isStepBudgetExceeded(stepIndex: Int) = stepIndex >= maxSteps

    fun isTimedOut(startedAtMillis: Long, nowMillis: Long) =
        nowMillis - startedAtMillis >= maxWallClockMillis

    /**
     * Detects the agent tapping the same thing on the same screen over and over —
     * the most common failure mode, and invisible without an explicit check.
     */
    fun isLooping(history: List<StepRecord>): Boolean {
        if (history.size < repeatThreshold) return false
        val recent = history.takeLast(repeatThreshold)
        val first = recent.first()
        return recent.all { it.screenSignature == first.screenSignature && it.action == first.action }
    }

    /**
     * Detects a ping-pong cycle: different actions, but the same handful of screens.
     *
     * [isLooping] only catches an agent repeating one action verbatim. The more common
     * real failure looks locally sensible at every step -
     * `HOME -> NOTIFICATIONS -> scroll -> BACK -> HOME -> NOTIFICATIONS` - because the
     * model cannot see that it has been here before. Judging by the set of *screens*
     * visited rather than the actions taken catches it.
     *
     * Scrolling a long list is deliberately not flagged: each scroll produces a different
     * screen signature, so a genuinely progressing run keeps its variety.
     */
    fun isOscillating(history: List<StepRecord>): Boolean {
        if (history.size < oscillationWindow) return false
        val recent = history.takeLast(oscillationWindow)
        return recent.map { it.screenSignature }.distinct().size <= 2
    }
}
