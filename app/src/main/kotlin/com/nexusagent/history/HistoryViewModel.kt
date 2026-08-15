package com.nexusagent.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nexusagent.core.data.CompressionSummary
import com.nexusagent.core.data.HistoryRepository
import com.nexusagent.core.data.RunEntity
import com.nexusagent.core.data.StepEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class HistoryViewModel @Inject constructor(
    private val history: HistoryRepository,
) : ViewModel() {

    val runs: StateFlow<List<RunEntity>> = history.runs
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val compression: StateFlow<CompressionSummary?> = history.compression
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _expandedRunId = MutableStateFlow<Long?>(null)
    val expandedRunId: StateFlow<Long?> = _expandedRunId.asStateFlow()

    /**
     * Steps for whichever run is open.
     *
     * `flatMapLatest` so opening a second run cancels the first query rather than leaving
     * both collecting - the alternative leaks a database observer per row the user taps.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val steps: StateFlow<List<StepEntity>> = _expandedRunId
        .flatMapLatest { id -> if (id == null) flowOf(emptyList()) else history.steps(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun toggle(runId: Long) {
        _expandedRunId.value = if (_expandedRunId.value == runId) null else runId
    }

    fun clear() {
        viewModelScope.launch { history.clear() }
    }
}
