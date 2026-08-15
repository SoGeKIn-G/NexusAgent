package com.nexusagent.agent.runtime.orchestrator

import android.util.Log
import com.nexusagent.agent.perception.PerceptionRepository
import com.nexusagent.agent.perception.SnapshotResult
import com.nexusagent.agent.runtime.execution.ExecutionRepository
import com.nexusagent.agent.runtime.reasoning.AgentTurn
import com.nexusagent.agent.runtime.reasoning.LlmResult
import com.nexusagent.agent.runtime.reasoning.ReasoningRepository
import com.nexusagent.core.model.ActionResult
import com.nexusagent.core.model.AgentAction
import com.nexusagent.core.model.AgentDecision
import com.nexusagent.core.model.AgentState
import com.nexusagent.core.model.RunContext
import com.nexusagent.core.model.RunGuards
import com.nexusagent.core.model.ScreenSnapshot
import com.nexusagent.core.model.StepRecord
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The agent loop.
 *
 * ```
 *   PERCEIVE -> COMPRESS -> REASON -> ACT -> VERIFY -> (repeat | finish)
 * ```
 *
 * Everything else in this project is a component; this is the thing that makes it an
 * agent. Its real job is not running the happy path - that is twenty lines - but deciding
 * when to stop. A model with gesture privileges and no termination conditions will
 * cheerfully tap forever, and the difference between a demo and a liability is entirely
 * in the guards below.
 *
 * ## Termination
 *
 * | Guard | Why it exists |
 * |---|---|
 * | Step budget | Bounds cost and time on a task the model has misunderstood |
 * | Wall clock | Catches a model that is progressing but far too slowly |
 * | Loop detection | The most common failure: same screen, same tap, forever |
 * | Ineffective action | The tap landed and changed nothing - retrying is pointless |
 * | Destructive gate | Some actions cannot be undone by the next step |
 * | Cancellation | Structured concurrency makes stop() genuinely immediate |
 */
class AgentOrchestrator(
    private val perception: PerceptionRepository,
    private val execution: ExecutionRepository,
    private val reasoning: ReasoningRepository,
    private val scope: CoroutineScope,
    /**
     * Optional on purpose. History is diagnostic; the agent must run without it, and a
     * storage failure must never abort a task the user asked for.
     */
    private val history: RunRecorder? = null,
) {

    private val _state = MutableStateFlow<AgentState>(AgentState.Idle)
    val state: StateFlow<AgentState> = _state.asStateFlow()

    private var job: Job? = null

    /** Set by [confirm]/[reject] to release a run paused on the destructive gate. */
    private val pendingConfirmation = MutableStateFlow<Boolean?>(null)
    private val pendingAnswer = MutableStateFlow<String?>(null)

    val isRunning: Boolean get() = job?.isActive == true

    /**
     * Starts a run, replacing any run already in flight.
     *
     * Replacing rather than refusing is the deliberate choice. Refusing meant a run stuck
     * in a minute-long rate-limit backoff silently swallowed every subsequent request -
     * the user pressed Run, nothing happened, and there was no way to tell the app apart
     * from a dead one. A new explicit goal is unambiguous intent; honour it.
     */
    fun start(goal: String) {
        if (isRunning) {
            Log.i(TAG, "Replacing the in-flight run with: $goal")
            job?.cancel()
        }
        job = scope.launch { runLoop(goal) }
    }

    /**
     * Cancels the run.
     *
     * Cancelling the job is sufficient because every suspending call in the loop is
     * cooperative - the HTTP request, the settle wait, the delays. This is the structured
     * concurrency payoff: the stop button is one line and it actually works mid-request,
     * rather than setting a flag that gets noticed several seconds later.
     */
    fun stop(reason: String = "Stopped by user") {
        job?.cancel()
        job = null
        val run = currentRun()
        _state.value = if (run != null) AgentState.Failed(run, reason) else AgentState.Idle
    }

    fun confirm() { pendingConfirmation.value = true }
    fun reject() { pendingConfirmation.value = false }
    fun answer(text: String) { pendingAnswer.value = text }

    fun reset() {
        if (!isRunning) _state.value = AgentState.Idle
    }

    // -- the loop -----------------------------------------------------------------

    private suspend fun runLoop(goal: String) {
        val settings = reasoning.settings.first()
        val guards = RunGuards(
            maxSteps = settings.maxSteps,
            confirmDestructiveActions = settings.confirmDestructive,
        )

        var run = RunContext(
            runId = System.currentTimeMillis(),
            goal = goal,
            startedAtMillis = System.currentTimeMillis(),
        )

        var lastError: String? = null
        var lastResult: ActionResult? = null
        var invalidResponses = 0
        var consecutiveRetries = 0

        history?.let { runCatching { it.onRunStarted(run) } }

        try {
            while (scope.isActive) {
                if (guards.isStepBudgetExceeded(run.stepIndex)) {
                    return finish(AgentState.Failed(run, "Gave up after ${guards.maxSteps} steps."))
                }
                if (guards.isTimedOut(run.startedAtMillis, System.currentTimeMillis())) {
                    return finish(AgentState.Failed(run, "Gave up after three minutes."))
                }
                if (guards.isLooping(run.history)) {
                    // Detected here rather than left to the model: it cannot see that it
                    // is repeating itself, because each turn looks locally reasonable.
                    return finish(AgentState.Failed(run, "Stuck repeating the same action."))
                }
                if (guards.isOscillating(run.history)) {
                    return finish(
                        AgentState.Failed(run, "Going in circles between the same screens."),
                    )
                }

                _state.value = AgentState.Planning(run)

                val snapshot = captureScreen(lastResult)
                    ?: return finish(AgentState.Failed(run, "Cannot read the screen."))
                execution.bind(snapshot.snapshot)

                val turn = AgentTurn(
                    goal = goal,
                    run = run,
                    screenJson = snapshot.json,
                    packageName = snapshot.snapshot.packageName,
                    maxSteps = guards.maxSteps,
                    shortcutCatalogue = execution.shortcutCatalogue,
                    screenshotBase64Jpeg = screenshotIfNeeded(snapshot.snapshot, lastResult),
                    lastError = lastError,
                )

                val decision = when (val result = reasoning.decide(turn)) {
                    is LlmResult.Success -> {
                        lastError = null
                        invalidResponses = 0
                        consecutiveRetries = 0
                        result.decision
                    }

                    is LlmResult.InvalidResponse -> {
                        // Feed the complaint back and let the model correct itself. Two
                        // attempts; a third means something is structurally wrong.
                        if (++invalidResponses > MAX_INVALID_RESPONSES) {
                            return finish(AgentState.Failed(run, "Model kept returning unusable output."))
                        }
                        lastError = result.message
                        Log.w(TAG, "Invalid response ($invalidResponses): ${result.message}")
                        continue
                    }

                    is LlmResult.Error -> {
                        if (!result.retryable) {
                            return finish(AgentState.Failed(run, result.message))
                        }

                        // Some tasks disconnect the agent from its own reasoning. Turning
                        // on aeroplane mode, toggling Wi-Fi off, enabling a VPN - the
                        // action succeeds, and the *next* model call cannot resolve DNS.
                        // Retrying four times into a network the agent itself disabled is
                        // pointless, and reporting "failed" is wrong: the last action
                        // landed. Report it honestly as unverifiable instead.
                        if (result.looksLikeNoNetwork() && run.history.isNotEmpty()) {
                            val last = run.history.last()
                            if (last.result == ActionResult.Success) {
                                return finish(
                                    AgentState.Done(
                                        run,
                                        "Ran ${run.history.size} steps. The last action took the " +
                                            "device offline, so the result could not be verified - " +
                                            "check the screen.",
                                    ),
                                )
                            }
                        }

                        if (++consecutiveRetries > MAX_RETRIES) {
                            return finish(
                                AgentState.Failed(run, "${result.message} Giving up after $MAX_RETRIES retries."),
                            )
                        }
                        // Prefer the provider's own instruction - a Gemini 429 states the
                        // exact wait, and guessing when you have been told is how a run
                        // ends up either still throttled or idle far longer than needed.
                        // Exponential backoff only as a fallback: a flat retry against a
                        // per-minute limit keeps the quota pinned indefinitely.
                        val backoff = result.retryAfterMs?.plus(RETRY_JITTER_MS)
                            ?: (RETRY_BASE_BACKOFF_MS shl (consecutiveRetries - 1))
                        Log.w(TAG, "Retryable error (${consecutiveRetries}/$MAX_RETRIES), waiting ${backoff}ms: ${result.message}")

                        // Surfaced as its own state, and counted down, so a minute-long
                        // wait reads as "waiting" rather than as a hang.
                        val waitUntil = System.currentTimeMillis() + backoff
                        while (scope.isActive) {
                            val remaining = waitUntil - System.currentTimeMillis()
                            if (remaining <= 0) break
                            _state.value = AgentState.Retrying(
                                run = run,
                                reason = result.message,
                                attempt = consecutiveRetries,
                                maxAttempts = MAX_RETRIES,
                                retryInMillis = remaining,
                            )
                            delay(minOf(remaining, RETRY_TICK_MS))
                        }
                        continue
                    }
                }

                Log.i(TAG, "step ${run.stepIndex + 1}: ${decision.thought} -> ${decision.action}")

                // Terminal and conversational actions leave the loop rather than reaching
                // the executor.
                when (val action = decision.action) {
                    is AgentAction.Done ->
                        return finish(AgentState.Done(run, action.summary))

                    is AgentAction.Fail ->
                        return finish(AgentState.Failed(run, action.reason))

                    is AgentAction.AskUser -> {
                        val reply = awaitAnswer(run, action.question)
                            ?: return finish(AgentState.Failed(run, "No answer given."))
                        run = run.copy(
                            history = run.history + StepRecord(
                                index = run.stepIndex,
                                thought = decision.thought,
                                action = action,
                                result = ActionResult.Success,
                                screenSignature = snapshot.snapshot.signature,
                                latencyMs = 0,
                                stats = snapshot.snapshot.stats,
                            ),
                            stepIndex = run.stepIndex + 1,
                        )
                        // The answer joins the goal so it survives history truncation.
                        lastError = "The user answered: $reply"
                        continue
                    }

                    AgentAction.RequestScreenshot -> {
                        lastResult = ActionResult.Ineffective // forces an image next turn
                        run = run.copy(stepIndex = run.stepIndex + 1)
                        continue
                    }

                    else -> Unit
                }

                if (guards.confirmDestructiveActions &&
                    DestructiveActions.requiresConfirmation(decision.action, snapshot.snapshot)
                ) {
                    val approved = awaitConfirmation(
                        run,
                        decision,
                        DestructiveActions.describe(decision.action, snapshot.snapshot),
                    )
                    if (!approved) {
                        return finish(AgentState.Failed(run, "You declined that action."))
                    }

                    // A human just spent seconds - possibly a minute - deciding, and the
                    // element ids in `snapshot` were captured before that pause. If the
                    // screen moved in the meantime (a notification, an autocomplete
                    // dropdown, the user scrolling), acting on those ids would tap
                    // whatever now occupies that position. Re-plan instead of guessing.
                    val nowShowing = (perception.snapshot() as? SnapshotResult.Success)?.snapshot
                    if (nowShowing == null || nowShowing.signature != snapshot.snapshot.signature) {
                        Log.w(TAG, "Screen changed while awaiting approval; re-planning.")
                        lastResult = ActionResult.Ineffective
                        lastError = "The screen changed while waiting for approval, so the " +
                            "previous element ids are stale. Look at the CURRENT screen and decide again."
                        continue
                    }
                }

                _state.value = AgentState.Executing(run, decision)
                val startedAt = System.currentTimeMillis()
                val executed = execution.execute(decision.action)

                _state.value = AgentState.Verifying(run, decision)
                val verified = verify(snapshot.snapshot, executed)
                lastResult = verified

                val record = StepRecord(
                    index = run.stepIndex,
                    thought = decision.thought,
                    action = decision.action,
                    result = verified,
                    screenSignature = snapshot.snapshot.signature,
                    latencyMs = System.currentTimeMillis() - startedAt,
                    stats = snapshot.snapshot.stats,
                )
                history?.let {
                    runCatching { it.onStep(run.runId, snapshot.snapshot.packageName, record) }
                }

                run = run.copy(
                    history = run.history + record,
                    stepIndex = run.stepIndex + 1,
                )
            }
        } catch (e: CancellationException) {
            throw e // stop() already set the state; do not overwrite it.
        } catch (e: Exception) {
            Log.e(TAG, "Loop crashed", e)
            finish(AgentState.Failed(run, e.message ?: "Unexpected error"))
        }
    }

    // -- steps --------------------------------------------------------------------

    private suspend fun captureScreen(lastResult: ActionResult?): SnapshotResult.Success? {
        // Let the previous action's effects land before observing. Skipped on the first
        // step, where there is nothing to settle from.
        if (lastResult != null) perception.awaitScreenSettled()

        repeat(SNAPSHOT_ATTEMPTS) { attempt ->
            when (val result = perception.snapshot()) {
                is SnapshotResult.Success -> return result
                SnapshotResult.NoActiveWindow -> delay(SNAPSHOT_RETRY_MS) // mid-transition
                SnapshotResult.ServiceUnavailable -> return null // not recoverable by waiting
            }
        }
        return null
    }

    /**
     * The hybrid perception policy, in one place.
     *
     * Pixels are attached only when the tree cannot carry the decision: a canvas-rendered
     * surface exposing almost nothing, or an action that demonstrably did nothing and may
     * have hit an overlay the tree does not describe.
     */
    private suspend fun screenshotIfNeeded(
        snapshot: ScreenSnapshot,
        lastResult: ActionResult?,
    ): String? {
        val needed = snapshot.isSparse() || lastResult == ActionResult.Ineffective
        if (!needed) return null
        return perception.screenshot()?.base64Jpeg
    }

    /**
     * Confirms the action actually changed something.
     *
     * Compares a hash of the screen before and after. An unchanged signature means the tap
     * landed on something inert - which the model must be told, because from its side a
     * successful dispatch and a successful *outcome* are indistinguishable.
     */
    private suspend fun verify(before: ScreenSnapshot, executed: ActionResult): ActionResult {
        if (executed !is ActionResult.Success) return executed

        perception.awaitScreenSettled()
        val after = (perception.snapshot() as? SnapshotResult.Success)?.snapshot
            ?: return ActionResult.Success // cannot tell; assume it worked

        return if (after.signature == before.signature) {
            Log.d(TAG, "screen unchanged - action was ineffective")
            ActionResult.Ineffective
        } else {
            ActionResult.Success
        }
    }

    private suspend fun awaitConfirmation(
        run: RunContext,
        decision: AgentDecision,
        prompt: String,
    ): Boolean {
        pendingConfirmation.value = null
        _state.value = AgentState.AwaitingConfirmation(run, decision, prompt)
        return pendingConfirmation.first { it != null } == true
    }

    private suspend fun awaitAnswer(run: RunContext, question: String): String? {
        pendingAnswer.value = null
        _state.value = AgentState.AwaitingUserInput(run, question)
        return pendingAnswer.first { it != null }
    }

    /**
     * Single exit point for the loop.
     *
     * Suspending so the terminal state can be persisted before the run is considered
     * over - a history row that never records how a run ended is worse than no row.
     */
    private suspend fun finish(terminal: AgentState) {
        _state.value = terminal
        job = null

        history?.let { recorder ->
            val (run, status, summary) = when (terminal) {
                is AgentState.Done -> Triple(terminal.run, "done", terminal.summary)
                is AgentState.Failed -> Triple(terminal.run, "failed", terminal.reason)
                else -> return
            }
            runCatching { recorder.onRunFinished(run, status, summary) }
        }
    }

    /**
     * Distinguishes "the network is gone" from "the provider had a bad minute".
     *
     * Matched on message text because every HTTP client surfaces this differently -
     * UnknownHostException, "No address associated with hostname", ENETUNREACH - and none
     * of them map to a status code we could switch on.
     */
    private fun LlmResult.Error.looksLikeNoNetwork(): Boolean {
        val m = message.lowercase()
        return "unable to resolve host" in m ||
            "no address associated" in m ||
            "unknownhost" in m ||
            "network is unreachable" in m ||
            "enetunreach" in m ||
            "failed to connect" in m
    }

    private fun currentRun(): RunContext? = when (val s = _state.value) {
        is AgentState.Planning -> s.run
        is AgentState.Retrying -> s.run
        is AgentState.Executing -> s.run
        is AgentState.Verifying -> s.run
        is AgentState.AwaitingConfirmation -> s.run
        is AgentState.AwaitingUserInput -> s.run
        is AgentState.Done -> s.run
        is AgentState.Failed -> s.run
        else -> null
    }

    private companion object {
        const val TAG = "NexusAgent"
        const val MAX_INVALID_RESPONSES = 2
        const val MAX_RETRIES = 4

        /** Fallback when the provider gives no advice. Doubles: 2s, 4s, 8s, 16s. */
        const val RETRY_BASE_BACKOFF_MS = 2_000L

        /** Added to a provider-supplied wait, so we resume just after the window rolls. */
        const val RETRY_JITTER_MS = 1_000L

        /** How often the countdown updates while waiting out a backoff. */
        const val RETRY_TICK_MS = 500L
        const val SNAPSHOT_ATTEMPTS = 3
        const val SNAPSHOT_RETRY_MS = 400L
    }
}
