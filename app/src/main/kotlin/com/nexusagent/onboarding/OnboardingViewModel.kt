package com.nexusagent.onboarding

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import com.nexusagent.agent.perception.AgentServiceBridge
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

data class OnboardingUiState(
    val accessibilityEnabled: Boolean = false,
    val overlayGranted: Boolean = false,
    val micGranted: Boolean = false,
    val notificationsGranted: Boolean = false,
    val batteryExempt: Boolean = false,
    val isAggressiveOem: Boolean = false,
    val manufacturer: String = "",
) {
    /** Only accessibility is strictly required to reach M1's acceptance test. */
    val readyForM1: Boolean get() = accessibilityEnabled

    val grantedCount: Int
        get() = listOf(
            accessibilityEnabled,
            overlayGranted,
            micGranted,
            notificationsGranted,
            !isAggressiveOem || batteryExempt,
        ).count { it }

    val totalCount: Int get() = 5
}

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val _uiState = MutableStateFlow(OnboardingUiState())
    val uiState: StateFlow<OnboardingUiState> = _uiState.asStateFlow()

    /** Live from the accessibility service — proves perception is actually running. */
    val serviceConnected: StateFlow<Boolean> = AgentServiceBridge.connected
    val foregroundPackage: StateFlow<String?> = AgentServiceBridge.foregroundPackage

    init {
        refresh()
    }

    /**
     * Re-reads every permission.
     *
     * Must be called on resume, not just at construction: the user grants these in system
     * Settings, outside our process, so nothing notifies us when they come back.
     */
    fun refresh() {
        _uiState.value = OnboardingUiState(
            accessibilityEnabled = AgentServiceBridge.isEnabledInSettings(context),
            overlayGranted = OemAutoStart.canDrawOverlays(context),
            micGranted = context.hasPermission(Manifest.permission.RECORD_AUDIO),
            notificationsGranted = context.hasPermission(Manifest.permission.POST_NOTIFICATIONS),
            batteryExempt = OemAutoStart.isIgnoringBatteryOptimizations(context),
            isAggressiveOem = OemAutoStart.isAggressiveOem,
            manufacturer = OemAutoStart.manufacturerLabel,
        )
    }

    private fun Context.hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
}
