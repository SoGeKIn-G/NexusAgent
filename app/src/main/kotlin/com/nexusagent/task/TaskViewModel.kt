package com.nexusagent.task

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nexusagent.agent.AgentService
import com.nexusagent.agent.perception.AgentServiceBridge
import com.nexusagent.agent.runtime.orchestrator.AgentOrchestrator
import com.nexusagent.agent.runtime.reasoning.ReasoningRepository
import com.nexusagent.core.model.AgentState
import com.nexusagent.voice.SpeechEvent
import com.nexusagent.voice.SpeechInput
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class TaskViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val orchestrator: AgentOrchestrator,
    reasoning: ReasoningRepository,
) : ViewModel() {

    val state: StateFlow<AgentState> = orchestrator.state

    val perceptionReady: StateFlow<Boolean> = AgentServiceBridge.connected

    val hasApiKey: StateFlow<Boolean> = reasoning.settings
        .map { it.hasApiKey }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    private val _goal = MutableStateFlow("")
    val goal: StateFlow<String> = _goal.asStateFlow()

    private val _answer = MutableStateFlow("")
    val answer: StateFlow<String> = _answer.asStateFlow()

    private val speech = SpeechInput(context)

    private val _listening = MutableStateFlow(false)
    val listening: StateFlow<Boolean> = _listening.asStateFlow()

    private val _amplitude = MutableStateFlow(0f)
    val amplitude: StateFlow<Float> = _amplitude.asStateFlow()

    private val _voiceError = MutableStateFlow<String?>(null)
    val voiceError: StateFlow<String?> = _voiceError.asStateFlow()

    private var listenJob: Job? = null

    fun onGoalChanged(value: String) { _goal.value = value }
    fun onAnswerChanged(value: String) { _answer.value = value }

    /**
     * Toggles dictation.
     *
     * Partial results stream straight into the goal field so the user watches their words
     * land; the final transcript replaces them. Deliberately does *not* auto-run the task
     * - speech recognition mishears, and an agent that starts tapping on a misheard
     * command is a bad surprise. The user reads it and presses Run.
     */
    fun toggleListening() {
        if (listenJob?.isActive == true) {
            listenJob?.cancel()
            listenJob = null
            _listening.value = false
            _amplitude.value = 0f
            return
        }

        _voiceError.value = null
        listenJob = viewModelScope.launch {
            speech.listen().collect { event ->
                when (event) {
                    SpeechEvent.Listening -> _listening.value = true
                    is SpeechEvent.Amplitude -> _amplitude.value = event.level
                    is SpeechEvent.Partial -> _goal.value = event.text
                    is SpeechEvent.Final -> {
                        _goal.value = event.text
                        _listening.value = false
                        _amplitude.value = 0f
                    }
                    is SpeechEvent.Failed -> {
                        _voiceError.value = event.message
                        _listening.value = false
                        _amplitude.value = 0f
                    }
                }
            }
            // The flow closes on its own after a final result or an error; clear the
            // listening flag in case the collector ended without either.
            _listening.value = false
            _amplitude.value = 0f
        }
    }

    fun dismissVoiceError() { _voiceError.value = null }

    /**
     * Runs through the foreground service rather than calling the orchestrator directly.
     *
     * The service is what keeps the process alive once this UI goes to the background -
     * which happens within a second of starting, because the agent's whole job is to
     * operate other apps.
     */
    fun start() {
        val goal = _goal.value.trim()
        if (goal.isEmpty()) return
        AgentService.start(context, goal)
    }

    fun runSuggestion(suggestion: String) {
        _goal.value = suggestion
        AgentService.start(context, suggestion)
    }

    fun stop() {
        AgentService.stop(context)
        orchestrator.stop()
    }

    fun confirm() = orchestrator.confirm()
    fun reject() = orchestrator.reject()

    fun submitAnswer() {
        val text = _answer.value.trim()
        if (text.isEmpty()) return
        orchestrator.answer(text)
        _answer.value = ""
    }

    fun reset() {
        orchestrator.reset()
        _goal.value = ""
    }

    companion object {
        /**
         * Ordered easiest-first, which is also most-reliable-first: the alarm resolves
         * through an Intent in a single step, while the last needs several UI hops.
         */
        val SUGGESTIONS = listOf(
            "Set an alarm for 6:30 am",
            "Turn on aeroplane mode",
            "Open Chrome and search for Kotlin coroutines",
            "Open display settings and turn on dark mode",
        )
    }
}
