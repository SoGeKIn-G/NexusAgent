package com.nexusagent.agent.perception

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.text.TextUtils
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Observable state of the accessibility service, readable from the UI.
 *
 * The system — not us — decides when [NexusAccessibilityService] is created and
 * destroyed, so there is no lifecycle owner to scope this to and no binder to hold.
 * A process-level singleton is the honest representation of a process-level fact.
 */
object AgentServiceBridge {

    private val _connected = MutableStateFlow(false)

    /** True while the service is bound. Flips to false if an OEM power manager kills us. */
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    private val _foregroundPackage = MutableStateFlow<String?>(null)

    /** Package name of the app currently on screen. */
    val foregroundPackage: StateFlow<String?> = _foregroundPackage.asStateFlow()

    internal fun setConnected(value: Boolean) {
        _connected.value = value
        // Deliberately does NOT clear foregroundPackage. The service can be unbound and
        // rebound by the system without being destroyed, and wiping the last-known screen
        // on every such blip made the UI flicker back to "nothing seen yet" while the
        // service was in fact still delivering events.
    }

    internal fun setForegroundPackage(pkg: String) {
        _foregroundPackage.value = pkg
    }

    /**
     * Whether the user has enabled the service in system Settings.
     *
     * Reads the secure setting rather than trusting [connected], because that flag only
     * becomes accurate once the service has actually been created. On a cold start the
     * setting is the source of truth.
     *
     * Note the delimiter handling: the setting is a `:`-separated list of
     * `package/class` entries, and a naive `contains()` would match a different app
     * whose component name merely shares our prefix.
     */
    fun isEnabledInSettings(context: Context): Boolean {
        val expected = ComponentName(
            context.packageName,
            NexusAccessibilityService::class.java.name,
        ).flattenToString()

        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false

        val splitter = TextUtils.SimpleStringSplitter(':').apply { setString(enabled) }
        return splitter.any { it.equals(expected, ignoreCase = true) }
    }
}
