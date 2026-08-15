package com.nexusagent.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nexusagent.agent.runtime.reasoning.AgentSettings
import com.nexusagent.agent.runtime.reasoning.ProviderId
import com.nexusagent.agent.runtime.reasoning.ReasoningRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val reasoning: ReasoningRepository,
) : ViewModel() {

    val settings: StateFlow<AgentSettings> = reasoning.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AgentSettings())

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    /**
     * Held in memory only while the user is typing. Never written back into the field
     * from storage - the stored key is encrypted and there is no reason to ever decrypt
     * it for display.
     */
    private val _draftKey = MutableStateFlow("")
    val draftKey: StateFlow<String> = _draftKey.asStateFlow()

    // Unlike the key, these are not secret, so they're seeded from storage and shown
    // back to the user - editing an endpoint you can't see is needlessly hostile.
    private val _draftBaseUrl = MutableStateFlow("")
    val draftBaseUrl: StateFlow<String> = _draftBaseUrl.asStateFlow()

    private val _draftModel = MutableStateFlow("")
    val draftModel: StateFlow<String> = _draftModel.asStateFlow()

    init {
        viewModelScope.launch {
            val current = reasoning.settings.first()
            _draftBaseUrl.value = current.baseUrl
            _draftModel.value = current.model
        }
    }

    fun onKeyChanged(value: String) {
        _draftKey.value = value
    }

    fun onBaseUrlChanged(value: String) {
        _draftBaseUrl.value = value
    }

    fun onModelChanged(value: String) {
        _draftModel.value = value
    }

    /**
     * Saves the key together with the endpoint and model.
     *
     * One button rather than three: a key belonging to a different endpoint than the one
     * stored is the single most confusing failure mode here, and it surfaces as an
     * unexplained 401.
     */
    fun saveKey() {
        val key = _draftKey.value.trim()
        viewModelScope.launch {
            reasoning.settingsStore.setBaseUrl(_draftBaseUrl.value)
            _draftModel.value.trim().takeIf { it.isNotBlank() }?.let {
                reasoning.settingsStore.setModel(it)
            }
            if (key.isNotBlank()) {
                reasoning.settingsStore.setApiKey(key)
                _draftKey.value = ""
                _message.value = "Key saved and encrypted."
            } else {
                _message.value = "Endpoint and model saved."
            }
        }
    }

    fun clearKey() {
        viewModelScope.launch {
            reasoning.settingsStore.setApiKey("")
            _message.value = "Key removed."
        }
    }

    fun setProvider(provider: ProviderId) {
        viewModelScope.launch { reasoning.settingsStore.setProvider(provider) }
    }

    fun setMaxSteps(steps: Int) {
        viewModelScope.launch { reasoning.settingsStore.setMaxSteps(steps) }
    }

    fun setConfirmDestructive(enabled: Boolean) {
        viewModelScope.launch { reasoning.settingsStore.setConfirmDestructive(enabled) }
    }

    fun dismissMessage() {
        _message.value = null
    }
}
