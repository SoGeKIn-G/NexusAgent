package com.nexusagent.core.model

/**
 * The complete set of things the agent is allowed to do.
 *
 * This is the contract between the LLM and the execution runtime: the model may only
 * emit one of these, and the executor only knows how to perform these. Keeping the set
 * small and closed is what makes the loop predictable — an open-ended "run this code"
 * action would be impossible to guard, verify, or recover from.
 *
 * Serialization annotations are added in M4, when the reasoning layer lands.
 */
sealed interface AgentAction {

    /** Tap an element. Falls back through ancestor-click and raw gesture — see NodeResolver. */
    data class Click(val elementId: Int) : AgentAction

    data class LongClick(val elementId: Int) : AgentAction

    /**
     * Type into a field.
     * @param submit also fire the IME action (search/send) after typing.
     */
    data class TypeText(
        val elementId: Int,
        val text: String,
        val submit: Boolean = false,
    ) : AgentAction

    data class Scroll(
        val direction: ScrollDirection,
        val elementId: Int? = null,
    ) : AgentAction

    /** Raw coordinate swipe. For canvas-rendered surfaces that expose no scrollable node. */
    data class Swipe(
        val startX: Int,
        val startY: Int,
        val endX: Int,
        val endY: Int,
        val durationMs: Long = 300L,
    ) : AgentAction

    data class Global(val action: GlobalAction) : AgentAction

    /** Skips several steps of home-screen hunting. */
    data class LaunchApp(val packageName: String) : AgentAction

    /**
     * The fast path. Some goals map to a first-class Android Intent that is instant and
     * cannot mis-tap — "set an alarm" becomes one deterministic step instead of six
     * fragile ones. The model chooses between this and UI navigation.
     */
    data class IntentShortcut(
        val name: String,
        val params: Map<String, String> = emptyMap(),
    ) : AgentAction

    data class Wait(val millis: Long) : AgentAction

    /** Model asks to see pixels — used when the accessibility tree is too sparse to ground on. */
    data object RequestScreenshot : AgentAction

    /** Required information is missing or ambiguous. Pauses the run and asks the user. */
    data class AskUser(val question: String) : AgentAction

    /** Terminal: goal achieved. */
    data class Done(val summary: String) : AgentAction

    /** Terminal: goal cannot be achieved. */
    data class Fail(val reason: String) : AgentAction
}

enum class ScrollDirection { FORWARD, BACKWARD, LEFT, RIGHT }

enum class GlobalAction { BACK, HOME, RECENTS, NOTIFICATIONS }

/**
 * One turn of the ReAct loop. [thought] is not decoration — it streams to the floating
 * overlay so the user can watch the agent reason while it drives another app.
 */
data class AgentDecision(
    val thought: String,
    val action: AgentAction,
    val confidence: Float = 1f,
)

/** Outcome of dispatching an action, fed back into the next prompt. */
sealed interface ActionResult {
    data object Success : ActionResult

    /**
     * The action dispatched but the screen did not change. Distinct from [Failure]:
     * the tap landed, it just did nothing — so the model should try a different target
     * rather than retry the same one.
     */
    data object Ineffective : ActionResult

    data class Failure(val reason: String) : ActionResult
}
