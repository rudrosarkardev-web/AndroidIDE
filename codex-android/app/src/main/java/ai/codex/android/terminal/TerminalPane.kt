/*
 * Codex for Android — embedded native terminal pane.
 *
 * Copyright (C) 2026 Codex for Android contributors.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package ai.codex.android.terminal

import android.content.Context
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import java.io.File

@Composable
fun TerminalPane(
  workspace: File,
  modifier: Modifier = Modifier,
) {
  val holder = remember(workspace.absolutePath) { TerminalHolder(workspace) }
  DisposableEffect(holder) {
    onDispose { holder.close() }
  }
  AndroidView(
    modifier = modifier,
    factory = { holder.view },
    update = { view ->
      view.setBackgroundColor(android.graphics.Color.rgb(13, 13, 13))
      if (view.tag !== holder.session) {
        view.tag = holder.session
        view.attachSession(holder.session)
      }
    },
  )
}

private class TerminalHolder(private val workspace: File) {
  private val client = TerminalCallbacks()
  val session: TerminalSession = TerminalSession(
    "/system/bin/sh",
    workspace.absolutePath,
    arrayOf("sh", "-i"),
    arrayOf(
      "TERM=xterm-256color",
      "HOME=${workspace.absolutePath}",
      "PWD=${workspace.absolutePath}",
      "PATH=/system/bin:/system/xbin:/data/data/ai.codex.android/files/usr/bin",
      "LANG=C.UTF-8",
    ),
    10_000,
    client,
  ).apply { mSessionName = "Codex workspace" }

  val view: TerminalView = TerminalView(workspaceContext(), null).apply {
    setTerminalViewClient(client)
    setTextSize(14)
    tag = null
  }

  private fun workspaceContext(): Context =
    // TerminalHolder is always created from a Compose Android context. The app
    // context is sufficient for the custom View because no Activity resources are needed.
    ai.codex.android.CodexApp.instance.applicationContext

  fun close() {
    session.finishIfRunning()
  }
}

private class TerminalCallbacks : TerminalSessionClient, TerminalViewClient {
  override fun onTextChanged(changedSession: TerminalSession) = Unit
  override fun onTitleChanged(changedSession: TerminalSession) = Unit
  override fun onSessionFinished(finishedSession: TerminalSession) = Unit
  override fun onCopyTextToClipboard(session: TerminalSession, text: String) = Unit
  override fun onPasteTextFromClipboard(session: TerminalSession?) = Unit
  override fun onBell(session: TerminalSession) = Unit
  override fun onColorsChanged(session: TerminalSession) = Unit
  override fun onTerminalCursorStateChange(state: Boolean) = Unit
  override fun setTerminalShellPid(session: TerminalSession, pid: Int) = Unit
  override fun getTerminalCursorStyle(): Int? = null

  override fun onScale(scale: Float): Float = scale
  override fun onSingleTapUp(e: MotionEvent) = Unit
  override fun shouldBackButtonBeMappedToEscape(): Boolean = false
  override fun shouldEnforceCharBasedInput(): Boolean = true
  override fun shouldUseCtrlSpaceWorkaround(): Boolean = false
  override fun isTerminalViewSelected(): Boolean = true
  override fun copyModeChanged(copyMode: Boolean) = Unit
  override fun onKeyDown(keyCode: Int, e: KeyEvent, session: TerminalSession): Boolean = false
  override fun onKeyUp(keyCode: Int, e: KeyEvent): Boolean = false
  override fun onLongPress(event: MotionEvent): Boolean = false
  override fun readControlKey(): Boolean = false
  override fun readAltKey(): Boolean = false
  override fun readShiftKey(): Boolean = false
  override fun readFnKey(): Boolean = false
  override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession): Boolean = false
  override fun onEmulatorSet() = Unit

  override fun logError(tag: String, message: String) = Log.e(tag, message)
  override fun logWarn(tag: String, message: String) = Log.w(tag, message)
  override fun logInfo(tag: String, message: String) = Log.i(tag, message)
  override fun logDebug(tag: String, message: String) = Log.d(tag, message)
  override fun logVerbose(tag: String, message: String) = Log.v(tag, message)
  override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) = Log.e(tag, message, e)
  override fun logStackTrace(tag: String, e: Exception) = Log.e(tag, e.message, e)
}
