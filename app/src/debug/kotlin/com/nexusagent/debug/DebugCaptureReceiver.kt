package com.nexusagent.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.nexusagent.agent.perception.PerceptionRepository
import com.nexusagent.agent.perception.SnapshotResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Debug-only benchmark hook.
 *
 * ```
 *   adb shell am broadcast -a com.nexusagent.CAPTURE
 * ```
 *
 * Triggers a measured snapshot of whatever is on screen *right now*, and logs the result
 * under the `NexusMetrics` tag. This exists because the interesting measurements are of
 * other people's apps - WhatsApp, Instagram - and the debug screen can only capture while
 * it is itself in the foreground, where the only thing to measure is our own UI.
 *
 * Lives in `src/debug`, so it is absent from release builds entirely rather than merely
 * disabled.
 */
class DebugCaptureReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        // The tree walk is suspending and outlives onReceive, so keep the process alive
        // until it finishes.
        val pending = goAsync()

        CoroutineScope(Dispatchers.Default).launch {
            try {
                val repository = PerceptionRepository()
                when (val result = repository.snapshot(measureBaseline = true)) {
                    is SnapshotResult.Success ->
                        // The full payload, so the compressed output can be eyeballed for
                        // whether anything meaningful was lost.
                        Log.i(TAG, "payload: ${result.json}")

                    SnapshotResult.ServiceUnavailable ->
                        Log.w(TAG, "accessibility service is not running")

                    SnapshotResult.NoActiveWindow ->
                        Log.w(TAG, "no readable window")
                }
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val TAG = "NexusMetrics"
    }
}
