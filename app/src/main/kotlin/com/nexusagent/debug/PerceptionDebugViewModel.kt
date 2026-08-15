package com.nexusagent.debug

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nexusagent.agent.perception.PerceptionRepository
import com.nexusagent.agent.perception.SnapshotResult
import com.nexusagent.agent.runtime.execution.ExecutionRepository
import com.nexusagent.core.model.ActionResult
import com.nexusagent.core.model.AgentAction
import com.nexusagent.core.model.ScreenSnapshot
import com.nexusagent.core.model.ScrollDirection
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

data class PerceptionDebugState(
    val snapshot: ScreenSnapshot? = null,
    val json: String = "",
    val screenshotBytes: Int? = null,
    val message: String? = null,
    val isCapturing: Boolean = false,
    val isAutoRefreshing: Boolean = false,
)

@HiltViewModel
class PerceptionDebugViewModel @Inject constructor(
    private val perception: PerceptionRepository,
    private val execution: ExecutionRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(PerceptionDebugState())
    val state: StateFlow<PerceptionDebugState> = _state.asStateFlow()

    val isConnected = perception.isConnected
    val foregroundPackage = perception.foregroundPackage

    private var autoRefreshJob: Job? = null

    fun capture() {
        viewModelScope.launch {
            _state.value = _state.value.copy(isCapturing = true, message = null)
            applyResult(perception.snapshot(measureBaseline = true))
            _state.value = _state.value.copy(isCapturing = false)
        }
    }

    /**
     * Polls the screen so you can watch the snapshot change while switching apps.
     *
     * Deliberately a debug-only affordance. The real agent never polls - it captures once
     * per step, after the screen settles. Polling here is acceptable because a human is
     * watching it; polling in the loop would be the perf mistake the whole perception
     * design exists to avoid.
     */
    fun toggleAutoRefresh() {
        if (autoRefreshJob?.isActive == true) {
            autoRefreshJob?.cancel()
            autoRefreshJob = null
            _state.value = _state.value.copy(isAutoRefreshing = false)
            return
        }

        _state.value = _state.value.copy(isAutoRefreshing = true)
        autoRefreshJob = viewModelScope.launch {
            while (isActive) {
                // Baseline measurement stays on. It roughly doubles the walk (tens of
                // milliseconds on a debug screen a human is watching), but without it
                // there is no byte-reduction figure for apps other than this one -
                // and measuring compression only against our own UI would be worthless.
                applyResult(perception.snapshot(measureBaseline = true))
                delay(AUTO_REFRESH_INTERVAL_MS)
            }
        }
    }

    fun captureScreenshot() {
        viewModelScope.launch {
            _state.value = _state.value.copy(isCapturing = true)
            val shot = perception.screenshot()
            _state.value = _state.value.copy(
                isCapturing = false,
                screenshotBytes = shot?.bytes,
                message = if (shot == null) {
                    "Screenshot refused - secure window, or the system rate-limited us."
                } else {
                    "Captured ${shot.width}x${shot.height}, ${shot.bytes / 1024} KB JPEG."
                },
            )
        }
    }

    /**
     * Fires a real action against the last captured screen.
     *
     * Re-snapshots first. The ids on screen came from a capture that may be seconds old,
     * and acting on a stale id targets whatever now occupies that index - which is the
     * exact failure mode the resolver exists to avoid, so it would be perverse to
     * introduce it here.
     */
    fun runAction(action: AgentAction) {
        viewModelScope.launch {
            _state.value = _state.value.copy(isCapturing = true)

            val fresh = perception.snapshot(measureBaseline = false)
            if (fresh is SnapshotResult.Success) execution.bind(fresh.snapshot)

            val result = execution.execute(action)

            perception.awaitScreenSettled()
            applyResult(perception.snapshot(measureBaseline = true))

            _state.value = _state.value.copy(
                isCapturing = false,
                message = when (result) {
                    ActionResult.Success -> "Done: $action"
                    ActionResult.Ineffective -> "Dispatched, but the screen didn't change."
                    is ActionResult.Failure -> "Failed: ${result.reason}"
                },
            )
        }
    }

    fun click(elementId: Int) = runAction(AgentAction.Click(elementId))

    fun scroll(forward: Boolean) = runAction(
        AgentAction.Scroll(if (forward) ScrollDirection.FORWARD else ScrollDirection.BACKWARD),
    )

    private fun applyResult(result: SnapshotResult) {
        _state.value = when (result) {
            is SnapshotResult.Success ->
                _state.value.copy(snapshot = result.snapshot, json = result.json, message = null)

            SnapshotResult.ServiceUnavailable ->
                _state.value.copy(message = "Accessibility service is off.")

            SnapshotResult.NoActiveWindow ->
                _state.value.copy(message = "No readable window right now - try again.")
        }
    }

    override fun onCleared() {
        autoRefreshJob?.cancel()
        super.onCleared()
    }

    private companion object {
        const val AUTO_REFRESH_INTERVAL_MS = 500L
    }
}
