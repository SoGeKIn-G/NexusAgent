package com.nexusagent.core.model

/**
 * Freezes an observed element into a description that can be re-found later.
 *
 * The snapshot the model reasons about is a photograph of a screen that has since moved
 * on: lists scroll, rows recycle, dialogs appear. By the time an action is dispatched the
 * original node may not exist. Converting to a [ResolvedTarget] makes that explicit -
 * execution re-finds the element by what it *is*, never by a handle to what it *was*.
 */
fun UiElement.toTarget(): ResolvedTarget = ResolvedTarget(
    viewIdResourceName = viewIdResourceName,
    text = text,
    contentDescription = contentDescription,
    className = className,
    bounds = bounds,
)

/** How an element was re-found at execution time. Recorded for diagnosing flaky steps. */
enum class ResolutionStrategy {
    /** Strongest: a stable resource id set by the app's developer. */
    VIEW_ID,
    TEXT,
    CONTENT_DESCRIPTION,
    /** Weakest: whatever interactive node now sits where the element used to be. */
    BOUNDS,
    /** Nothing matched; the caller falls back to a raw coordinate tap. */
    NONE,
}
