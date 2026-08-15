package com.nexusagent.agent.runtime.orchestrator

import com.nexusagent.core.model.AgentAction
import com.nexusagent.core.model.ScreenSnapshot

/**
 * Decides whether an action deserves a human's explicit approval before it happens.
 *
 * The agent holds gesture privileges over every app on the phone, including messaging and
 * payment apps. Most mistakes it makes are recoverable - a wrong tap navigates somewhere
 * odd and the next step corrects it. A small set are not: a sent message cannot be unsent,
 * a payment cannot be untapped, a deleted thread does not come back.
 *
 * So this is deliberately **over-inclusive**. A false positive costs one confirmation tap;
 * a false negative sends a stranger a message. Matching is on the target element's own
 * label rather than the goal text, because what matters is the button about to be pressed,
 * not what the user asked for twenty seconds ago.
 */
object DestructiveActions {

    private val RISKY_LABELS = listOf(
        "send", "pay", "delete", "remove", "confirm", "order", "buy", "purchase",
        "transfer", "checkout", "place order", "book now", "submit", "post",
        "unfriend", "block", "leave", "log out", "sign out", "uninstall",
        "clear all", "erase", "reset", "call",
    )

    /** Apps where an irreversible action is a tap away and the stakes are real. */
    private val SENSITIVE_PACKAGES = listOf(
        "upi", "pay", "bank", "wallet", "money", "paytm", "phonepe", "gpay",
    )

    fun requiresConfirmation(action: AgentAction, snapshot: ScreenSnapshot?): Boolean {
        // Typing is safe; it is the button afterwards that commits. Gating typing too
        // would make the agent ask twice for one decision.
        val elementId = when (action) {
            is AgentAction.Click -> action.elementId
            is AgentAction.LongClick -> action.elementId
            is AgentAction.TypeText -> return action.submit && looksSensitive(snapshot)
            is AgentAction.IntentShortcut ->
                // dial() only opens the dialer pre-filled, so it is not gated; a real
                // call still needs a human to press the green button.
                return false
            else -> return false
        }

        val element = snapshot?.elements?.firstOrNull { it.id == elementId } ?: return false
        val label = (element.contentDescription.orEmpty() + " " + element.text.orEmpty()).lowercase()

        if (RISKY_LABELS.any { label.containsWord(it) }) return true

        // On a payments app, a bare "Proceed" is as consequential as one labelled "Pay".
        return looksSensitive(snapshot) && label.isNotBlank()
    }

    fun describe(action: AgentAction, snapshot: ScreenSnapshot?): String {
        val elementId = when (action) {
            is AgentAction.Click -> action.elementId
            is AgentAction.LongClick -> action.elementId
            else -> null
        }
        val label = elementId
            ?.let { id -> snapshot?.elements?.firstOrNull { it.id == id } }
            ?.let { it.contentDescription ?: it.text }

        val app = snapshot?.packageName?.substringAfterLast('.')?.replaceFirstChar { it.uppercase() }
        return if (label != null) {
            "Tap \"$label\"${if (app != null) " in $app" else ""}?"
        } else {
            "Allow this action?"
        }
    }

    private fun looksSensitive(snapshot: ScreenSnapshot?): Boolean {
        val pkg = snapshot?.packageName?.lowercase() ?: return false
        return SENSITIVE_PACKAGES.any { pkg.contains(it) }
    }

    /**
     * Word-boundary match, so "Send" is caught but "Sender name" and "Resend code" are
     * not treated identically to a send button... and, more importantly, so "call" does
     * not fire on "Recall" or "Called".
     */
    private fun String.containsWord(word: String): Boolean =
        Regex("\\b${Regex.escape(word)}\\b").containsMatchIn(this)
}
