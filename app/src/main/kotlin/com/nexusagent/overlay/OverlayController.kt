package com.nexusagent.overlay

import android.content.Context
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.AndroidUiDispatcher
import androidx.compose.ui.platform.WindowRecomposerFactory
import com.nexusagent.agent.runtime.orchestrator.AgentOrchestrator
import com.nexusagent.core.model.AgentState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Shows and hides the floating bubble in step with the agent's state.
 *
 * Owns a [Recomposer] because a Compose hierarchy outside an Activity has no window to
 * inherit one from. It is driven by [AndroidUiDispatcher.CurrentThread]'s monotonic frame
 * clock so animations in the overlay tick against real frames rather than free-running.
 */
@Singleton
class OverlayController @Inject constructor(
    private val orchestrator: AgentOrchestrator,
) {

    private var overlay: AgentOverlay? = null
    private var recomposer: Recomposer? = null
    private var recomposerJob: Job? = null
    private var stateJob: Job? = null

    private var currentState by mutableStateOf<AgentState>(AgentState.Idle)
    private var expanded by mutableStateOf(false)

    /**
     * Begins mirroring agent state into a floating bubble.
     *
     * Safe to call repeatedly - a second call while already attached is ignored, which
     * matters because the hosting service can be restarted by the system.
     */
    fun attach(context: Context, scope: CoroutineScope) {
        if (overlay != null) return
        if (!AgentOverlay.canDrawOverlays(context)) return

        // The Recomposer must run on a thread with a frame clock. AndroidUiDispatcher
        // supplies both the dispatcher and the MonotonicFrameClock.
        val recomposerScope = CoroutineScope(AndroidUiDispatcher.CurrentThread)
        val newRecomposer = Recomposer(recomposerScope.coroutineContext)
        recomposerJob = recomposerScope.launch { newRecomposer.runRecomposeAndApplyChanges() }
        recomposer = newRecomposer

        val newOverlay = AgentOverlay(context.applicationContext, newRecomposer)
        overlay = newOverlay

        newOverlay.show {
            OverlayContent(
                state = currentState,
                expanded = expanded,
                onToggle = { expanded = !expanded },
                onStop = { orchestrator.stop() },
                onApprove = { orchestrator.confirm() },
                onDeny = { orchestrator.reject() },
            )
        }

        stateJob = scope.launch {
            orchestrator.state.collectLatest { state ->
                currentState = state
                // Auto-expand when the agent needs something. A collapsed bubble asking
                // for approval is a run that silently stalls.
                if (state is AgentState.AwaitingConfirmation || state is AgentState.AwaitingUserInput) {
                    expanded = true
                }
            }
        }
    }

    fun detach() {
        stateJob?.cancel()
        stateJob = null
        overlay?.hide()
        overlay = null
        recomposer?.cancel()
        recomposer = null
        recomposerJob?.cancel()
        recomposerJob = null
        expanded = false
    }
}
