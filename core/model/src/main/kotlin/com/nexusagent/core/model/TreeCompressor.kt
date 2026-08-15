package com.nexusagent.core.model

/**
 * Tuning for [TreeCompressor]. Defaults are chosen to keep a typical screen under a few
 * hundred tokens without dropping information the model needs to ground on.
 */
data class CompressionConfig(
    /** Longer strings are truncated. Chat previews and article bodies are the usual offenders. */
    val maxTextLength: Int = 120,
    /** How many hoisted fragments a single element may absorb before we stop appending. */
    val maxHoistedFragments: Int = 8,
    /** Hard cap on emitted elements; pathological screens otherwise blow the context window. */
    val maxElements: Int = 120,
    /**
     * Also serialize the unpruned tree to measure the reduction.
     * Roughly doubles the work, so it's on for the debug screen and off in the agent loop.
     */
    val measureBaseline: Boolean = false,
)

/**
 * Turns a raw screen hierarchy into something an LLM can read.
 *
 * ## The problem
 *
 * A real Android screen is thousands of nodes, and almost all of them are structural:
 * `FrameLayout` wrapping `LinearLayout` wrapping `ConstraintLayout`, none of which the
 * agent can tap and none of which carry meaning. Sent raw, one screen is tens of
 * kilobytes of mostly-empty boxes.
 *
 * ## The approach
 *
 * A single depth-first pass that keeps **only interactive nodes**, and hoists the text of
 * everything discarded into the nearest interactive ancestor.
 *
 * ```
 *   LinearLayout (clickable)          ──►  one element:
 *     ├── ImageView                        "Rahul · Hey, running late · 2:14 PM"
 *     ├── TextView "Rahul"
 *     ├── TextView "Hey, running late"
 *     └── TextView "2:14 PM"
 * ```
 *
 * Hoisting is what makes the pruning safe. Dropping the `TextView`s outright would throw
 * away the contact name — the very thing the model needs to pick the right row. Instead
 * the row becomes one tappable element that still reads as a chat row.
 *
 * Text that reaches the root without ever finding an interactive ancestor is emitted as a
 * non-interactive element, so headings and static labels survive.
 */
class TreeCompressor(private val config: CompressionConfig = CompressionConfig()) {

    fun compress(
        root: UiNode?,
        packageName: String,
        activityName: String?,
        nowMillis: Long,
    ): ScreenSnapshot {
        val started = nowMillis
        val elements = mutableListOf<UiElement>()
        val counter = NodeCounter()

        val orphanText = if (root == null) emptyList() else visit(root, elements, counter)

        // Anything still unclaimed belongs to no interactive element — headings, empty
        // states, error messages. Emit it so the screen doesn't read as blank.
        if (orphanText.isNotEmpty() && elements.size < config.maxElements) {
            elements += UiElement(
                id = elements.size,
                className = "Text",
                text = joinFragments(orphanText),
                bounds = root?.bounds ?: Bounds(0, 0, 0, 0),
            )
        }

        val compressedJson = SnapshotJson.encode(packageName, activityName, elements)
        val baselineBytes = if (config.measureBaseline && root != null) {
            SnapshotJson.encodeBaseline(root)
        } else {
            0
        }

        return ScreenSnapshot(
            packageName = packageName,
            activityName = activityName,
            elements = elements,
            stats = SnapshotStats(
                rawNodeCount = counter.visited,
                keptNodeCount = elements.size,
                rawBytes = baselineBytes,
                compressedBytes = compressedJson.length,
                walkDurationMs = 0,
            ),
            capturedAtMillis = started,
        )
    }

    /**
     * @return text fragments from this subtree that found no interactive ancestor and
     *   must be carried further up.
     */
    private fun visit(
        node: UiNode,
        out: MutableList<UiElement>,
        counter: NodeCounter,
    ): List<String> {
        counter.visited++

        // An invisible node's subtree is invisible too. This prunes entire offscreen
        // pages of a ViewPager in one step, and is the single cheapest win in the walk.
        if (!node.isVisibleToUser || node.bounds.isEmpty) return emptyList()

        val childFragments = mutableListOf<String>()
        for (i in 0 until node.childCount) {
            val child = node.childAt(i) ?: continue
            childFragments += visit(child, out, counter)
        }

        val own = ownFragments(node)

        if (!node.isInteractive) return own + childFragments

        if (out.size >= config.maxElements) return emptyList()

        // contentDescription is kept separate from hoisted text: it is this node's own
        // label ("Send"), whereas hoisted text is borrowed content. Merging them loses
        // which is which.
        val label = node.contentDescription?.cleaned()
        val body = joinFragments(own.filter { it != label } + childFragments)

        out += UiElement(
            id = out.size,
            className = node.className.simpleName(),
            text = body,
            contentDescription = label,
            bounds = node.bounds,
            clickable = node.isClickable,
            longClickable = node.isLongClickable,
            editable = node.isEditable,
            scrollable = node.isScrollable,
            checkable = node.isCheckable,
            checked = node.isChecked,
            focused = node.isFocused,
            viewIdResourceName = node.viewIdResourceName,
        )

        return emptyList() // absorbed
    }

    private fun ownFragments(node: UiNode): List<String> =
        listOfNotNull(node.text?.cleaned(), node.contentDescription?.cleaned()).distinct()

    private fun joinFragments(fragments: List<String>): String? {
        if (fragments.isEmpty()) return null
        return fragments
            .distinct()
            .take(config.maxHoistedFragments)
            .joinToString(" · ")
            .take(config.maxTextLength)
    }

    private fun String.cleaned(): String? =
        trim().replace(WHITESPACE, " ").ifBlank { null }?.take(config.maxTextLength)

    private class NodeCounter { var visited = 0 }

    private companion object {
        val WHITESPACE = Regex("\\s+")
    }
}

/** `android.widget.Button` → `Button`. Package prefixes are pure token cost. */
internal fun String.simpleName(): String = substringAfterLast('.').ifBlank { this }
