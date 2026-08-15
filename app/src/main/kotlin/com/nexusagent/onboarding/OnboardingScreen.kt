package com.nexusagent.onboarding

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Accessibility
import androidx.compose.material.icons.filled.BatterySaver
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nexusagent.core.ui.component.PermissionCard

@Composable
fun OnboardingScreen(
    viewModel: OnboardingViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val serviceConnected by viewModel.serviceConnected.collectAsStateWithLifecycle()
    val foregroundPackage by viewModel.foregroundPackage.collectAsStateWithLifecycle()

    // Permissions are granted in system Settings, outside our process — nothing tells us
    // when the user comes back, so re-read everything on every resume.
    LifecycleResumeEffect(Unit) {
        viewModel.refresh()
        onPauseOrDispose { }
    }

    val micLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { viewModel.refresh() }

    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { viewModel.refresh() }

    val progress by animateFloatAsState(
        targetValue = state.grantedCount.toFloat() / state.totalCount,
        animationSpec = tween(durationMillis = 500),
        label = "onboardingProgress",
    )

    Column(
        // No systemBarsPadding here: the Scaffold in NexusApp already insets its content
        // for the status bar and the navigation bar. Applying both double-pads the top.
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = "NexusAgent",
            style = MaterialTheme.typography.displayLarge,
        )
        Text(
            text = "Give the agent the access it needs to see your screen and act on it.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Row(
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "${state.grantedCount}/${state.totalCount}",
                style = MaterialTheme.typography.labelLarge,
            )
        }

        // Reaching 5/5 needs to resolve into something. There is no next screen until the
        // command console lands in M6, so say so plainly rather than leaving a completed
        // wizard with no exit.
        AnimatedVisibility(visible = state.grantedCount == state.totalCount) {
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                ),
            ) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "Setup complete",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        "Every permission is granted and perception is running. " +
                            "The voice console arrives in the next milestone — for now, the " +
                            "card at the bottom is the live proof that the agent can see " +
                            "your screen.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }

        Spacer(Modifier.height(4.dp))

        PermissionCard(
            title = "Accessibility service",
            rationale = "The core permission. Lets NexusAgent read the layout of the app " +
                "on screen and perform taps for you.\n\n" +
                "Find NexusAgent under Settings → Accessibility → Downloaded apps, and turn it on.",
            icon = Icons.Default.Accessibility,
            granted = state.accessibilityEnabled,
            actionLabel = "Open Accessibility settings",
            onAction = { OemAutoStart.openAccessibilitySettings(context) },
        )

        PermissionCard(
            title = "Display over other apps",
            rationale = "Shows the floating status bubble while a task runs, so you can " +
                "watch what the agent is doing and stop it at any time.",
            icon = Icons.Default.Layers,
            granted = state.overlayGranted,
            actionLabel = "Grant overlay access",
            onAction = { OemAutoStart.openOverlaySettings(context) },
        )

        PermissionCard(
            title = "Microphone",
            rationale = "For speaking instructions instead of typing them. Speech is " +
                "recognised on-device where your phone supports it.",
            icon = Icons.Default.Mic,
            granted = state.micGranted,
            actionLabel = "Allow microphone",
            onAction = { micLauncher.launch(Manifest.permission.RECORD_AUDIO) },
        )

        PermissionCard(
            title = "Notifications",
            rationale = "Shows a persistent notification while a task is running, and " +
                "tells you when one finishes or needs your input.",
            icon = Icons.Default.Notifications,
            granted = state.notificationsGranted,
            actionLabel = "Allow notifications",
            onAction = { notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) },
        )

        // Only surfaced on manufacturers that actually need it — showing this on a Pixel
        // would be noise, and every extra step in a permission wizard costs completions.
        AnimatedVisibility(visible = state.isAggressiveOem) {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                PermissionCard(
                    title = "Unrestricted battery use",
                    rationale = "${state.manufacturer} devices aggressively close background " +
                        "apps, which can silently switch the accessibility service off " +
                        "mid-task. Exempting NexusAgent keeps it running.",
                    icon = Icons.Default.BatterySaver,
                    granted = state.batteryExempt,
                    actionLabel = "Allow unrestricted battery",
                    onAction = { OemAutoStart.requestIgnoreBatteryOptimizations(context) },
                )

                // Instructions rather than a button, because on ColorOS the autostart
                // activity requires a signature-level OEM permission and cannot be
                // launched by us at all. The button is offered as a best-effort attempt
                // on OEMs where it does work.
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    ),
                    shape = RoundedCornerShape(20.dp),
                ) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("One more on ${state.manufacturer}", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Optional for now — it matters once the agent starts running " +
                                "tasks in the background. Without it the service won't come " +
                                "back after a reboot.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        OemAutoStart.autoStartInstructions?.let { steps ->
                            Text(
                                text = steps,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (!OemAutoStart.isAutoStartDeepLinkBlocked) {
                            androidx.compose.material3.TextButton(
                                onClick = { OemAutoStart.openAutoStartSettings(context) },
                            ) {
                                Text("Open Autostart settings")
                            }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        PerceptionStatusCard(
            enabledInSettings = state.accessibilityEnabled,
            connected = serviceConnected,
            foregroundPackage = foregroundPackage,
        )

        Spacer(Modifier.height(24.dp))
    }
}

/**
 * M1's acceptance test, surfaced in the UI rather than buried in Logcat.
 *
 * Enable the service, switch to any app, come back: the package name below should be
 * the app you just visited. That single line proves perception is live.
 *
 * Three states, not two. [enabledInSettings] is the source of truth — it reads the secure
 * setting the user actually toggled. [connected] only becomes true once the system has
 * built and bound the service, which lags a process restart by a moment. Treating
 * `!connected` as "offline" made the card claim perception was dead while `dumpsys`
 * showed the service running.
 */
@Composable
private fun PerceptionStatusCard(
    enabledInSettings: Boolean,
    connected: Boolean,
    foregroundPackage: String?,
) {
    val (title, detail) = when {
        connected -> "Perception online" to
            "Switch to another app and come back — the app you visited appears below."
        enabledInSettings -> "Enabled — waiting for the system to bind" to
            "The service is switched on. Android rebinds it a moment after the app " +
                "restarts; this flips to online on its own."
        else -> "Perception offline" to
            "Turn on the accessibility service above to bring perception online."
    }

    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = when {
                connected -> MaterialTheme.colorScheme.secondaryContainer
                enabledInSettings -> MaterialTheme.colorScheme.tertiaryContainer
                else -> MaterialTheme.colorScheme.surfaceVariant
            },
        ),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = detail,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "last seen: ${foregroundPackage ?: "—"}",
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}
