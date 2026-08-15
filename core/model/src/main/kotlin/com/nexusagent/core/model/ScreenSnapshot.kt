package com.nexusagent.core.model

/**
 * One compressed observation of the foreground screen.
 *
 * Produced by walking the live accessibility tree and discarding everything the model
 * cannot act on. A typical raw Android hierarchy is thousands of nodes and tens of
 * kilobytes of mostly-empty layout containers; this is what survives.
 */
data class ScreenSnapshot(
    val packageName: String,
    val activityName: String?,
    val elements: List<UiElement>,
    val stats: SnapshotStats,
    val capturedAtMillis: Long,
) {
    /**
     * Cheap fingerprint used to detect whether an action actually changed anything.
     *
     * Deliberately excludes bounds — a list that scrolls a few pixels has not meaningfully
     * changed — and sorts texts so element reordering alone doesn't register as a change.
     */
    val signature: Int
        get() = (
            packageName +
                activityName.orEmpty() +
                elements.mapNotNull { it.text ?: it.contentDescription }.sorted().joinToString("|")
            ).hashCode()

    /** Sparse trees usually mean a canvas-rendered surface, which is when we attach a screenshot. */
    fun isSparse(threshold: Int = 4): Boolean = elements.count { it.isActionable } < threshold
}

/**
 * A single actionable element.
 *
 * The [id] is stable only within one snapshot — it is what the model refers to when it
 * says `click(7)`. It intentionally does NOT hold an AccessibilityNodeInfo: those are
 * Binder handles into another process and go stale between observation and execution.
 * See [ResolvedTarget].
 */
data class UiElement(
    val id: Int,
    val className: String,
    val text: String? = null,
    val contentDescription: String? = null,
    val bounds: Bounds,
    val clickable: Boolean = false,
    val longClickable: Boolean = false,
    val editable: Boolean = false,
    val scrollable: Boolean = false,
    val checkable: Boolean = false,
    val checked: Boolean = false,
    val focused: Boolean = false,
    /** Kept for re-resolution at execution time. Strongest matching signal when present. */
    val viewIdResourceName: String? = null,
) {
    val isActionable: Boolean
        get() = clickable || longClickable || editable || scrollable || checkable
}

data class Bounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val centerX: Int get() = (left + right) / 2
    val centerY: Int get() = (top + bottom) / 2
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val isEmpty: Boolean get() = width <= 0 || height <= 0
}

/**
 * How to find an element again at execution time.
 *
 * Node staleness is the hardest real bug in this project: an AccessibilityNodeInfo is a
 * handle into another process, and between snapshot and tap the target may have scrolled,
 * been recycled, or been destroyed. So we store a *description* and re-resolve, in
 * priority order: viewId → text → contentDescription → bounds containment.
 */
data class ResolvedTarget(
    val viewIdResourceName: String?,
    val text: String?,
    val contentDescription: String?,
    val className: String,
    val bounds: Bounds,
)

/**
 * Compression instrumentation, persisted per step.
 *
 * This is what turns "I optimized the context window" into a measured number backed by
 * a few hundred recorded steps. Keep it — it is the project's headline metric.
 */
data class SnapshotStats(
    val rawNodeCount: Int,
    val keptNodeCount: Int,
    val rawBytes: Int,
    val compressedBytes: Int,
    val walkDurationMs: Long,
) {
    val nodeReductionPercent: Float
        get() = if (rawNodeCount == 0) 0f else (1f - keptNodeCount.toFloat() / rawNodeCount) * 100f

    val byteReductionPercent: Float
        get() = if (rawBytes == 0) 0f else (1f - compressedBytes.toFloat() / rawBytes) * 100f
}
