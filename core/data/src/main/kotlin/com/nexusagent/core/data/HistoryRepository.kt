package com.nexusagent.core.data

import com.nexusagent.core.model.ActionResult
import com.nexusagent.core.model.RunContext
import com.nexusagent.core.model.StepRecord
import kotlinx.coroutines.flow.Flow

/**
 * Records what the agent did, so a run can be reviewed after the fact.
 *
 * Deliberately fire-and-forget from the orchestrator's perspective: a failure to write
 * history must never abort a task the user asked for. Every method here swallows storage
 * errors rather than propagating them into the loop.
 */
class HistoryRepository(private val dao: RunDao) {

    val runs: Flow<List<RunEntity>> = dao.observeRuns()
    val compression: Flow<CompressionSummary?> = dao.observeCompressionSummary()

    fun steps(runId: Long): Flow<List<StepEntity>> = dao.observeSteps(runId)

    suspend fun startRun(run: RunContext, provider: String, model: String) {
        runCatching {
            // Anything still `running` at this point belongs to a dead process - only one
            // run is ever active. Close them out before opening a new one.
            dao.resolveOrphanedRuns(System.currentTimeMillis())

            dao.upsertRun(
                RunEntity(
                    id = run.runId,
                    goal = run.goal,
                    startedAt = run.startedAtMillis,
                    endedAt = null,
                    status = "running",
                    summary = null,
                    provider = provider,
                    model = model,
                    stepCount = 0,
                    promptTokens = 0,
                    responseTokens = 0,
                ),
            )
        }
    }

    suspend fun recordStep(runId: Long, packageName: String, step: StepRecord) {
        runCatching {
            dao.insertStep(
                StepEntity(
                    runId = runId,
                    index = step.index,
                    thought = step.thought,
                    action = step.action.toString(),
                    result = when (val r = step.result) {
                        ActionResult.Success -> "ok"
                        ActionResult.Ineffective -> "no change"
                        is ActionResult.Failure -> "failed: ${r.reason}"
                    },
                    packageName = packageName,
                    latencyMs = step.latencyMs,
                    rawNodeCount = step.stats?.rawNodeCount ?: 0,
                    keptNodeCount = step.stats?.keptNodeCount ?: 0,
                    rawBytes = step.stats?.rawBytes ?: 0,
                    compressedBytes = step.stats?.compressedBytes ?: 0,
                    walkDurationMs = step.stats?.walkDurationMs ?: 0,
                ),
            )
        }
    }

    suspend fun finishRun(
        run: RunContext,
        provider: String,
        model: String,
        status: String,
        summary: String?,
    ) {
        runCatching {
            dao.upsertRun(
                RunEntity(
                    id = run.runId,
                    goal = run.goal,
                    startedAt = run.startedAtMillis,
                    endedAt = System.currentTimeMillis(),
                    status = status,
                    summary = summary,
                    provider = provider,
                    model = model,
                    stepCount = run.history.size,
                    promptTokens = 0,
                    responseTokens = 0,
                ),
            )
            dao.trimTo(MAX_RUNS)
        }
    }

    suspend fun clear() {
        runCatching { dao.clearAll() }
    }

    private companion object {
        /** Enough to review a demo session; small enough never to matter on disk. */
        const val MAX_RUNS = 50
    }
}
