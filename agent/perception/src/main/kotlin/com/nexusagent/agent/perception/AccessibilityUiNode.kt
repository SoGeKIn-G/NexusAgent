package com.nexusagent.agent.perception

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.nexusagent.core.model.Bounds
import com.nexusagent.core.model.UiNode

/**
 * Adapts a live [AccessibilityNodeInfo] to the platform-free [UiNode] the compressor
 * works against.
 *
 * ## Lifetime warning
 *
 * Every property here is a **Binder call into another process**. Instances are valid only
 * for the duration of a single tree walk and must never be stored, cached, or handed to a
 * coroutine that outlives the walk — the backing node can be recycled by the owning app at
 * any moment, after which reads return garbage or throw.
 *
 * That is why [com.nexusagent.core.model.ResolvedTarget] exists: the snapshot keeps a
 * *description* of each element and re-resolves it at execution time, rather than holding
 * one of these.
 *
 * `recycle()` is deliberately not called — it was deprecated in API 33 and is a no-op on
 * every version this app supports (minSdk 31 notwithstanding, the platform now manages
 * these itself).
 */
internal class AccessibilityUiNode(
    private val node: AccessibilityNodeInfo,
) : UiNode {

    override val className: String
        get() = node.className?.toString() ?: "View"

    override val text: String?
        get() = node.text?.toString()

    override val contentDescription: String?
        get() = node.contentDescription?.toString()

    override val viewIdResourceName: String?
        get() = node.viewIdResourceName

    // Computed once: getBoundsInScreen is an IPC round-trip and the compressor reads
    // bounds more than once per node.
    override val bounds: Bounds by lazy(LazyThreadSafetyMode.NONE) {
        val rect = Rect()
        node.getBoundsInScreen(rect)
        Bounds(rect.left, rect.top, rect.right, rect.bottom)
    }

    override val isVisibleToUser: Boolean get() = node.isVisibleToUser
    override val isClickable: Boolean get() = node.isClickable
    override val isLongClickable: Boolean get() = node.isLongClickable
    override val isEditable: Boolean get() = node.isEditable
    override val isScrollable: Boolean get() = node.isScrollable
    override val isCheckable: Boolean get() = node.isCheckable
    // Deprecated in favour of a tri-state (unchecked / checked / partial) accessor on
    // newer SDKs. The boolean is the right shape for the agent: an action either toggled
    // something on or it did not, and "partially checked" is not a state it can target.
    @Suppress("DEPRECATION")
    override val isChecked: Boolean get() = node.isChecked
    override val isFocused: Boolean get() = node.isFocused
    override val isEnabled: Boolean get() = node.isEnabled

    override val childCount: Int get() = node.childCount

    override fun childAt(index: Int): UiNode? =
        // getChild returns null for recycled or not-yet-attached children; a busy list
        // hits this routinely, so it is expected rather than exceptional.
        runCatching { node.getChild(index) }.getOrNull()?.let(::AccessibilityUiNode)
}
