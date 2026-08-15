package com.nexusagent.overlay

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nexusagent.core.model.AgentState
import com.nexusagent.core.ui.theme.NexusTheme

/**
 * What the floating bubble renders.
 *
 * This is the highest-value surface in the whole project for demo purposes: it's the only
 * moment where a viewer sees the agent *reasoning* while it operates a third-party app.
 * The thought text is the payload; everything else is chrome around it.
 *
 * Collapsed by default so it doesn't obscure the app being driven. Tap to expand.
 */
@Composable
fun OverlayContent(
    state: AgentState,
    expanded: Boolean,
    onToggle: () -> Unit,
    onStop: () -> Unit,
    onApprove: () -> Unit,
    onDeny: () -> Unit,
) {
    NexusTheme {
        val accent = when (state) {
            is AgentState.Planning -> Color(0xFFA5B4FC)
            is AgentState.Retrying -> Color(0xFFF97316)
            is AgentState.Executing -> Color(0xFFFBBF24)
            is AgentState.Verifying -> Color(0xFF67E8F9)
            is AgentState.AwaitingConfirmation, is AgentState.AwaitingUserInput -> Color(0xFFF97316)
            is AgentState.Done -> Color(0xFF34D399)
            is AgentState.Failed -> Color(0xFFF87171)
            else -> Color(0xFF9CA3AF)
        }

        Surface(
            shape = RoundedCornerShape(22.dp),
            color = Color(0xFF15151C).copy(alpha = 0.95f),
            shadowElevation = 12.dp,
            modifier = Modifier.widthIn(max = 300.dp),
        ) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.clickable(onClick = onToggle),
                ) {
                    PulsingDot(accent)

                    Text(
                        text = state.headline(),
                        color = Color.White,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )

                    state.stepLabel()?.let {
                        Text(it, color = Color(0xFF9CA3AF), style = MaterialTheme.typography.labelSmall)
                    }

                    // Always one tap away, collapsed or not. A stop button you have to
                    // expand to reach is not a stop button.
                    Box(
                        Modifier
                            .size(26.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF3F1D1D))
                            .clickable(onClick = onStop),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "Stop the agent",
                            tint = Color(0xFFF87171),
                            modifier = Modifier.size(15.dp),
                        )
                    }
                }

                AnimatedVisibility(
                    visible = expanded,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically(),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        state.thought()?.let { thought ->
                            Text(
                                text = thought,
                                color = Color(0xFFE5E7EB),
                                // Monospace: reads as machine output rather than chat,
                                // which is the impression we want here.
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                        state.goal()?.let { goal ->
                            Text(
                                text = "goal: $goal",
                                color = Color(0xFF6B7280),
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }

                        // Approval happens HERE, floating over the app being driven -
                        // never by switching to our own UI. Switching apps changes the
                        // foreground window, which invalidates every element id the agent
                        // captured, so a run that asked for approval in-app could not
                        // reliably resume where it left off.
                        if (state is AgentState.AwaitingConfirmation) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.padding(top = 4.dp),
                            ) {
                                OverlayButton(
                                    label = "Allow",
                                    background = Color(0xFF065F46),
                                    foreground = Color(0xFF6EE7B7),
                                    onClick = onApprove,
                                    modifier = Modifier.weight(1f),
                                )
                                OverlayButton(
                                    label = "Don't",
                                    background = Color(0xFF3F1D1D),
                                    foreground = Color(0xFFFCA5A5),
                                    onClick = onDeny,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * A compact button sized for the overlay.
 *
 * Hand-rolled rather than Material's Button: the overlay is deliberately small, and
 * Material's minimum touch target plus default padding makes the bubble large enough to
 * obscure the app underneath - which defeats the point of floating over it.
 */
@Composable
private fun OverlayButton(
    label: String,
    background: Color,
    foreground: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(background)
            .clickable(onClick = onClick)
            .padding(vertical = 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = foreground,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun PulsingDot(color: Color) {
    val transition = rememberInfiniteTransition(label = "overlayDot")
    val scale by transition.animateFloat(
        initialValue = 0.7f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(tween(850), RepeatMode.Reverse),
        label = "overlayDotScale",
    )
    Box(
        Modifier
            .size(11.dp)
            .scale(scale)
            .clip(CircleShape)
            .background(color),
    )
}

private fun AgentState.headline(): String = when (this) {
    AgentState.Idle -> "Idle"
    is AgentState.Listening -> "Listening"
    is AgentState.Planning -> "Thinking"
    is AgentState.Retrying -> "Waiting ${(retryInMillis / 1000) + 1}s"
    is AgentState.Executing -> "Acting"
    is AgentState.Verifying -> "Checking"
    is AgentState.AwaitingConfirmation -> "Needs approval"
    is AgentState.AwaitingUserInput -> "Needs an answer"
    is AgentState.Done -> "Done"
    is AgentState.Failed -> "Stopped"
}

private fun AgentState.stepLabel(): String? = when (this) {
    is AgentState.Planning -> "${run.stepIndex + 1}"
    is AgentState.Executing -> "${run.stepIndex + 1}"
    is AgentState.Verifying -> "${run.stepIndex + 1}"
    else -> null
}

private fun AgentState.thought(): String? = when (this) {
    is AgentState.Retrying -> "$reason (attempt $attempt of $maxAttempts)"
    is AgentState.Executing -> decision.thought
    is AgentState.Verifying -> decision.thought
    is AgentState.AwaitingConfirmation -> prompt
    is AgentState.AwaitingUserInput -> question
    is AgentState.Done -> summary
    is AgentState.Failed -> reason
    is AgentState.Planning -> "Deciding what to do next..."
    else -> null
}

private fun AgentState.goal(): String? = when (this) {
    is AgentState.Planning -> run.goal
    is AgentState.Retrying -> run.goal
    is AgentState.Executing -> run.goal
    is AgentState.Verifying -> run.goal
    is AgentState.AwaitingConfirmation -> run.goal
    is AgentState.AwaitingUserInput -> run.goal
    else -> null
}
