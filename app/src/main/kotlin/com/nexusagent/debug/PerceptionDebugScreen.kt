package com.nexusagent.debug

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nexusagent.core.model.ScreenSnapshot

/**
 * Live view of what the agent sees.
 *
 * This is a development instrument, not product UI - but it is also where the project's
 * headline metric comes from. Point it at a real app and it reports exactly how many
 * nodes were discarded and how many bytes were saved, measured rather than estimated.
 */
@Composable
fun PerceptionDebugScreen(
    viewModel: PerceptionDebugViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val connected by viewModel.isConnected.collectAsStateWithLifecycle()
    val foreground by viewModel.foregroundPackage.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Perception", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Switch to another app, then come back and capture. " +
                "Or turn on live mode and watch it update as you move around.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (!connected) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                ),
                shape = RoundedCornerShape(16.dp),
            ) {
                Text(
                    "Accessibility service is off. Enable it on the Setup tab.",
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = viewModel::capture,
                enabled = connected && !state.isCapturing,
            ) { Text("Capture") }

            FilledTonalButton(
                onClick = viewModel::toggleAutoRefresh,
                enabled = connected,
            ) { Text(if (state.isAutoRefreshing) "Stop live" else "Live") }

            OutlinedButton(
                onClick = viewModel::captureScreenshot,
                enabled = connected && !state.isCapturing,
            ) { Text("Shot") }
        }

        if (state.isCapturing) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                CircularProgressIndicator(Modifier.height(18.dp))
                Text("Walking the tree...", style = MaterialTheme.typography.bodySmall)
            }
        }

        state.message?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Text(
            "foreground: ${foreground ?: "-"}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        AnimatedVisibility(visible = state.snapshot != null) {
            state.snapshot?.let { CompressionCard(it) }
        }

        AnimatedVisibility(visible = state.snapshot != null) {
            state.snapshot?.let { snapshot ->
                ElementsCard(
                    snapshot = snapshot,
                    onClick = viewModel::click,
                    onScroll = viewModel::scroll,
                    enabled = connected && !state.isCapturing,
                )
            }
        }

        AnimatedVisibility(visible = state.json.isNotEmpty()) {
            PayloadCard(state.json)
        }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun CompressionCard(snapshot: ScreenSnapshot) {
    val stats = snapshot.stats
    val hasBaseline = stats.rawBytes > 0

    val nodeProgress by animateFloatAsState(
        targetValue = (stats.nodeReductionPercent / 100f).coerceIn(0f, 1f),
        animationSpec = spring(stiffness = 220f),
        label = "nodeReduction",
    )

    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
        ),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                "%.1f%% fewer nodes".format(stats.nodeReductionPercent),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
            LinearProgressIndicator(
                progress = { nodeProgress },
                modifier = Modifier.fillMaxWidth(),
            )

            Metric("nodes", "${stats.rawNodeCount} -> ${stats.keptNodeCount}")
            if (hasBaseline) {
                Metric(
                    "payload",
                    "${stats.rawBytes.b()} -> ${stats.compressedBytes.b()}" +
                        "  (%.1f%% smaller)".format(stats.byteReductionPercent),
                )
            } else {
                // Live mode skips the baseline walk, so there is nothing to compare against.
                Metric("payload", stats.compressedBytes.b() + "  (baseline off in live mode)")
            }
            Metric("walk", "${stats.walkDurationMs} ms")
            Metric("app", snapshot.packageName)
            snapshot.activityName?.let { Metric("screen", it.substringAfterLast('.')) }
            Metric("sparse", if (snapshot.isSparse()) "yes - would attach a screenshot" else "no")
        }
    }
}

@Composable
private fun Metric(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall)
        Text(value, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
    }
}

/**
 * Every element the model would be offered, each one tappable for real.
 *
 * This is M3 made visible: pressing a row runs the identical code path the agent will use
 * once the reasoning layer is choosing the ids itself - same re-resolution, same fallback
 * ladder. Only the decision-maker differs.
 */
@Composable
private fun ElementsCard(
    snapshot: ScreenSnapshot,
    onClick: (Int) -> Unit,
    onScroll: (Boolean) -> Unit,
    enabled: Boolean,
) {
    Card(shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("${snapshot.elements.size} elements", style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(onClick = { onScroll(false) }, enabled = enabled) { Text("Up") }
                    OutlinedButton(onClick = { onScroll(true) }, enabled = enabled) { Text("Down") }
                }
            }

            Text(
                "Tap a row to dispatch a real click on that element.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // Bounded height with its own scroll: some screens produce 100+ elements and
            // an unbounded list here would make the page unusable.
            Column(
                Modifier
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                snapshot.elements.forEach { element ->
                    val label = element.contentDescription
                        ?: element.text
                        ?: "(${element.className})"

                    FilledTonalButton(
                        onClick = { onClick(element.id) },
                        enabled = enabled && element.clickable,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = "${element.id}  $label",
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PayloadCard(json: String) {
    Card(shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Payload sent to the model", style = MaterialTheme.typography.titleMedium)
            Box(
                Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                // Horizontal scroll rather than wrapping: the payload is one dense line
                // and wrapping it makes the structure impossible to read.
                Text(
                    text = json,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                )
            }
        }
    }
}

private fun Int.b(): String = if (this >= 1024) "%.1f KB".format(this / 1024f) else "$this B"
