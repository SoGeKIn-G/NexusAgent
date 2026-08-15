package com.nexusagent.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.nexusagent.agent.AgentService

/**
 * Debug-only: starts a full agent run from a shell.
 *
 * ```
 *   adb shell am broadcast -a com.nexusagent.RUN --es goal 'set an alarm for 7:45 am'
 *   adb shell am broadcast -a com.nexusagent.STOPRUN
 * ```
 *
 * Needed because [AgentService] is not exported - and should not be, since anything able
 * to start it can make the phone operate itself. A receiver in the debug variant is the
 * narrow opening: present only in development builds, and it does nothing except forward
 * a goal to the same service the UI uses.
 */
class DebugRunReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_RUN -> {
                val goal = intent.getStringExtra("goal")
                if (goal.isNullOrBlank()) {
                    Log.w(TAG, "No goal given")
                    return
                }
                Log.i(TAG, "starting run: $goal")
                AgentService.start(context.applicationContext, goal)
            }

            ACTION_STOP -> {
                Log.i(TAG, "stopping run")
                AgentService.stop(context.applicationContext)
            }
        }
    }

    private companion object {
        const val TAG = "NexusAgent"
        const val ACTION_RUN = "com.nexusagent.RUN"
        const val ACTION_STOP = "com.nexusagent.STOPRUN"
    }
}
