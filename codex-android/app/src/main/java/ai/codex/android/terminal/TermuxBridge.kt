/*
 * Codex for Android — optional Termux command bridge.
 *
 * The embedded shell works on its own. If the user also has Termux installed,
 * these helpers can ask Termux's RUN_COMMAND service to install the usual coding
 * tools in Termux's prefix, where git/python/node/ripgrep and package updates
 * are supported by Termux's package manager.
 *
 * Copyright (C) 2026 Codex for Android contributors.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package ai.codex.android.terminal

import android.content.ComponentName
import android.content.Context
import android.content.Intent

object TermuxBridge {
  private const val TERMUX_PACKAGE = "com.termux"
  private const val RUN_COMMAND = "com.termux.RUN_COMMAND"
  private const val RUN_COMMAND_SERVICE = "com.termux.app.RunCommandService"
  private const val EXTRA_COMMAND_PATH = "com.termux.RUN_COMMAND_PATH"
  private const val EXTRA_ARGUMENTS = "com.termux.RUN_COMMAND_ARGUMENTS"
  private const val EXTRA_BACKGROUND = "com.termux.RUN_COMMAND_BACKGROUND"
  private const val EXTRA_LABEL = "com.termux.RUN_COMMAND_COMMAND_LABEL"

  fun isInstalled(context: Context): Boolean = runCatching {
    context.packageManager.getPackageInfo(TERMUX_PACKAGE, 0)
    true
  }.getOrDefault(false)

  fun open(context: Context): Boolean = runCatching {
    context.startActivity(context.packageManager.getLaunchIntentForPackage(TERMUX_PACKAGE))
    true
  }.getOrDefault(false)

  fun installCommonTools(context: Context): Boolean = runCommand(
    context = context,
    command = "pkg update -y && pkg install -y git python nodejs ripgrep clang make",
    label = "Install coding tools",
  )

  fun runCommand(context: Context, command: String, label: String = "Codex command"): Boolean = runCatching {
    val intent = Intent(RUN_COMMAND).apply {
      component = ComponentName(TERMUX_PACKAGE, RUN_COMMAND_SERVICE)
      putExtra(EXTRA_COMMAND_PATH, "/data/data/com.termux/files/usr/bin/bash")
      putExtra(EXTRA_ARGUMENTS, arrayOf("-lc", command))
      putExtra(EXTRA_BACKGROUND, true)
      putExtra(EXTRA_LABEL, label)
    }
    context.startService(intent)
    true
  }.getOrDefault(false)
}
