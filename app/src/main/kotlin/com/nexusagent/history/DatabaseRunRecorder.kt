package com.nexusagent.history

import com.nexusagent.agent.runtime.orchestrator.RunRecorder
import com.nexusagent.agent.runtime.reasoning.ReasoningRepository
import com.nexusagent.core.data.HistoryRepository
import com.nexusagent.core.model.RunContext
import com.nexusagent.core.model.StepRecord
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Bridges the orchestrator's [RunRecorder] port to the Room-backed store.
 *
 * Lives in `:app` so `:agent:runtime` never depends on `:core:data` - the loop stays
 * unaware that history is a database at all, which is what lets it run with the recorder
 * absent entirely.
 */
@Singleton
class DatabaseRunRecorder @Inject constructor(
    private val history: HistoryRepository,
    private val reasoning: ReasoningRepository,
) : RunRecorder {

    override suspend fun onRunStarted(run: RunContext) {
        val settings = reasoning.settings.first()
        history.startRun(run, settings.provider.name, settings.model)
    }

    override suspend fun onStep(runId: Long, packageName: String, step: StepRecord) {
        history.recordStep(runId, packageName, step)
    }

    override suspend fun onRunFinished(run: RunContext, status: String, summary: String?) {
        val settings = reasoning.settings.first()
        history.finishRun(run, settings.provider.name, settings.model, status, summary)
    }
}
