/*
 * Codex for Android — foreground service that keeps an agent run alive while the
 * app is in the background. The UI talks to the engine directly; this service only
 * holds a foreground notification and a wake lock.
 *
 * Copyright (C) 2026 Codex for Android contributors.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package ai.codex.android.svc

import ai.codex.android.CodexApp
import ai.codex.android.R
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat

class AgentService : Service() {

  private var wakeLock: PowerManager.WakeLock? = null

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onCreate() {
    super.onCreate()
    val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
    wakeLock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:agent")?.apply {
      setReferenceCounted(false)
      try {
        acquire(6 * 60 * 60 * 1000L)
      } catch (_: Throwable) {
      }
    }
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    when (intent?.action) {
      ACTION_STOP -> {
        stopForegroundCompat()
        stopSelf()
        return START_NOT_STICKY
      }

      else -> startForegroundCompat(intent?.getStringExtra(EXTRA_TEXT) ?: "Agent is working…")
    }
    return START_STICKY
  }

  private fun startForegroundCompat(text: String) {
    val open = Intent(this, ai.codex.android.MainActivity::class.java)
    val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    val pending = PendingIntent.getActivity(this, 1, open, flags)
    val notification: Notification = NotificationCompat.Builder(this, CodexApp.CHANNEL_AGENT)
      .setContentTitle(getString(R.string.app_name))
      .setContentText(text)
      .setSmallIcon(R.drawable.ic_codex_foreground)
      .setOngoing(true)
      .setContentIntent(pending)
      .setPriority(NotificationCompat.PRIORITY_LOW)
      .build()
    try {
      startForeground(NOTIFICATION_ID, notification)
    } catch (_: Throwable) {
    }
  }

  @Suppress("DEPRECATION")
  private fun stopForegroundCompat() {
    try {
      stopForeground(true)
    } catch (_: Throwable) {
    }
  }

  override fun onDestroy() {
    try {
      wakeLock?.release()
    } catch (_: Throwable) {
    }
    wakeLock = null
    super.onDestroy()
  }

  companion object {
    private const val NOTIFICATION_ID = 4711
    const val EXTRA_TEXT = "text"
    const val ACTION_STOP = "ai.codex.android.action.STOP_AGENT"

    fun start(context: Context, text: String) {
      val intent = Intent(context, AgentService::class.java).apply {
        putExtra(EXTRA_TEXT, text)
      }
      try {
        context.startForegroundService(intent)
      } catch (_: Throwable) {
      }
    }

    fun stop(context: Context) {
      try {
        context.stopService(Intent(context, AgentService::class.java))
      } catch (_: Throwable) {
      }
    }
  }
}
