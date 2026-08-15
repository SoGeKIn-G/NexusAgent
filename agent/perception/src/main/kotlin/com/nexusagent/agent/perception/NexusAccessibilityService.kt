package com.nexusagent.agent.perception

import android.accessibilityservice.AccessibilityService
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.nexusagent.core.model.CompressionConfig
import com.nexusagent.core.model.ScreenSnapshot
import com.nexusagent.core.model.TreeCompressor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.plus
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The agent's eyes and hands.
 *
 * ## The single most important design decision in this class
 *
 * This service does **not** parse the view hierarchy when an accessibility event arrives.
 *
 * A busy app - a scrolling feed, a loading screen, an animation - emits
 * `TYPE_WINDOW_CONTENT_CHANGED` hundreds of times per second. Walking the tree on each
 * one would burn battery, heat the device, and jank the app the user is actually looking
 * at. Instead:
 *
 *  - events are pushed into [screenSettled] and debounced, so downstream sees a signal
 *    only once the screen goes quiet;
 *  - the tree is walked **on demand**, via [captureSnapshot].
 *
 * The callback thread therefore does almost nothing: record the foreground package, emit
 * into a flow, return.
 */
class NexusAccessibilityService : AccessibilityService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Raw event ticks. `DROP_OLDEST` matters - during an event storm we care that
     * activity is happening, not about replaying every event, and an unbounded buffer
     * here would be a slow memory leak.
     */
    private val events = MutableSharedFlow<Unit>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /**
     * Emits once the screen has stopped changing.
     *
     * The executor waits on this after dispatching an action, rather than sleeping a
     * fixed duration - a fast screen shouldn't cost us 800ms, and a slow one shouldn't
     * be observed half-drawn.
     */
    @OptIn(kotlinx.coroutines.FlowPreview::class)
    val screenSettled = events
        .debounce(SETTLE_DEBOUNCE_MS)
        .shareIn(scope, SharingStarted.Eagerly, replay = 0)

    /** Class name from the last window-state change; used as the screen's activity label. */
    @Volatile
    private var lastActivityName: String? = null

    override fun onCreate() {
        super.onCreate()
        // Claim the handle here as well as in onServiceConnected. The system may unbind
        // and rebind a live service without destroying it, in which case onServiceConnected
        // does not fire again - so treating it as the only "we're alive" signal left the
        // UI reporting the service as off while it was demonstrably delivering events.
        instance = this
        AgentServiceBridge.setConnected(true)
        Log.i(TAG, "onCreate - service object created.")
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        AgentServiceBridge.setConnected(true)
        Log.i(TAG, "Connected. Perception online.")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return

        // Keep this branch cheap. It runs on the system's callback thread, potentially
        // hundreds of times per second.
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val pkg = event.packageName?.toString()
            val cls = event.className?.toString()
            if (cls != null && cls.contains('.')) lastActivityName = cls
            if (!pkg.isNullOrBlank() && pkg != AgentServiceBridge.foregroundPackage.value) {
                AgentServiceBridge.setForegroundPackage(pkg)
                Log.d(TAG, "Foreground app -> $pkg  ($cls)")
            }
        }

        events.tryEmit(Unit)
    }

    /**
     * Walks the live hierarchy and returns a compressed snapshot.
     *
     * Runs on [Dispatchers.Default] because every node access is a Binder round-trip into
     * the foreground app's process; doing this on the main thread would stutter the UI of
     * whatever app the agent is driving.
     *
     * Returns null when there is no active window - which happens briefly during app
     * transitions and on secure windows the service isn't permitted to read.
     */
    suspend fun captureSnapshot(
        config: CompressionConfig = CompressionConfig(),
    ): ScreenSnapshot? = withContext(Dispatchers.Default) {
        val root = rootInActiveWindow ?: run {
            Log.d(TAG, "No active window to snapshot")
            return@withContext null
        }

        val startedAt = System.currentTimeMillis()
        val snapshot = runCatching {
            TreeCompressor(config).compress(
                root = AccessibilityUiNode(root),
                packageName = root.packageName?.toString()
                    ?: AgentServiceBridge.foregroundPackage.value.orEmpty(),
                activityName = lastActivityName,
                nowMillis = startedAt,
            )
        }.onFailure {
            // A node can be recycled by the owning app mid-walk. That is a normal race,
            // not a crash: the caller simply retries on the next settled screen.
            Log.w(TAG, "Tree walk failed", it)
        }.getOrNull() ?: return@withContext null

        snapshot.copy(
            stats = snapshot.stats.copy(
                walkDurationMs = System.currentTimeMillis() - startedAt,
            ),
        )
    }

    /** Downscaled JPEG of the current screen, or null if the system refused. */
    suspend fun captureScreenshot(): Screenshot? =
        if (Build.VERSION.SDK_INT >= 30) ScreenCapture.capture(this) else null

    /**
     * Suspends until the screen stops changing, or [timeoutMs] elapses.
     *
     * A timeout is not a failure. It usually means the screen was already static and no
     * further events arrived, which is exactly the state the caller wanted. Callers should
     * proceed either way.
     *
     * @return true if a settle signal was observed, false on timeout.
     */
    suspend fun awaitScreenSettled(timeoutMs: Long = SETTLE_TIMEOUT_MS): Boolean =
        withTimeoutOrNull(timeoutMs) { screenSettled.first() } != null

    override fun onInterrupt() {
        Log.w(TAG, "Interrupted by the system.")
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        Log.i(TAG, "onUnbind")
        clearIfCurrent()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        Log.i(TAG, "onDestroy")
        scope.cancel()
        clearIfCurrent()
        super.onDestroy()
    }

    /**
     * Releases the shared handle only if this instance still owns it.
     *
     * On reinstall (and on some OEM restarts) the system creates the replacement service
     * *before* tearing the old one down, so the teardown callbacks arrive in this order:
     *
     * ```
     *   old.onUnbind  ->  new.onCreate  ->  new.onServiceConnected  ->  old.onDestroy
     * ```
     *
     * An unconditional `instance = null` in the last step wipes the handle belonging to
     * the live service, and the UI then reports perception as offline while the service
     * is demonstrably still delivering events. The identity check makes teardown
     * order-independent.
     */
    private fun clearIfCurrent() {
        if (instance === this) {
            instance = null
            AgentServiceBridge.setConnected(false)
            Log.i(TAG, "Perception offline.")
        } else {
            Log.d(TAG, "Stale instance torn down; live service left untouched.")
        }
    }

    companion object {
        private const val TAG = "NexusPerception"

        /** How long the screen must be quiet before we call it settled. */
        const val SETTLE_DEBOUNCE_MS = 400L

        /** Upper bound on waiting for a screen to stop animating. */
        const val SETTLE_TIMEOUT_MS = 2_000L

        /**
         * The live service, or null when disabled.
         *
         * A static handle to a Service is normally a leak, but an AccessibilityService is
         * a process-lifetime singleton owned by the system, and it is cleared in both
         * [onUnbind] and [onDestroy]. The orchestrator needs a way to reach it that
         * doesn't depend on binding.
         */
        @Volatile
        var instance: NexusAccessibilityService? = null
            private set
    }
}
