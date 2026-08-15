package com.nexusagent.history

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import com.nexusagent.core.data.CompressionSummary
import com.nexusagent.core.data.RunEntity
import com.nexusagent.core.data.StepEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Recorded runs, and the aggregate they add up to.
 *
 * The card at the top is the point of this screen. Individual traces are useful for
 * debugging a flaky task; the aggregate is the project's headline claim, computed over
 * every step ever recorded rather than asserted from one lucky screen.
 */
@Composable
fun HistoryScreen(viewModel: HistoryViewModel = hiltViewModel()) {
    val runs by viewModel.runs.collectAsStateWithLifecycle()
    val compression by viewModel.compression.collectAsStateWithLifecycle()
    val expandedRunId by viewModel.expandedRunId.collectAsStateWithLifecycle()
    val steps by viewModel.steps.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 16.dp),
    ) {
        item {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("History", style = MaterialTheme.typography.headlineMedium)
                if (runs.isNotEmpty()) {
                    OutlinedButton(onClick = viewModel::clear) { Text("Clear") }
                }
            }
        }

        compression?.takeIf { it.steps > 0 }?.let { summary ->
            item { CompressionCard(summary) }
        }

        if (runs.isEmpty()) {
            item {
                Text(
                    "No runs yet. Give the agent a goal on the Task tab and it will be " +
                        "recorded here, step by step.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        items(runs, key = { it.id }) { run ->
            RunCard(
                run = run,
                expanded = expandedRunId == run.id,
                steps = if (expandedRunId == run.id) steps else emptyList(),
                onClick = { viewModel.toggle(run.id) },
            )
        }

        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun CompressionCard(summary: CompressionSummary) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
        ),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "%.1f%% smaller payloads".format(summary.avgByteReduction),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                "Measured across ${summary.steps} recorded steps",
                style = MaterialTheme.typography.bodyMedium,
            )
            Metric("nodes discarded", "%.1f%%".format(summary.avgNodeReduction))
            Metric(
                "bytes",
                "${summary.totalRawBytes.kb()} -> ${summary.totalCompressedBytes.kb()}",
            )
            Metric("avg walk", "%.0f ms".format(summary.avgWalkMs))
        }
    }
}

@Composable
private fun RunCard(
    run: RunEntity,
    expanded: Boolean,
    steps: List<StepEntity>,
    onClick: () -> Unit,
) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = when (run.status) {
                "done" -> MaterialTheme.colorScheme.surfaceVariant
                "failed" -> MaterialTheme.colorScheme.errorContainer
                else -> MaterialTheme.colorScheme.surface
            },
        ),
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(run.goal, style = MaterialTheme.typography.titleMedium)

            Text(
                buildString {
                    append(run.status)
                    append(" · ").append(run.stepCount).append(" steps")
                    // No duration for interrupted runs: `endedAt` is stamped when the
                    // orphan is resolved, which can be days after the run actually died,
                    // producing a meaningless "68928s".
                    if (run.status != "interrupted") {
                        run.endedAt?.let { append(" · ").append(((it - run.startedAt) / 1000)).append("s") }
                    }
                    append(" · ").append(run.startedAt.time())
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            run.summary?.let {
                Text(it, style = MaterialTheme.typography.bodySmall)
            }

            AnimatedVisibility(visible = expanded) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Spacer(Modifier.height(4.dp))
                    steps.forEach { step ->
                        Column {
                            Text(
                                "${step.index + 1}. ${step.thought}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Text(
                                "   ${step.action} -> ${step.result}  " +
                                    "(${step.latencyMs} ms, ${step.packageName})",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (step.rawNodeCount > 0) {
                                Text(
                                    "   nodes ${step.rawNodeCount} -> ${step.keptNodeCount}, " +
                                        "walk ${step.walkDurationMs} ms",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    if (steps.isEmpty()) {
                        Text(
                            "No steps recorded for this run.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
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

private fun Long.kb(): String =
    if (this >= 1024) "%.1f KB".format(this / 1024f) else "$this B"

private fun Long.time(): String =
    SimpleDateFormat("d MMM HH:mm", Locale.getDefault()).format(Date(this))
