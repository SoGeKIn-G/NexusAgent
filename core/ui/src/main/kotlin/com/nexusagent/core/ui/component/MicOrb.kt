package com.nexusagent.core.ui.component

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.sin

enum class OrbState { Idle, Listening, Thinking, Acting }

/**
 * The mic orb.
 *
 * Four layered sine waves, phase-offset and drawn as closed paths, rotating at slightly
 * different rates. Cheap to run - one Canvas, no bitmaps, no shader compilation - and
 * because the deformation is driven by real microphone amplitude while listening, it
 * reads as *responding to you* rather than as a decorative loop.
 *
 * State is legible at a glance, which matters more than it sounds: the agent spends
 * seconds at a time in a network call with nothing else happening on screen, and an app
 * that looks frozen reads as broken.
 *
 *  - **Idle** — slow breathing, muted
 *  - **Listening** — amplitude-reactive, cyan
 *  - **Thinking** — fast swirl, indigo
 *  - **Acting** — tight quick pulse, amber
 */
@Composable
fun MicOrb(
    state: OrbState,
    amplitude: Float,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: androidx.compose.ui.unit.Dp = 168.dp,
) {
    val transition = rememberInfiniteTransition(label = "orb")

    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = (2 * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = when (state) {
                    OrbState.Idle -> 6000
                    OrbState.Listening -> 2600
                    OrbState.Thinking -> 1500
                    OrbState.Acting -> 900
                },
                easing = androidx.compose.animation.core.LinearEasing,
            ),
            repeatMode = RepeatMode.Restart,
        ),
        label = "orbPhase",
    )

    val breathe by transition.animateFloat(
        initialValue = 0.94f,
        targetValue = 1.06f,
        animationSpec = infiniteRepeatable(tween(2200), RepeatMode.Reverse),
        label = "orbBreathe",
    )

    // Spring rather than tween: microphone amplitude is noisy, and a spring smooths it
    // into something that looks like a voice instead of like jitter.
    val reactive by animateFloatAsState(
        targetValue = if (state == OrbState.Listening) amplitude else 0f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 420f),
        label = "orbAmplitude",
    )

    val palette = when (state) {
        OrbState.Idle -> listOf(Color(0xFF6366F1), Color(0xFF4F46E5))
        OrbState.Listening -> listOf(Color(0xFF22D3EE), Color(0xFF0891B2))
        OrbState.Thinking -> listOf(Color(0xFFA5B4FC), Color(0xFF4F46E5))
        OrbState.Acting -> listOf(Color(0xFFFBBF24), Color(0xFFF59E0B))
    }

    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(size)) {
            val center = Offset(this.size.width / 2f, this.size.height / 2f)
            val base = this.size.minDimension / 2f * 0.72f * breathe

            // Outer halo. Sits behind the blobs and gives the orb a sense of depth
            // without needing a real shadow.
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(palette[0].copy(alpha = 0.28f), Color.Transparent),
                    center = center,
                    radius = base * (1.7f + reactive * 0.5f),
                ),
                radius = base * (1.7f + reactive * 0.5f),
                center = center,
            )

            // Four lobed blobs, each with a different lobe count and phase. Different
            // rotation rates keep them from ever locking into a repeating silhouette.
            repeat(4) { layer ->
                val lobes = 3 + layer
                val radius = base * (1f - layer * 0.11f)
                val wobble = (0.10f + layer * 0.035f) * (1f + reactive * 2.4f)
                drawBlob(
                    center = center,
                    radius = radius,
                    lobes = lobes,
                    wobble = wobble,
                    phase = phase * (1f + layer * 0.23f),
                    color = palette[layer % palette.size].copy(alpha = 0.34f - layer * 0.05f),
                )
            }

            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(palette[0], palette[1]),
                    center = center,
                    radius = base * 0.62f,
                ),
                radius = base * 0.62f,
                center = center,
            )
        }

        Icon(
            imageVector = if (state == OrbState.Idle) Icons.Default.Mic else Icons.Default.Stop,
            contentDescription = if (state == OrbState.Idle) "Start listening" else "Stop",
            tint = Color.White,
            modifier = Modifier.size(size / 4.2f),
        )
    }
}

/**
 * A closed path whose radius varies sinusoidally with angle, sampled every 6 degrees.
 *
 * 60 samples is well past the point where the outline reads as smooth at this size, and
 * far cheaper than a path built from bezier segments.
 */
private fun DrawScope.drawBlob(
    center: Offset,
    radius: Float,
    lobes: Int,
    wobble: Float,
    phase: Float,
    color: Color,
) {
    val path = Path()
    val steps = 60

    for (i in 0..steps) {
        val angle = (i.toFloat() / steps) * 2f * PI.toFloat()
        val r = radius * (1f + wobble * sin(lobes * angle + phase))
        val x = center.x + r * kotlin.math.cos(angle.toDouble()).toFloat()
        val y = center.y + r * sin(angle.toDouble()).toFloat()
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    path.close()

    drawPath(path, color)
}
