/*
 * Codex for Android — application entry point.
 *
 * Copyright (C) 2026 Codex for Android contributors.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package ai.codex.android

import ai.codex.android.core.AppContainer
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build

class CodexApp : Application() {

  override fun onCreate() {
    super.onCreate()
    instance = this
    AppContainer.init(this)
    createNotificationChannels()
  }

  private fun createNotificationChannels() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
    val manager = getSystemService(NotificationManager::class.java) ?: return
    val agent = NotificationChannel(
      CHANNEL_AGENT,
      getString(R.string.app_name) + " agent",
      NotificationManager.IMPORTANCE_LOW
    ).apply {
      description = "Shows the status of a running agent task"
      setShowBadge(false)
    }
    val terminal = NotificationChannel(
      CHANNEL_TERMINAL,
      getString(R.string.app_name) + " terminal",
      NotificationManager.IMPORTANCE_LOW
    ).apply {
      description = "Keeps terminal sessions alive in the background"
      setShowBadge(false)
    }
    manager.createNotificationChannel(agent)
    manager.createNotificationChannel(terminal)
  }

  companion object {
    const val CHANNEL_AGENT = "codex.agent"
    const val CHANNEL_TERMINAL = "codex.terminal"

    lateinit var instance: CodexApp
      private set
  }
}
