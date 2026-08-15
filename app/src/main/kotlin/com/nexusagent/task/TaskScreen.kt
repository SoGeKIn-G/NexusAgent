package com.nexusagent.task

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nexusagent.core.model.AgentState
import com.nexusagent.core.model.RunContext
import com.nexusagent.core.ui.component.MicOrb
import com.nexusagent.core.ui.component.OrbState

/**
 * The task console.
 *
 * Type a goal, watch the agent work. Once the run starts this screen is almost always in
 * the background - the agent is by definition operating some other app - so its real job
 * is to be a good place to *return* to: what was asked, what has been tried, and a stop
 * button that is never more than one tap away.
 */
@Composable
fun TaskScreen(viewModel: TaskViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val goal by viewModel.goal.collectAsStateWithLifecycle()
    val answer by viewModel.answer.collectAsStateWithLifecycle()
    val ready by viewModel.perceptionReady.collectAsStateWithLifecycle()
    val hasKey by viewModel.hasApiKey.collectAsStateWithLifecycle()
    val listening by viewModel.listening.collectAsStateWithLifecycle()
    val amplitude by viewModel.amplitude.collectAsStateWithLifecycle()
    val voiceError by viewModel.voiceError.collectAsStateWithLifecycle()

    val busy = state is AgentState.Planning ||
        state is AgentState.Executing ||
        state is AgentState.Verifying ||
        state is AgentState.Retrying

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("What should I do?", style = MaterialTheme.typography.headlineMedium)

        // The orb doubles as the run indicator: it keeps moving through Thinking and
        // Acting, so the screen never looks frozen during a multi-second model call.
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            MicOrb(
                state = when {
                    listening -> OrbState.Listening
                    state is AgentState.Executing -> OrbState.Acting
                    busy -> OrbState.Thinking
                    else -> OrbState.Idle
                },
                amplitude = amplitude,
                onClick = {
                    if (busy) viewModel.stop() else viewModel.toggleListening()
                },
            )
        }

        voiceError?.let { message ->
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.weight(1f),
                )
                OutlinedButton(onClick = viewModel::dismissVoiceError) { Text("OK") }
            }
        }

        if (!ready || !hasKey) {
            BlockerCard(
                text = when {
                    !ready -> "Turn on the accessibility service on the Setup tab first."
                    else -> "Add a Gemini API key on the Settings tab first."
                },
            )
        }

        OutlinedTextField(
            value = goal,
            onValueChange = viewModel::onGoalChanged,
            label = { Text("Goal") },
            placeholder = { Text("Set an alarm for 6:30 am") },
            modifier = Modifier.fillMaxWidth(),
            enabled = !busy,
            minLines = 2,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = viewModel::start,
                enabled = ready && hasKey && !busy && goal.isNotBlank(),
            ) { Text("Run") }

            if (busy || state is AgentState.AwaitingConfirmation || state is AgentState.AwaitingUserInput) {
                OutlinedButton(onClick = viewModel::stop) { Text("Stop") }
            }
        }

        AnimatedVisibility(visible = !busy && state is AgentState.Idle) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Try one of these", style = MaterialTheme.typography.labelLarge)
                TaskViewModel.SUGGESTIONS.forEach { suggestion ->
                    AssistChip(
                        onClick = { viewModel.runSuggestion(suggestion) },
                        label = { Text(suggestion) },
                        enabled = ready && hasKey,
                    )
                }
            }
        }

        StatusCard(state = state, busy = busy)

        when (val current = state) {
            is AgentState.AwaitingConfirmation -> ConfirmationCard(
                prompt = current.prompt,
                onApprove = viewModel::confirm,
                onDecline = viewModel::reject,
            )

            is AgentState.AwaitingUserInput -> AnswerCard(
                question = current.question,
                answer = answer,
                onAnswerChanged = viewModel::onAnswerChanged,
                onSubmit = viewModel::submitAnswer,
            )

            is AgentState.Done, is AgentState.Failed ->
                OutlinedButton(onClick = viewModel::reset) { Text("New task") }

            else -> Unit
        }

        currentRun(state)?.let { run ->
            if (run.history.isNotEmpty()) StepsCard(run)
        }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun BlockerCard(text: String) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Text(text, Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun StatusCard(state: AgentState, busy: Boolean) {
    val (title, detail) = when (state) {
        AgentState.Idle -> "Idle" to "Give me a goal and I'll carry it out."
        is AgentState.Listening -> "Listening" to state.partialTranscript
        is AgentState.Planning -> "Thinking" to "Step ${state.run.stepIndex + 1}"
        // Counted down explicitly: a silent minute-long wait is indistinguishable from a
        // hang, and this is the single most common reason a run appears stuck.
        is AgentState.Retrying ->
            "Waiting ${(state.retryInMillis / 1000) + 1}s" to
                "${state.reason} (attempt ${state.attempt} of ${state.maxAttempts})"
        is AgentState.Executing -> "Acting" to state.decision.thought
        is AgentState.Verifying -> "Checking the screen changed" to state.decision.thought
        is AgentState.AwaitingConfirmation -> "Waiting for you" to state.prompt
        is AgentState.AwaitingUserInput -> "Waiting for you" to state.question
        is AgentState.Done -> "Done" to state.summary
        is AgentState.Failed -> "Stopped" to state.reason
    }

    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = when (state) {
                is AgentState.Done -> MaterialTheme.colorScheme.secondaryContainer
                is AgentState.Failed -> MaterialTheme.colorScheme.errorContainer
                else -> MaterialTheme.colorScheme.surfaceVariant
            },
        ),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (busy) ThinkingPulse()
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            if (detail.isNotBlank()) {
                Text(detail, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

/**
 * A breathing dot shown while the agent is working.
 *
 * A run spends seconds at a time in a network call with nothing visibly happening; without
 * some sign of life the app reads as hung. Placeholder for the full mic orb in M6.
 */
@Composable
private fun ThinkingPulse() {
    val transition = rememberInfiniteTransition(label = "pulse")
    val scale by transition.animateFloat(
        initialValue = 0.55f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "pulseScale",
    )
    val primary = MaterialTheme.colorScheme.primary
    val secondary = MaterialTheme.colorScheme.secondary

    Canvas(Modifier.size(18.dp)) {
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(secondary, primary),
                center = Offset(size.width / 2, size.height / 2),
                radius = size.minDimension / 2 * scale,
            ),
            radius = size.minDimension / 2 * scale,
        )
    }
}

@Composable
private fun ConfirmationCard(prompt: String, onApprove: () -> Unit, onDecline: () -> Unit) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Confirm", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(prompt, style = MaterialTheme.typography.bodyMedium)
            Text(
                "This action can't be undone by the next step, so it needs your approval.",
                style = MaterialTheme.typography.labelSmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = onApprove) { Text("Allow") }
                OutlinedButton(onClick = onDecline) { Text("Don't") }
            }
        }
    }
}

@Composable
private fun AnswerCard(
    question: String,
    answer: String,
    onAnswerChanged: (String) -> Unit,
    onSubmit: () -> Unit,
) {
    Card(shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(question, style = MaterialTheme.typography.bodyLarge)
            OutlinedTextField(
                value = answer,
                onValueChange = onAnswerChanged,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            Button(onClick = onSubmit, enabled = answer.isNotBlank()) { Text("Send") }
        }
    }
}

@Composable
private fun StepsCard(run: RunContext) {
    Card(shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("${run.history.size} steps", style = MaterialTheme.typography.titleMedium)
            run.history.forEach { step ->
                Column {
                    Text(
                        "${step.index + 1}. ${step.thought}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        "   ${step.action}  ->  ${step.result}  (${step.latencyMs} ms)",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

private fun currentRun(state: AgentState): RunContext? = when (state) {
    is AgentState.Planning -> state.run
    is AgentState.Retrying -> state.run
    is AgentState.Executing -> state.run
    is AgentState.Verifying -> state.run
    is AgentState.AwaitingConfirmation -> state.run
    is AgentState.AwaitingUserInput -> state.run
    is AgentState.Done -> state.run
    is AgentState.Failed -> state.run
    else -> null
}
