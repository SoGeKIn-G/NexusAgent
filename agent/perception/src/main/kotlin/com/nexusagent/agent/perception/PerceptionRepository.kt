package com.nexusagent.agent.perception

import com.nexusagent.core.model.CompressionConfig
import com.nexusagent.core.model.ScreenSnapshot
import com.nexusagent.core.model.SnapshotJson

/**
 * The perception layer's public surface.
 *
 * Everything above this line (UI, orchestrator) talks to perception through here and
 * never touches [NexusAccessibilityService] directly. That keeps one awkward fact - the
 * service is owned by the system and may simply not exist - contained behind a result
 * type instead of leaking nullable service handles across the app.
 */
class PerceptionRepository {

    val isConnected = AgentServiceBridge.connected
    val foregroundPackage = AgentServiceBridge.foregroundPackage

    /**
     * @param measureBaseline also serialize the unpruned tree to compute the reduction.
     *   Roughly doubles the work, so it's on for the debug screen and off in the loop.
     */
    suspend fun snapshot(measureBaseline: Boolean = false): SnapshotResult {
        val service = NexusAccessibilityService.instance
            ?: return SnapshotResult.ServiceUnavailable

        val snapshot = service.captureSnapshot(
            CompressionConfig(measureBaseline = measureBaseline),
        ) ?: return SnapshotResult.NoActiveWindow

        val json = SnapshotJson.encode(
            snapshot.packageName,
            snapshot.activityName,
            snapshot.elements,
        )

        // Every snapshot is logged with its measurements. This is the raw material for
        // the project's headline metric: with a few hundred of these from real apps, the
        // compression claim is backed by data instead of a single lucky screen.
        val stats = snapshot.stats
        android.util.Log.i(
            METRICS_TAG,
            buildString {
                append(snapshot.packageName).append(' ')
                append("nodes=").append(stats.rawNodeCount).append("->").append(stats.keptNodeCount)
                append(" (").append("%.1f".format(stats.nodeReductionPercent)).append("%)")
                if (stats.rawBytes > 0) {
                    append(" bytes=").append(stats.rawBytes).append("->").append(stats.compressedBytes)
                    append(" (").append("%.1f".format(stats.byteReductionPercent)).append("%)")
                } else {
                    append(" bytes=").append(stats.compressedBytes)
                }
                append(" walk=").append(stats.walkDurationMs).append("ms")
                if (snapshot.isSparse()) append(" SPARSE")
            },
        )

        return SnapshotResult.Success(snapshot = snapshot, json = json)
    }

    /** Null when the service is off, or the system refused (secure window, rate limit). */
    suspend fun screenshot(): Screenshot? =
        NexusAccessibilityService.instance?.captureScreenshot()

    /** See [NexusAccessibilityService.awaitScreenSettled] - a timeout here is not a failure. */
    suspend fun awaitScreenSettled(timeoutMs: Long = NexusAccessibilityService.SETTLE_TIMEOUT_MS): Boolean =
        NexusAccessibilityService.instance?.awaitScreenSettled(timeoutMs) ?: false

    private companion object {
        /** `adb logcat -s NexusMetrics` to collect compression data from real apps. */
        const val METRICS_TAG = "NexusMetrics"
    }
}

sealed interface SnapshotResult {
    data class Success(val snapshot: ScreenSnapshot, val json: String) : SnapshotResult

    /** The accessibility service is switched off, or the system hasn't bound it yet. */
    data object ServiceUnavailable : SnapshotResult

    /** Transient: happens during app transitions and on windows we may not read. */
    data object NoActiveWindow : SnapshotResult
}
