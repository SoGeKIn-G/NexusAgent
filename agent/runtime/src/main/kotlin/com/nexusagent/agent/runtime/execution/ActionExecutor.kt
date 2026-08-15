package com.nexusagent.agent.runtime.execution

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.nexusagent.agent.perception.NexusAccessibilityService
import com.nexusagent.core.model.ActionResult
import com.nexusagent.core.model.AgentAction
import com.nexusagent.core.model.GlobalAction
import com.nexusagent.core.model.ResolvedTarget
import com.nexusagent.core.model.ScrollDirection
import kotlinx.coroutines.delay

/**
 * Turns an [AgentAction] into something that actually happens on the device.
 *
 * ## Fallback chains
 *
 * Every physical action has a ladder, tried strongest-first:
 *
 * ```
 *   click : node.ACTION_CLICK  ->  clickable ancestor  ->  synthesised tap
 *   type  : ACTION_SET_TEXT    ->  focus + ACTION_PASTE
 *   scroll: ACTION_SCROLL_*    ->  synthesised swipe across the element
 * ```
 *
 * The ladders are not defensive padding - each rung fires routinely in practice. Text and
 * icons are usually not clickable themselves, so the ancestor rung carries a large share
 * of all taps; and canvas-rendered surfaces expose no scroll action at all, so feeds are
 * scrolled by synthesised swipe.
 *
 * The executor reports [ActionResult.Ineffective] separately from [ActionResult.Failure].
 * That distinction matters upstream: *failure* means the action never happened and may be
 * worth retrying, while *ineffective* means it happened and changed nothing - so retrying
 * is pointless and the model should try a different target instead.
 */
class ActionExecutor(
    private val context: Context,
    private val resolver: NodeResolver = NodeResolver(),
) {

    private val shortcuts = IntentShortcuts(context)

    val shortcutCatalogue: String get() = shortcuts.catalogue

    suspend fun execute(action: AgentAction, targets: Map<Int, ResolvedTarget>): ActionResult {
        val service = NexusAccessibilityService.instance
            ?: return ActionResult.Failure("Accessibility service is not running")

        return runCatching {
            when (action) {
                is AgentAction.Click -> click(service, targets[action.elementId], longPress = false)
                is AgentAction.LongClick -> click(service, targets[action.elementId], longPress = true)
                is AgentAction.TypeText -> type(service, targets[action.elementId], action.text, action.submit)
                is AgentAction.Scroll -> scroll(service, action.direction, targets[action.elementId])
                is AgentAction.Swipe -> swipeRaw(service, action)
                is AgentAction.Global -> global(service, action.action)
                is AgentAction.LaunchApp -> launchApp(action.packageName)
                is AgentAction.IntentShortcut -> shortcut(action.name, action.params)
                is AgentAction.Wait -> {
                    delay(action.millis.coerceIn(0, MAX_WAIT_MS))
                    ActionResult.Success
                }
                // Handled by the orchestrator, not here - they change the loop's state
                // rather than the device's.
                AgentAction.RequestScreenshot,
                is AgentAction.AskUser,
                is AgentAction.Done,
                is AgentAction.Fail,
                -> ActionResult.Success
            }
        }.getOrElse { throwable ->
            Log.e(TAG, "Action threw", throwable)
            ActionResult.Failure(throwable.message ?: throwable::class.simpleName.orEmpty())
        }
    }

    // -- physical actions ---------------------------------------------------------

    private suspend fun click(
        service: AccessibilityService,
        target: ResolvedTarget?,
        longPress: Boolean,
    ): ActionResult {
        target ?: return ActionResult.Failure("Unknown element id")
        val root = service.rootInActiveWindow ?: return ActionResult.Failure("No active window")
        val gestures = GestureDispatcher(service)

        val resolution = resolver.resolve(root, target)
        val action = if (longPress) {
            AccessibilityNodeInfo.ACTION_LONG_CLICK
        } else {
            AccessibilityNodeInfo.ACTION_CLICK
        }

        if (resolution != null) {
            val node = resolution.node
            Log.d(TAG, "click via ${resolution.strategy}")

            if (node.isEnabled && node.performAction(action)) return ActionResult.Success

            node.nearestClickableAncestor()?.let { ancestor ->
                if (ancestor.performAction(action)) {
                    Log.d(TAG, "click landed on clickable ancestor")
                    return ActionResult.Success
                }
            }

            // The node exists but refuses the action - custom views that draw their own
            // touch handling do this constantly. Tap where it actually is.
            val rect = node.screenBounds()
            val ok = if (longPress) {
                gestures.longPress(rect.centerX(), rect.centerY())
            } else {
                gestures.tap(rect.centerX(), rect.centerY())
            }
            return if (ok) ActionResult.Success else ActionResult.Failure("Gesture rejected")
        }

        // Nothing matched. The element may have scrolled away entirely; tapping its last
        // known position is a guess, and the verification pass upstream is what catches it
        // if the guess was wrong.
        Log.d(TAG, "unresolved - tapping last known bounds")
        val ok = if (longPress) {
            gestures.longPress(target.bounds.centerX, target.bounds.centerY)
        } else {
            gestures.tap(target.bounds.centerX, target.bounds.centerY)
        }
        return if (ok) ActionResult.Success else ActionResult.Failure("Element not found")
    }

    private suspend fun type(
        service: AccessibilityService,
        target: ResolvedTarget?,
        text: String,
        submit: Boolean,
    ): ActionResult {
        target ?: return ActionResult.Failure("Unknown element id")
        val root = service.rootInActiveWindow ?: return ActionResult.Failure("No active window")

        val node = resolver.resolve(root, target)?.node
            ?: return ActionResult.Failure("Text field not found")

        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)

        val arguments = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }

        val typed = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments) ||
            pasteFallback(node, text)

        if (!typed) return ActionResult.Failure("Could not enter text")

        if (submit) {
            // Give the field a moment to settle before firing the IME action; submitting
            // into a half-updated field is a real source of empty searches.
            delay(SUBMIT_SETTLE_MS)
            node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }
        return ActionResult.Success
    }

    /**
     * Clipboard fallback for fields that reject ACTION_SET_TEXT.
     *
     * Rare but real: some custom editors only accept paste. Uses the modern clipboard API
     * and does not attempt to restore the previous clipboard contents - doing so races
     * with the paste and reliably pasted the wrong thing.
     */
    private fun pasteFallback(node: AccessibilityNodeInfo, text: String): Boolean {
        val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)
            ?: return false
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("nexus", text))
        return node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
    }

    private suspend fun scroll(
        service: AccessibilityService,
        direction: ScrollDirection,
        target: ResolvedTarget?,
    ): ActionResult {
        val root = service.rootInActiveWindow ?: return ActionResult.Failure("No active window")
        val gestures = GestureDispatcher(service)

        val node = target?.let { resolver.resolve(root, it)?.node }
            ?: root.findScrollable()

        if (node != null) {
            val action = when (direction) {
                ScrollDirection.FORWARD, ScrollDirection.RIGHT ->
                    AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                ScrollDirection.BACKWARD, ScrollDirection.LEFT ->
                    AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            }
            if (node.isScrollable && node.performAction(action)) return ActionResult.Success
        }

        // No scrollable node - the common case on canvas-drawn feeds. Swipe across the
        // element if we found one, otherwise across the middle of the screen.
        val rect = node?.screenBounds() ?: root.screenBounds()
        val cx = rect.centerX()
        val quarter = rect.height() / 4

        val (fromY, toY) = when (direction) {
            ScrollDirection.FORWARD, ScrollDirection.RIGHT ->
                rect.centerY() + quarter to rect.centerY() - quarter
            ScrollDirection.BACKWARD, ScrollDirection.LEFT ->
                rect.centerY() - quarter to rect.centerY() + quarter
        }

        val ok = gestures.swipe(cx, fromY, cx, toY, SWIPE_DURATION_MS)
        return if (ok) ActionResult.Success else ActionResult.Ineffective
    }

    private suspend fun swipeRaw(service: AccessibilityService, action: AgentAction.Swipe): ActionResult {
        val ok = GestureDispatcher(service)
            .swipe(action.startX, action.startY, action.endX, action.endY, action.durationMs)
        return if (ok) ActionResult.Success else ActionResult.Failure("Swipe rejected")
    }

    private fun global(service: AccessibilityService, action: GlobalAction): ActionResult {
        val id = when (action) {
            GlobalAction.BACK -> AccessibilityService.GLOBAL_ACTION_BACK
            GlobalAction.HOME -> AccessibilityService.GLOBAL_ACTION_HOME
            GlobalAction.RECENTS -> AccessibilityService.GLOBAL_ACTION_RECENTS
            GlobalAction.NOTIFICATIONS -> AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
        }
        return if (service.performGlobalAction(id)) {
            ActionResult.Success
        } else {
            ActionResult.Failure("Global action $action refused")
        }
    }

    private fun launchApp(packageName: String): ActionResult {
        val intent = context.packageManager.getLaunchIntentForPackage(packageName)
            ?: return ActionResult.Failure("$packageName is not installed")
        context.startActivity(intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        return ActionResult.Success
    }

    private fun shortcut(name: String, params: Map<String, String>): ActionResult =
        when (val result = shortcuts.execute(name, params)) {
            ShortcutResult.Success -> ActionResult.Success
            is ShortcutResult.Unknown -> ActionResult.Failure("No shortcut named '${result.name}'")
            is ShortcutResult.BadArguments -> ActionResult.Failure("Bad arguments for '${result.name}'")
            // Recoverable by design: the model is told, and can drive the UI instead.
            is ShortcutResult.NoHandler -> ActionResult.Failure("No app handles '${result.name}'")
        }

    private fun AccessibilityNodeInfo.findScrollable(): AccessibilityNodeInfo? {
        var found: AccessibilityNodeInfo? = null
        forEachNode { node ->
            if (found == null && node.isScrollable && node.isVisibleToUser) found = node
        }
        return found
    }

    private companion object {
        const val TAG = "NexusExecution"
        const val MAX_WAIT_MS = 10_000L
        const val SUBMIT_SETTLE_MS = 150L
        const val SWIPE_DURATION_MS = 300L
    }
}
