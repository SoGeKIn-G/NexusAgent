package com.nexusagent.agent.runtime.execution

import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.nexusagent.core.model.Bounds
import com.nexusagent.core.model.ResolutionStrategy
import com.nexusagent.core.model.ResolvedTarget
import kotlin.math.abs

data class Resolution(
    val node: AccessibilityNodeInfo,
    val strategy: ResolutionStrategy,
)

/**
 * Finds the live node corresponding to an element the model chose.
 *
 * ## Why this class exists
 *
 * `AccessibilityNodeInfo` is a handle into another process. Between observing the screen
 * and acting on it - one LLM round-trip, typically a second or more - the target may have
 * scrolled off, been recycled by a `RecyclerView`, or been destroyed with its activity.
 * Holding the original object and calling `performAction` on it later fails intermittently
 * and, worse, *silently*: the call returns false and the agent concludes the UI didn't
 * respond.
 *
 * So nothing is held. The snapshot records a [ResolvedTarget] description, and the element
 * is looked up again here, immediately before it is acted upon.
 *
 * ## Strategy order
 *
 * Strongest evidence first, because each fallback is more likely to find the *wrong* node:
 *
 * 1. **View id** - set by the app's developer, stable across scrolls and recycling.
 * 2. **Text** - stable while the content is on screen; ambiguous if repeated.
 * 3. **Content description** - same, for elements labelled only for accessibility.
 * 4. **Bounds** - whatever interactive node now occupies that region. Deliberately last:
 *    after a scroll this finds a real element that is simply not the one intended.
 */
class NodeResolver {

    fun resolve(root: AccessibilityNodeInfo, target: ResolvedTarget): Resolution? {
        byViewId(root, target)?.let { return Resolution(it, ResolutionStrategy.VIEW_ID) }
        byText(root, target)?.let { return Resolution(it, ResolutionStrategy.TEXT) }
        byDescription(root, target)?.let { return Resolution(it, ResolutionStrategy.CONTENT_DESCRIPTION) }
        byBounds(root, target)?.let { return Resolution(it, ResolutionStrategy.BOUNDS) }

        Log.w(TAG, "Could not re-resolve ${target.describe()}")
        return null
    }

    private fun byViewId(root: AccessibilityNodeInfo, target: ResolvedTarget): AccessibilityNodeInfo? {
        val id = target.viewIdResourceName?.takeIf { it.isNotBlank() } ?: return null
        val matches = runCatching { root.findAccessibilityNodeInfosByViewId(id) }
            .getOrNull()
            .orEmpty()
            .filter { it.isVisibleToUser }

        if (matches.isEmpty()) return null
        if (matches.size == 1) return matches.first()

        // A recycled list gives every row the same view id, so the id alone is ambiguous.
        // Disambiguate on the text we recorded, then on position.
        return matches.firstOrNull { it.matchesTextOf(target) } ?: matches.nearestTo(target.bounds)
    }

    private fun byText(root: AccessibilityNodeInfo, target: ResolvedTarget): AccessibilityNodeInfo? {
        val text = target.text?.takeIf { it.isNotBlank() } ?: return null

        // The compressor hoists child text into interactive ancestors, so the recorded
        // text is often a joined string ("Rahul · Hey, running late") that appears nowhere
        // verbatim. Search on the first fragment instead.
        val probe = text.substringBefore(HOIST_SEPARATOR).trim().ifBlank { return null }

        val matches = runCatching { root.findAccessibilityNodeInfosByText(probe) }
            .getOrNull()
            .orEmpty()
            .filter { it.isVisibleToUser }
            .ifEmpty { return null }

        // findAccessibilityNodeInfosByText matches the TextView itself, which is usually
        // not clickable - the row around it is. Climb to something actionable.
        return (matches.firstOrNull { it.isClickable } ?: matches.nearestTo(target.bounds))
            ?.let { it.takeIf { n -> n.isClickable } ?: it.nearestClickableAncestor() ?: it }
    }

    private fun byDescription(root: AccessibilityNodeInfo, target: ResolvedTarget): AccessibilityNodeInfo? {
        val description = target.contentDescription?.takeIf { it.isNotBlank() } ?: return null
        val matches = mutableListOf<AccessibilityNodeInfo>()

        root.forEachNode { node ->
            if (node.isVisibleToUser &&
                node.contentDescription?.toString()?.equals(description, ignoreCase = true) == true
            ) {
                matches += node
            }
        }

        return matches.firstOrNull { it.isClickable } ?: matches.nearestTo(target.bounds)
    }

    private fun byBounds(root: AccessibilityNodeInfo, target: ResolvedTarget): AccessibilityNodeInfo? {
        val wanted = target.bounds
        var best: AccessibilityNodeInfo? = null
        var bestDistance = Int.MAX_VALUE

        root.forEachNode { node ->
            if (!node.isVisibleToUser || !node.isClickable) return@forEachNode
            val rect = node.screenBounds()
            if (!rect.contains(wanted.centerX, wanted.centerY)) return@forEachNode

            val distance = abs(rect.centerX() - wanted.centerX) + abs(rect.centerY() - wanted.centerY)
            if (distance < bestDistance) {
                bestDistance = distance
                best = node
            }
        }
        return best
    }

    // -- helpers ------------------------------------------------------------------

    private fun AccessibilityNodeInfo.matchesTextOf(target: ResolvedTarget): Boolean {
        val probe = target.text?.substringBefore(HOIST_SEPARATOR)?.trim() ?: return false
        if (probe.isBlank()) return false
        return text?.toString()?.contains(probe, ignoreCase = true) == true ||
            contentDescription?.toString()?.contains(probe, ignoreCase = true) == true
    }

    private fun List<AccessibilityNodeInfo>.nearestTo(bounds: Bounds): AccessibilityNodeInfo? =
        minByOrNull { node ->
            val rect = node.screenBounds()
            abs(rect.centerX() - bounds.centerX) + abs(rect.centerY() - bounds.centerY)
        }

    private fun ResolvedTarget.describe(): String =
        "[id=$viewIdResourceName text=$text desc=$contentDescription class=$className]"

    private companion object {
        const val TAG = "NexusExecution"

        /** Matches the separator TreeCompressor uses when hoisting child text. */
        const val HOIST_SEPARATOR = " · "
    }
}

// -- shared node extensions -------------------------------------------------------

internal fun AccessibilityNodeInfo.screenBounds(): Rect =
    Rect().also { getBoundsInScreen(it) }

/**
 * Walks the subtree depth-first.
 *
 * Depth-capped: a malformed hierarchy can contain a cycle, and an uncapped recursion here
 * takes down the whole process with a StackOverflowError inside a system callback.
 */
internal fun AccessibilityNodeInfo.forEachNode(
    maxDepth: Int = 60,
    action: (AccessibilityNodeInfo) -> Unit,
) {
    fun visit(node: AccessibilityNodeInfo, depth: Int) {
        if (depth > maxDepth) return
        action(node)
        for (i in 0 until node.childCount) {
            val child = runCatching { node.getChild(i) }.getOrNull() ?: continue
            visit(child, depth + 1)
        }
    }
    visit(this, 0)
}

/**
 * The nearest ancestor that will actually respond to a click.
 *
 * Text and icons are usually not clickable themselves; the row or button wrapping them is.
 * Without this, half of all resolved targets would report "click returned false".
 */
internal fun AccessibilityNodeInfo.nearestClickableAncestor(maxHops: Int = 6): AccessibilityNodeInfo? {
    var current = parent
    var hops = 0
    while (current != null && hops < maxHops) {
        if (current.isClickable && current.isEnabled) return current
        current = current.parent
        hops++
    }
    return null
}
