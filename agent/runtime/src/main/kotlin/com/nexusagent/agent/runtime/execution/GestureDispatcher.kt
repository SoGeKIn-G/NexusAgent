package com.nexusagent.agent.runtime.execution

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.util.Log
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Synthesises real touch input.
 *
 * This is the layer of last resort. Wherever possible the executor asks a node to perform
 * an action on itself, because that is precise and unaffected by overlays. Gestures are
 * used when there is no cooperating node - canvas-rendered surfaces, custom views that
 * expose no accessibility actions, or a target that could not be re-resolved at all.
 *
 * Gestures are blunt: they hit whatever pixel is on top, which may be a dialog that
 * appeared in the intervening milliseconds. Preferring node actions is what keeps the
 * agent from tapping through a permission prompt it never saw.
 */
class GestureDispatcher(private val service: AccessibilityService) {

    suspend fun tap(x: Int, y: Int): Boolean =
        dispatch(strokeOf(path(x, y), durationMs = TAP_DURATION_MS), "tap($x,$y)")

    suspend fun longPress(x: Int, y: Int): Boolean =
        dispatch(strokeOf(path(x, y), durationMs = LONG_PRESS_DURATION_MS), "longPress($x,$y)")

    suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long): Boolean {
        val path = Path().apply {
            moveTo(x1.toFloat(), y1.toFloat())
            lineTo(x2.toFloat(), y2.toFloat())
        }
        return dispatch(
            strokeOf(path, durationMs.coerceIn(MIN_SWIPE_MS, MAX_SWIPE_MS)),
            "swipe($x1,$y1 -> $x2,$y2)",
        )
    }

    private fun path(x: Int, y: Int) = Path().apply { moveTo(x.toFloat(), y.toFloat()) }

    private fun strokeOf(path: Path, durationMs: Long): GestureDescription =
        GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, durationMs))
            .build()

    private suspend fun dispatch(gesture: GestureDescription, label: String): Boolean =
        suspendCancellableCoroutine { continuation ->
            val callback = object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(description: GestureDescription?) {
                    if (continuation.isActive) continuation.resume(true)
                }

                override fun onCancelled(description: GestureDescription?) {
                    // Usually means another gesture was already in flight, or the screen
                    // changed under us mid-stroke.
                    Log.d(TAG, "$label cancelled")
                    if (continuation.isActive) continuation.resume(false)
                }
            }

            // Returns false immediately if the service lacks canPerformGestures, or if
            // the system is in a state that forbids injection (secure keyguard, for one).
            val accepted = runCatching {
                service.dispatchGesture(gesture, callback, null)
            }.getOrDefault(false)

            if (!accepted) {
                Log.w(TAG, "$label rejected by the system")
                if (continuation.isActive) continuation.resume(false)
            }
        }

    private companion object {
        const val TAG = "NexusExecution"

        // Long enough to register as a deliberate tap rather than a stray touch, short
        // enough not to trip long-press handlers.
        const val TAP_DURATION_MS = 60L
        const val LONG_PRESS_DURATION_MS = 700L
        const val MIN_SWIPE_MS = 100L
        const val MAX_SWIPE_MS = 2_000L
    }
}
