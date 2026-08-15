package com.nexusagent.onboarding

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.getSystemService

/**
 * Works around aggressive OEM power management.
 *
 * Stock Android keeps an enabled accessibility service alive. Several manufacturers do
 * not: MIUI, ColorOS, Funtouch and others kill background processes on their own
 * schedule and will silently disable the service after a reboot or a few idle hours.
 * That failure is invisible until a task mysteriously stops working — during a demo,
 * usually.
 *
 * The autostart screens below are undocumented, differ across OS versions, and are
 * frequently renamed, so **every launch here is best-effort**: try the candidates, fall
 * back to the app's system settings page, and never crash if none resolve.
 */
object OemAutoStart {

    /** True on manufacturers known to need manual whitelisting. */
    val isAggressiveOem: Boolean
        get() = Build.MANUFACTURER.lowercase() in AGGRESSIVE_MANUFACTURERS

    val manufacturerLabel: String
        get() = Build.MANUFACTURER.replaceFirstChar { it.uppercase() }

    private val AGGRESSIVE_MANUFACTURERS = setOf(
        "xiaomi", "redmi", "poco",
        "oppo", "realme", "oneplus",
        "vivo", "iqoo",
        "huawei", "honor",
        "meizu", "letv", "asus",
    )

    /**
     * Candidate autostart / background-permission screens, most specific first.
     * Many will not resolve on any given device; that is expected.
     *
     * Verified on OPPO CPH2761 / ColorOS 16 (Android 16): the modern activities live in
     * `com.oplus.battery`, not the older `com.coloros.safecenter`. They exist and resolve,
     * but launching one throws
     *
     *     SecurityException: requires oplus.permission.OPLUS_COMPONENT_SAFE
     *
     * — a signature-level OEM permission no third-party app can hold. So on ColorOS this
     * screen is simply unreachable programmatically; see [autoStartInstructions].
     */
    private val CANDIDATES: Map<String, List<ComponentName>> = mapOf(
        "xiaomi" to listOf(
            ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
        ),
        "oppo" to listOf(
            ComponentName("com.oplus.battery", "com.oplus.startupapp.view.StartupAppListActivity"),
            ComponentName("com.oplus.battery", "com.oplus.startupapp.view.AssociateStartActivity"),
            ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
            ComponentName("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"),
            ComponentName("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity"),
        ),
        "vivo" to listOf(
            ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
            ComponentName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"),
        ),
        "huawei" to listOf(
            ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
            ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity"),
        ),
        "oneplus" to listOf(
            ComponentName("com.oneplus.security", "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity"),
        ),
        "asus" to listOf(
            ComponentName("com.asus.mobilemanager", "com.asus.mobilemanager.autostart.AutoStartActivity"),
        ),
    ).withDefaultAliases()

    /** Sub-brands share their parent's system apps. */
    private fun Map<String, List<ComponentName>>.withDefaultAliases(): Map<String, List<ComponentName>> {
        val aliases = mapOf(
            "redmi" to "xiaomi",
            "poco" to "xiaomi",
            "realme" to "oppo",
            "iqoo" to "vivo",
            "honor" to "huawei",
        )
        return this + aliases.mapNotNull { (alias, parent) ->
            this[parent]?.let { alias to it }
        }
    }

    /**
     * Manufacturer-specific manual route to the autostart setting.
     *
     * Shown as text because on several OEMs — ColorOS confirmed — the screen cannot be
     * opened programmatically at all. A button that silently lands the user somewhere
     * else is worse than an honest instruction.
     */
    /**
     * OEMs whose autostart screen is gated behind a signature-level permission, so no
     * amount of intent-juggling will open it. Confirmed on ColorOS 16, which throws
     * `SecurityException: requires oplus.permission.OPLUS_COMPONENT_SAFE`.
     *
     * On these, offering a button is worse than offering none: it dumps the user on an
     * unrelated screen and makes the app look broken.
     */
    val isAutoStartDeepLinkBlocked: Boolean
        get() = Build.MANUFACTURER.lowercase() in setOf("oppo", "realme", "oneplus")

    val autoStartInstructions: String?
        get() = when (Build.MANUFACTURER.lowercase()) {
            "oppo", "realme", "oneplus" ->
                "ColorOS only allows this screen to be opened by the system, so there's no " +
                    "button for it. Open Settings, tap the search box, and look for " +
                    "\"Auto-launch\" or \"Startup manager\", then enable NexusAgent."
            "xiaomi", "redmi", "poco" ->
                "Settings → Apps → Manage apps → NexusAgent → Autostart."
            "vivo", "iqoo" ->
                "Settings → Battery → Background power consumption management → NexusAgent."
            "huawei", "honor" ->
                "Settings → Apps → NexusAgent → Battery → App launch → Manage manually."
            else -> null
        }

    /**
     * Tries to open the OEM autostart screen, falling back to the app's settings page.
     *
     * @return true only if an OEM-specific screen actually opened. On ColorOS this is
     *   always false — the activities require a signature-level permission — which is why
     *   [autoStartInstructions] exists.
     */
    fun openAutoStartSettings(context: Context): Boolean {
        val candidates = CANDIDATES[Build.MANUFACTURER.lowercase()].orEmpty()

        for (component in candidates) {
            val intent = Intent().apply {
                this.component = component
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            // resolveActivity only proves the activity exists. Several OEMs let it
            // resolve and then throw SecurityException on launch, so the try/catch is
            // load-bearing, not defensive padding.
            if (intent.resolveActivity(context.packageManager) != null) {
                runCatching { context.startActivity(intent) }.onSuccess { return true }
            }
        }

        openAppSettings(context)
        return false
    }

    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val pm = context.getSystemService<PowerManager>() ?: return false
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    /**
     * Asks to be exempted from Doze. Uses the direct-request intent, falling back to the
     * general list — some OEMs block the direct request entirely.
     */
    fun requestIgnoreBatteryOptimizations(context: Context) {
        val direct = Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:${context.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        if (runCatching { context.startActivity(direct) }.isSuccess) return

        val list = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(list) }
    }

    fun openAccessibilitySettings(context: Context) {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    fun openOverlaySettings(context: Context) {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${context.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }.onFailure { openAppSettings(context) }
    }

    fun openAppSettings(context: Context) {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:${context.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    fun canDrawOverlays(context: Context): Boolean = Settings.canDrawOverlays(context)
}
