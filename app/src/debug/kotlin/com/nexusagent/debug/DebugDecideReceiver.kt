package com.nexusagent.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.nexusagent.agent.perception.PerceptionRepository
import com.nexusagent.agent.perception.SnapshotResult
import com.nexusagent.agent.runtime.execution.ExecutionRepository
import com.nexusagent.agent.runtime.reasoning.AgentTurn
import com.nexusagent.agent.runtime.reasoning.LlmResult
import com.nexusagent.agent.runtime.reasoning.ReasoningRepository
import com.nexusagent.core.model.RunContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * M4's acceptance test: one real decision against one real screen.
 *
 * ```
 *   adb shell am broadcast -a com.nexusagent.DECIDE --es goal "open the wifi settings"
 * ```
 *
 * Perceives, asks the model, prints the thought and the chosen action - and stops. It
 * deliberately does **not** execute the action: the point of this milestone is to confirm
 * that the model, given a compressed screen, picks something a human would agree with. The
 * loop that acts on it is M5.
 */
class DebugDecideReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val goal = intent.getStringExtra("goal") ?: "describe what is on screen and finish"
        val execute = intent.getBooleanExtra("execute", false)

        CoroutineScope(Dispatchers.Default).launch {
            try {
                val perception = PerceptionRepository()
                val execution = ExecutionRepository(context.applicationContext)
                val reasoning = ReasoningRepository(context.applicationContext)

                if (!reasoning.hasApiKey()) {
                    Log.e(TAG, "No API key saved. Add one on the Settings tab.")
                    return@launch
                }

                val snapshot = perception.snapshot() as? SnapshotResult.Success
                if (snapshot == null) {
                    Log.e(TAG, "No screen to look at.")
                    return@launch
                }
                execution.bind(snapshot.snapshot)

                Log.i(TAG, "GOAL: $goal")
                Log.i(TAG, "SCREEN: ${snapshot.snapshot.packageName} (${snapshot.snapshot.elements.size} elements, ${snapshot.json.length} chars)")

                val turn = AgentTurn(
                    goal = goal,
                    run = RunContext(runId = 0, goal = goal, startedAtMillis = System.currentTimeMillis()),
                    screenJson = snapshot.json,
                    packageName = snapshot.snapshot.packageName,
                    maxSteps = 25,
                    shortcutCatalogue = execution.shortcutCatalogue,
                    // Mirrors the hybrid policy: pixels only when the tree is too thin to
                    // ground on.
                    screenshotBase64Jpeg = if (snapshot.snapshot.isSparse()) {
                        perception.screenshot()?.base64Jpeg
                    } else {
                        null
                    },
                )

                when (val result = reasoning.decide(turn)) {
                    is LlmResult.Success -> {
                        Log.i(TAG, "THOUGHT: ${result.decision.thought}")
                        Log.i(TAG, "ACTION : ${result.decision.action}")
                        Log.i(
                            TAG,
                            "tokens in=${result.promptTokens} out=${result.responseTokens} " +
                                "latency=${result.latencyMs}ms confidence=${result.decision.confidence}",
                        )
                        if (execute) {
                            val outcome = execution.execute(result.decision.action)
                            Log.i(TAG, "EXECUTED: $outcome")
                        }
                    }

                    is LlmResult.InvalidResponse ->
                        Log.e(TAG, "Model returned something unusable: ${result.message} / ${result.raw}")

                    is LlmResult.Error ->
                        Log.e(TAG, "Provider error (retryable=${result.retryable}): ${result.message}")
                }
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val TAG = "NexusReasoning"
    }
}
