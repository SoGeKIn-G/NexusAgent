package com.nexusagent.core.model

/**
 * A read-only view of one node in the screen hierarchy.
 *
 * This exists so the compression algorithm never touches `AccessibilityNodeInfo`.
 * That buys two things:
 *
 *  1. **Testability.** The pruning and hoisting logic is the trickiest code in the
 *     project, and here it can be tested on the JVM against hand-built trees — no
 *     device, no emulator, no Robolectric.
 *  2. **Honesty about lifetimes.** `AccessibilityNodeInfo` is a Binder handle into
 *     another process. Keeping the algorithm on an interface makes it structurally
 *     impossible to accidentally retain one past the walk.
 *
 * The Android-backed implementation lives in `:agent:perception`.
 */
interface UiNode {
    val className: String
    val text: String?
    val contentDescription: String?
    val viewIdResourceName: String?
    val bounds: Bounds

    val isVisibleToUser: Boolean
    val isClickable: Boolean
    val isLongClickable: Boolean
    val isEditable: Boolean
    val isScrollable: Boolean
    val isCheckable: Boolean
    val isChecked: Boolean
    val isFocused: Boolean
    val isEnabled: Boolean

    val childCount: Int
    fun childAt(index: Int): UiNode?
}

/**
 * A node the agent can actually do something with.
 *
 * Note that plain text is deliberately **not** in this list. A `TextView` is content,
 * not a target — its text gets hoisted into the nearest interactive ancestor instead of
 * becoming its own element. That single decision is where most of the compression comes
 * from: a chat list row is one clickable element carrying "Rahul · Hey, running late",
 * not three separate nodes.
 */
val UiNode.isInteractive: Boolean
    get() = isEnabled && (isClickable || isLongClickable || isEditable || isScrollable || isCheckable)
