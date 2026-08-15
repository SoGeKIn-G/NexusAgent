package com.nexusagent.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.nexusagent.agent.perception.PerceptionRepository
import com.nexusagent.agent.perception.SnapshotResult
import com.nexusagent.agent.runtime.execution.ExecutionRepository
import com.nexusagent.core.model.AgentAction
import com.nexusagent.core.model.GlobalAction
import com.nexusagent.core.model.ScrollDirection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Debug-only execution hook - M3's acceptance test, driven from a shell.
 *
 * ```
 *   adb shell am broadcast -a com.nexusagent.ACTION --es do click  --ei id 7
 *   adb shell am broadcast -a com.nexusagent.ACTION --es do type   --ei id 3 --es text hello
 *   adb shell am broadcast -a com.nexusagent.ACTION --es do scroll --es dir forward
 *   adb shell am broadcast -a com.nexusagent.ACTION --es do back
 *   adb shell am broadcast -a com.nexusagent.ACTION --es do alarm  --ei hour 6 --ei minutes 30
 * ```
 *
 * Every invocation takes a fresh snapshot before acting, which is not incidental: element
 * ids are only meaningful within the snapshot that issued them, so acting on an id from a
 * stale capture would target whatever now happens to occupy that index.
 */
class DebugActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()

        CoroutineScope(Dispatchers.Default).launch {
            try {
                val perception = PerceptionRepository()
                val execution = ExecutionRepository(context.applicationContext)

                when (val snapshot = perception.snapshot()) {
                    is SnapshotResult.Success -> {
                        execution.bind(snapshot.snapshot)
                        Log.i(TAG, "bound ${execution.boundElementCount} elements from ${snapshot.snapshot.packageName}")

                        val action = parse(intent)
                        if (action == null) {
                            Log.w(TAG, "unrecognised action: ${intent.getStringExtra("do")}")
                            return@launch
                        }

                        Log.i(TAG, "executing $action")
                        val result = execution.execute(action)
                        Log.i(TAG, "result: $result")

                        // Show the caller what the screen became, so a "did it work?"
                        // question is answerable from the log alone.
                        perception.awaitScreenSettled()
                        (perception.snapshot() as? SnapshotResult.Success)?.let {
                            Log.i(TAG, "after: ${it.snapshot.packageName} / ${it.snapshot.activityName}")
                        }
                    }

                    else -> Log.w(TAG, "no snapshot: $snapshot")
                }
            } finally {
                pending.finish()
            }
        }
    }

    private fun parse(intent: Intent): AgentAction? {
        val id = intent.getIntExtra("id", -1)
        return when (intent.getStringExtra("do")?.lowercase()) {
            "click" -> id.takeIf { it >= 0 }?.let { AgentAction.Click(it) }
            "longclick" -> id.takeIf { it >= 0 }?.let { AgentAction.LongClick(it) }
            "type" -> id.takeIf { it >= 0 }?.let {
                AgentAction.TypeText(
                    elementId = it,
                    text = intent.getStringExtra("text").orEmpty(),
                    submit = intent.getBooleanExtra("submit", false),
                )
            }
            "scroll" -> AgentAction.Scroll(
                direction = when (intent.getStringExtra("dir")?.lowercase()) {
                    "backward", "up" -> ScrollDirection.BACKWARD
                    else -> ScrollDirection.FORWARD
                },
                elementId = id.takeIf { it >= 0 },
            )
            "back" -> AgentAction.Global(GlobalAction.BACK)
            "home" -> AgentAction.Global(GlobalAction.HOME)
            "recents" -> AgentAction.Global(GlobalAction.RECENTS)
            "launch" -> intent.getStringExtra("pkg")?.let { AgentAction.LaunchApp(it) }
            "alarm" -> AgentAction.IntentShortcut(
                name = "set_alarm",
                params = buildMap {
                    put("hour", intent.getIntExtra("hour", 7).toString())
                    put("minutes", intent.getIntExtra("minutes", 0).toString())
                    intent.getStringExtra("label")?.let { put("label", it) }
                },
            )
            "url" -> intent.getStringExtra("url")?.let {
                AgentAction.IntentShortcut("open_url", mapOf("url" to it))
            }
            else -> null
        }
    }

    private companion object {
        const val TAG = "NexusExecution"
    }
}
