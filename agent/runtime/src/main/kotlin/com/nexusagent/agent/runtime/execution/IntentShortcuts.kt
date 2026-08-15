package com.nexusagent.agent.runtime.execution

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.util.Log

/**
 * The agent's fast path.
 *
 * Some goals map onto a first-class Android Intent. "Set an alarm for 6am" through the UI
 * is six fragile steps - open Clock, find the alarm tab, tap add, set hour, set minute,
 * save - each of which can mis-tap and each of which costs an LLM round-trip. The same
 * goal through [AlarmClock.ACTION_SET_ALARM] is one step that cannot miss.
 *
 * The catalogue is described to the model in the system prompt so it can *choose*. That
 * choice is the interesting part of the design: the agent has a deterministic path and a
 * general path, and picks per task. Instagram has no Intent for "like the third post", so
 * that still goes through the UI - as it must.
 */
class IntentShortcuts(private val context: Context) {

    /** Sent to the model verbatim, so what it may request and what exists here cannot drift. */
    val catalogue: String = """
        set_alarm(hour:0-23, minutes:0-59, label?)  - create an alarm
        set_timer(seconds, label?)                  - start a countdown
        dial(number)                                - open the dialer, pre-filled (does NOT call)
        open_url(url)                               - open a web page
        share_text(text)                            - open the system share sheet
        create_event(title, begin?, end?)           - new calendar event
    """.trimIndent()

    fun execute(name: String, params: Map<String, String>): ShortcutResult {
        val intent = when (name.lowercase()) {
            "set_alarm" -> setAlarm(params)
            "set_timer" -> setTimer(params)
            "dial" -> dial(params)
            "open_url" -> openUrl(params)
            "share_text" -> shareText(params)
            "create_event" -> createEvent(params)
            else -> return ShortcutResult.Unknown(name)
        } ?: return ShortcutResult.BadArguments(name)

        return try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            ShortcutResult.Success
        } catch (e: ActivityNotFoundException) {
            // No app handles it on this device. Recoverable: the model is told, and can
            // fall back to driving the UI instead.
            Log.w(TAG, "No handler for $name", e)
            ShortcutResult.NoHandler(name)
        }
    }

    private fun setAlarm(params: Map<String, String>): Intent? {
        val hour = params["hour"]?.toIntOrNull()?.takeIf { it in 0..23 } ?: return null
        val minutes = params["minutes"]?.toIntOrNull()?.takeIf { it in 0..59 } ?: 0

        return Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, hour)
            putExtra(AlarmClock.EXTRA_MINUTES, minutes)
            params["label"]?.let { putExtra(AlarmClock.EXTRA_MESSAGE, it) }
            // Without this the Clock app merely opens its create-alarm form pre-filled and
            // waits for a human to confirm - which defeats the point of the fast path.
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        }
    }

    private fun setTimer(params: Map<String, String>): Intent? {
        val seconds = params["seconds"]?.toIntOrNull()?.takeIf { it > 0 } ?: return null
        return Intent(AlarmClock.ACTION_SET_TIMER).apply {
            putExtra(AlarmClock.EXTRA_LENGTH, seconds)
            params["label"]?.let { putExtra(AlarmClock.EXTRA_MESSAGE, it) }
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        }
    }

    /**
     * ACTION_DIAL, never ACTION_CALL.
     *
     * Deliberate: dialling places a real call with real cost and real social consequence.
     * The agent gets the number onto the dialer; a human presses the green button.
     */
    private fun dial(params: Map<String, String>): Intent? {
        val number = params["number"]?.takeIf { it.isNotBlank() } ?: return null
        return Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number"))
    }

    private fun openUrl(params: Map<String, String>): Intent? {
        val raw = params["url"]?.takeIf { it.isNotBlank() } ?: return null
        val url = if (raw.startsWith("http://") || raw.startsWith("https://")) raw else "https://$raw"
        return Intent(Intent.ACTION_VIEW, Uri.parse(url))
    }

    private fun shareText(params: Map<String, String>): Intent? {
        val text = params["text"]?.takeIf { it.isNotBlank() } ?: return null
        return Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
    }

    private fun createEvent(params: Map<String, String>): Intent? {
        val title = params["title"]?.takeIf { it.isNotBlank() } ?: return null
        return Intent(Intent.ACTION_INSERT).apply {
            data = CalendarContract.Events.CONTENT_URI
            putExtra(CalendarContract.Events.TITLE, title)
            params["begin"]?.toLongOrNull()?.let {
                putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, it)
            }
            params["end"]?.toLongOrNull()?.let {
                putExtra(CalendarContract.EXTRA_EVENT_END_TIME, it)
            }
        }
    }

    private companion object {
        const val TAG = "NexusExecution"
    }
}

sealed interface ShortcutResult {
    data object Success : ShortcutResult
    data class Unknown(val name: String) : ShortcutResult
    data class BadArguments(val name: String) : ShortcutResult
    data class NoHandler(val name: String) : ShortcutResult
}
