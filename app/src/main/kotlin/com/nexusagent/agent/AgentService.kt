package com.nexusagent.agent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.getSystemService
import com.nexusagent.MainActivity
import com.nexusagent.R
import com.nexusagent.agent.runtime.orchestrator.AgentOrchestrator
import com.nexusagent.core.model.AgentState
import com.nexusagent.overlay.OverlayController
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import javax.inject.Inject

/**
 * Keeps the process alive while a task runs.
 *
 * The service deliberately does **not** own the agent loop - [AgentOrchestrator] is an
 * application-scoped singleton. If the loop lived here it would die with the service on
 * any restart, and the run would vanish mid-task. The service's only responsibilities are
 * holding the process foregrounded and showing the user what is happening with a way to
 * stop it.
 *
 * Started when a task begins and stopped when it ends, rather than running permanently:
 * a persistent notification for an idle agent is the kind of thing users uninstall over.
 */
@AndroidEntryPoint
class AgentService : Service() {

    @Inject lateinit var orchestrator: AgentOrchestrator
    @Inject lateinit var overlay: OverlayController

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForegroundCompat(buildNotification("Starting...", null))

        // The bubble's lifetime matches the run's, not the app's. Attached here rather
        // than from the Activity because the UI is backgrounded within a second of a
        // task starting - which is the whole point.
        overlay.attach(this, scope)

        scope.launch {
            orchestrator.state.collectLatest { state ->
                when (state) {
                    is AgentState.Done, is AgentState.Failed, AgentState.Idle -> {
                        // Terminal: drop the notification and release the process.
                        stopSelf()
                    }
                    else -> notify(buildNotification(state.headline(), state.detail()))
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            orchestrator.stop()
            stopSelf()
            return START_NOT_STICKY
        }
        intent?.getStringExtra(EXTRA_GOAL)?.let { orchestrator.start(it) }

        // NOT_STICKY on purpose: if the system kills us mid-task, silently restarting and
        // resuming a half-finished sequence of taps would be worse than stopping.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        overlay.detach()
        scope.cancel()
        super.onDestroy()
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= 34) {
            // No existing foreground type describes "an agent operating the phone", so
            // specialUse is the honest choice. Play would require a justification here;
            // this app does not ship there (see PLAN.md).
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun notify(notification: Notification) {
        getSystemService<NotificationManager>()?.notify(NOTIFICATION_ID, notification)
    }

    private fun buildNotification(title: String, detail: String?): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, AgentService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(detail)
            .setStyle(NotificationCompat.BigTextStyle().bigText(detail))
            .setSmallIcon(R.drawable.ic_agent_notification)
            .setContentIntent(open)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            // A stop control that is always one tap away, wherever the user happens to be.
            .addAction(0, "Stop", stop)
            .build()
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Running tasks",
            NotificationManager.IMPORTANCE_LOW, // no sound; the agent is already noisy visually
        ).apply {
            description = "Shown while NexusAgent is carrying out a task"
            setShowBadge(false)
        }
        getSystemService<NotificationManager>()?.createNotificationChannel(channel)
    }

    private fun AgentState.headline(): String = when (this) {
        is AgentState.Planning -> "Thinking..."
        is AgentState.Retrying -> "Rate limited - retrying in ${(retryInMillis / 1000) + 1}s"
        is AgentState.Executing -> "Acting..."
        is AgentState.Verifying -> "Checking..."
        is AgentState.AwaitingConfirmation -> "Needs your approval"
        is AgentState.AwaitingUserInput -> "Needs your answer"
        is AgentState.Listening -> "Listening..."
        else -> "NexusAgent"
    }

    private fun AgentState.detail(): String? = when (this) {
        is AgentState.Planning -> run.goal
        is AgentState.Retrying -> reason
        is AgentState.Executing -> decision.thought
        is AgentState.Verifying -> decision.thought
        is AgentState.AwaitingConfirmation -> prompt
        is AgentState.AwaitingUserInput -> question
        else -> null
    }

    companion object {
        private const val CHANNEL_ID = "agent_runs"
        private const val NOTIFICATION_ID = 1001
        private const val ACTION_STOP = "com.nexusagent.STOP"
        private const val EXTRA_GOAL = "goal"

        fun start(context: Context, goal: String) {
            val intent = Intent(context, AgentService::class.java).putExtra(EXTRA_GOAL, goal)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, AgentService::class.java).setAction(ACTION_STOP),
            )
        }
    }
}
