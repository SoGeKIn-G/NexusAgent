package com.nexusagent.agent.runtime.reasoning

import com.nexusagent.core.model.ActionResult
import com.nexusagent.core.model.AgentAction
import com.nexusagent.core.model.StepRecord

/**
 * Builds the ReAct prompt.
 *
 * Two messages per turn: a fixed system instruction describing the world and the rules,
 * and a user message carrying the goal, what has been tried, and the current screen.
 * Keeping the system half constant is what makes provider-side prompt caching effective -
 * it is by far the largest static block and is identical on every step of every run.
 */
object PromptBuilder {

    fun system(shortcutCatalogue: String): String = """
You control an Android phone by choosing ONE action at a time.

You are shown a compressed view of the current screen: only elements a user could
interact with, plus any text belonging to them. Choose the single next action that
best advances the goal, then stop. You will be shown the result and asked again.

SCREEN FORMAT
  {"pkg":"<app>","act":"<screen>","el":[ ...elements... ]}
Each element:
  i  id you refer to        k  clickable        s  scrollable
  c  widget class           e  editable         x  checked (0/1)
  t  text content           l  long-clickable   f  focused
  d  accessibility label    b  bounds [left,top,right,bottom]

Text from non-interactive children is merged into its interactive parent and joined
with " · ", so a chat row may read "Rahul · Hey, running late · 2:14 PM".

ACTIONS
  click          element_id
  long_click     element_id
  type           element_id, text, submit(bool - also presses enter/search)
  scroll         direction("forward"|"backward"), element_id(optional)
  back | home | recents | notifications
  launch_app     package_name
  intent         shortcut, params_json
  wait           millis
  screenshot     (request an image when the screen is too sparse to understand)
  ask_user       question (when required information is missing or ambiguous)
  done           summary
  fail           reason

INTENT SHORTCUTS - prefer these over navigating the UI when one fits.
They are instant and cannot mis-tap.
$shortcutCatalogue

params_json is a JSON object encoded as a STRING, with every value a string.
It is REQUIRED whenever action is "intent". A shortcut with empty params does nothing.

Worked example - "set an alarm for 6:30 am":
  {"thought":"An alarm shortcut exists, so no need to open the Clock app.",
   "action":"intent","shortcut":"set_alarm",
   "params_json":"{\"hour\":\"6\",\"minutes\":\"30\"}"}

Worked example - "set an alarm for 7:45 pm" (24-hour clock, so 19):
  {"thought":"Evening alarm, so hour 19.",
   "action":"intent","shortcut":"set_alarm",
   "params_json":"{\"hour\":\"19\",\"minutes\":\"45\"}"}

RULES
1. Only ever use an id that appears in the CURRENT screen. Ids change every step.
2. If a shortcut accomplishes the goal, use it instead of opening the app.
3. If the app you need is not open, use launch_app.
4. If the last action did nothing, do NOT repeat it - try a different element,
   scroll to reveal more, or go back.
5. Scroll before concluding something is absent. Lists are usually longer than one screen.
6. If the goal is already satisfied by what you see, answer done immediately.
7. Never guess a person's contact, a payment amount, or a recipient. Use ask_user.
8. Keep "thought" to one short sentence saying why this action, now.
9. For system toggles - aeroplane mode, Wi-Fi, Bluetooth, dark mode, brightness -
   use launch_app("com.android.settings") and navigate the Settings app. Do NOT use
   the notifications shade: its quick-settings tiles are drawn as custom views that
   expose almost nothing to act on, and attempts to drive them go in circles.
10. If HISTORY shows you have already visited a screen twice without progress, stop
   trying that route. Take a different one, or answer fail with what blocked you.
    """.trimIndent()

    fun user(turn: AgentTurn): String = buildString {
        append("GOAL: ").append(turn.goal).append('\n')
        append("STEP: ").append(turn.run.stepIndex + 1).append(" of ").append(turn.maxSteps).append('\n')

        if (turn.run.history.isEmpty()) {
            append("HISTORY: (nothing yet - this is the first step)\n")
        } else {
            append("HISTORY:\n")
            // Only the tail. Early steps stop being informative quickly, and the whole
            // point of the compressor is undone if history grows without bound.
            turn.run.history.takeLast(MAX_HISTORY).forEach { append(it.render()) }
        }

        turn.lastError?.let {
            append("\nYOUR LAST RESPONSE WAS REJECTED: ").append(it)
                .append("\nReturn valid JSON matching the schema this time.\n")
        }

        append("\nCURRENT SCREEN:\n").append(turn.screenJson).append('\n')

        if (turn.screenshotBase64Jpeg != null) {
            append("\nA screenshot is attached because this screen exposes little ")
                .append("structure. Use it to locate elements, but you may still only ")
                .append("act on ids listed above.\n")
        }
    }

    private fun StepRecord.render(): String {
        // Bound to a local: `result` is a property from another module, so the compiler
        // will not smart-cast it inside the branch.
        val outcome = when (val outcome = result) {
            ActionResult.Success -> "ok"
            // Phrased as an instruction, not a status: "ineffective" alone reads to a
            // model like a transient failure worth retrying, which is the opposite of
            // what it should conclude.
            ActionResult.Ineffective -> "NO CHANGE - that did nothing, try something else"
            is ActionResult.Failure -> "failed: ${outcome.reason}"
        }
        return "  ${index + 1}. ${action.describe()} -> $outcome\n"
    }

    private fun AgentAction.describe(): String = when (this) {
        is AgentAction.Click -> "click($elementId)"
        is AgentAction.LongClick -> "long_click($elementId)"
        is AgentAction.TypeText -> "type($elementId, \"${text.take(40)}\")"
        is AgentAction.Scroll -> "scroll($direction)"
        is AgentAction.Swipe -> "swipe"
        is AgentAction.Global -> action.name.lowercase()
        is AgentAction.LaunchApp -> "launch_app($packageName)"
        is AgentAction.IntentShortcut -> "intent($name, $params)"
        is AgentAction.Wait -> "wait(${millis}ms)"
        AgentAction.RequestScreenshot -> "screenshot"
        is AgentAction.AskUser -> "ask_user"
        is AgentAction.Done -> "done"
        is AgentAction.Fail -> "fail"
    }

    private const val MAX_HISTORY = 8
}
