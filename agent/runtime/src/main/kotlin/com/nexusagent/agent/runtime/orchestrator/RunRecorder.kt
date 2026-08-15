package com.nexusagent.agent.runtime.orchestrator

import com.nexusagent.core.model.RunContext
import com.nexusagent.core.model.StepRecord

/**
 * Where the orchestrator sends its trace.
 *
 * An interface rather than a direct dependency on `:core:data` so `:agent:runtime` stays
 * free of Room - the loop shouldn't know or care whether history lands in a database, a
 * log file, or nowhere at all. `:app` supplies the implementation.
 */
interface RunRecorder {
    suspend fun onRunStarted(run: RunContext)
    suspend fun onStep(runId: Long, packageName: String, step: StepRecord)
    suspend fun onRunFinished(run: RunContext, status: String, summary: String?)
}
