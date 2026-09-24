/*
 * Codex for Android — Compose workspace UI.
 *
 * The layout intentionally keeps the important Codex surfaces together: a compact
 * workspace rail, model/key routing at the top, a tool-aware transcript, and a
 * terminal/files area for local work. API routes are managed in a modal-friendly
 * screen and are never rendered with their plaintext secret.
 *
 * Copyright (C) 2026 Codex for Android contributors.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package ai.codex.android.ui

import ai.codex.android.CodexViewModel
import ai.codex.android.data.AgentEvent
import ai.codex.android.data.ApiKeyProfile
import ai.codex.android.data.ChatMessage
import ai.codex.android.data.ProviderKind
import ai.codex.android.data.defaultBaseUrl
import ai.codex.android.data.defaultModel
import ai.codex.android.terminal.TerminalPane
import ai.codex.android.terminal.TermuxBridge
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.io.File

private val Bg = Color(0xFF0D0D0D)
private val Panel = Color(0xFF151515)
private val PanelRaised = Color(0xFF1C1C1C)
private val PanelSelected = Color(0xFF233A44)
private val Border = Color(0xFF2B2B2B)
private val Muted = Color(0xFF979797)
private val Accent = Color(0xFF8BD5FF)
private val Good = Color(0xFF86D993)
private val Warning = Color(0xFFFFC777)

private enum class AppTab(val label: String, val title: String) {
  CHAT("Chat", "New task"),
  TERMINAL("Terminal", "Terminal"),
  FILES("Files", "Workspace"),
  KEYS("Keys", "API routes"),
  SETTINGS("Settings", "Settings"),
}

@Composable
fun CodexScreen(viewModel: CodexViewModel) {
  val messages by viewModel.messages.collectAsStateCompat()
  val keys by viewModel.keys.collectAsStateCompat()
  val busy by viewModel.busy.collectAsStateCompat()
  val event by viewModel.events.collectAsStateCompat()
  val selectedId by viewModel.selectedProfileId.collectAsStateCompat()
  var tab by rememberSaveable { mutableStateOf(AppTab.CHAT) }
  var showModelPicker by remember { mutableStateOf(false) }
  var showAddProfile by remember { mutableStateOf(false) }
  var showImport by remember { mutableStateOf(false) }

  Surface(modifier = Modifier.fillMaxSize(), color = Bg) {
    Row(Modifier.fillMaxSize().safeDrawingPadding()) {
      Sidebar(
        tab = tab,
        keyCount = keys.size,
        selected = keys.firstOrNull { it.id == selectedId },
        onTab = { tab = it },
        onNewTask = { viewModel.newTask(); tab = AppTab.CHAT },
      )
      VerticalDivider(color = Border, thickness = 1.dp)
      Column(Modifier.fillMaxSize()) {
        Header(
          tab = tab,
          selected = keys.firstOrNull { it.id == selectedId },
          keyCount = keys.size,
          event = event,
          busy = busy,
          onModelClick = { showModelPicker = true },
          onStop = viewModel::stop,
        )
        when (tab) {
          AppTab.CHAT -> ChatWorkspace(viewModel, messages, busy, event)
          AppTab.TERMINAL -> TerminalWorkspace(viewModel.workspaceRoot)
          AppTab.FILES -> FilesWorkspace(viewModel.workspaceRoot)
          AppTab.KEYS -> KeysWorkspace(
            viewModel = viewModel,
            keys = keys,
            selectedId = selectedId,
            onAdd = { showAddProfile = true },
            onImport = { showImport = true },
          )
          AppTab.SETTINGS -> SettingsWorkspace(viewModel.workspaceRoot, keyCount = keys.size)
        }
      }
    }
  }

  if (showModelPicker) {
    ModelPickerDialog(
      keys = keys,
      selectedId = selectedId,
      onSelect = { viewModel.selectProfile(it); showModelPicker = false },
      onDismiss = { showModelPicker = false },
      onAdd = { showModelPicker = false; showAddProfile = true },
    )
  }
  if (showAddProfile) {
    AddProfileDialog(
      onDismiss = { showAddProfile = false },
      onAdd = { provider, label, secret, base, model ->
        val profile = viewModel.addProfile(provider, label, secret, base, model)
        viewModel.selectProfile(profile.id)
        showAddProfile = false
      },
    )
  }
  if (showImport) {
    ImportProfilesDialog(
      onDismiss = { showImport = false },
      onImport = { text ->
        val count = viewModel.importProfiles(text)
        showImport = false
        Toast.makeText(context, "Imported $count route${if (count == 1) "" else "s"}", Toast.LENGTH_SHORT).show()
      },
    )
  }
}

@Composable
private fun Sidebar(
  tab: AppTab,
  keyCount: Int,
  selected: ApiKeyProfile?,
  onTab: (AppTab) -> Unit,
  onNewTask: () -> Unit,
) {
  Column(
    modifier = Modifier.width(238.dp).fillMaxHeight().background(Panel).padding(horizontal = 12.dp, vertical = 16.dp),
  ) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
      Surface(color = Accent, shape = RoundedCornerShape(8.dp), modifier = Modifier.size(30.dp)) {
        Box(contentAlignment = Alignment.Center) { Text("›_", color = Color(0xFF08202B), fontWeight = FontWeight.Bold, fontSize = 13.sp) }
      }
      Spacer(Modifier.width(10.dp))
      Text("codex", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
      Spacer(Modifier.weight(1f))
      Text("64", color = Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold)
    }
    Spacer(Modifier.height(22.dp))
    Surface(
      color = Color(0xFF252525),
      shape = RoundedCornerShape(8.dp),
      modifier = Modifier.fillMaxWidth().height(42.dp).clickable(onClick = onNewTask),
    ) {
      Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 13.dp)) {
        Text("+", color = Accent, fontSize = 22.sp)
        Spacer(Modifier.width(10.dp))
        Text("New task", color = Color.White, fontSize = 13.sp)
        Spacer(Modifier.weight(1f))
        Text("⌘ K", color = Muted, fontSize = 11.sp)
      }
    }
    Spacer(Modifier.height(20.dp))
    Text("WORKSPACE", color = Muted, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp))
    AppTab.entries.forEach { item ->
      SidebarItem(item, selected = item == tab, badge = if (item == AppTab.KEYS && keyCount > 0) keyCount.toString() else null) { onTab(item) }
    }
    Spacer(Modifier.weight(1f))
    Surface(color = Color(0xFF1B262B), shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth()) {
      Column(Modifier.padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Box(Modifier.size(8.dp).clip(RoundedCornerShape(4.dp)).background(if (selected != null) Good else Warning))
          Spacer(Modifier.width(8.dp))
          Text(if (selected != null) "Agent ready" else "Add an API route", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        }
        Spacer(Modifier.height(7.dp))
        Text(
          selected?.let { "${it.provider.displayName} · ${it.displayModel()}" } ?: "100+ routes supported · local models too",
          color = Muted,
          fontSize = 11.sp,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
        )
      }
    }
    Spacer(Modifier.height(10.dp))
    Text("Codex for Android 1.0", color = Color(0xFF656565), fontSize = 10.sp, modifier = Modifier.padding(horizontal = 8.dp))
  }
}

@Composable
private fun SidebarItem(item: AppTab, selected: Boolean, badge: String?, onClick: () -> Unit) {
  Row(
    modifier = Modifier.fillMaxWidth().height(40.dp).clip(RoundedCornerShape(7.dp))
      .background(if (selected) PanelSelected else Color.Transparent)
      .clickable(onClick = onClick).padding(horizontal = 12.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(
      when (item) {
        AppTab.CHAT -> "◌"
        AppTab.TERMINAL -> "\$_"
        AppTab.FILES -> "□"
        AppTab.KEYS -> "⌁"
        AppTab.SETTINGS -> "⚙"
      },
      color = if (selected) Accent else Muted,
      fontSize = 17.sp,
      modifier = Modifier.width(26.dp),
    )
    Text(item.label, color = if (selected) Color.White else Color(0xFFC0C0C0), fontSize = 13.sp)
    if (badge != null) {
      Spacer(Modifier.weight(1f))
      Text(badge, color = Accent, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
  }
}

@Composable
private fun Header(
  tab: AppTab,
  selected: ApiKeyProfile?,
  keyCount: Int,
  event: AgentEvent?,
  busy: Boolean,
  onModelClick: () -> Unit,
  onStop: () -> Unit,
) {
  Row(
    modifier = Modifier.fillMaxWidth().height(62.dp).background(Bg).padding(horizontal = 24.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(tab.title, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
    if (tab == AppTab.CHAT) {
      Spacer(Modifier.width(14.dp))
      Surface(
        color = PanelRaised,
        shape = RoundedCornerShape(7.dp),
        border = BorderStroke(1.dp, Border),
        modifier = Modifier.clickable(onClick = onModelClick),
      ) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
          Text(selected?.displayModel() ?: "Select model", color = if (selected == null) Warning else Color.White, fontSize = 12.sp)
          Spacer(Modifier.width(7.dp))
          Text("⌄", color = Muted, fontSize = 13.sp)
        }
      }
      Spacer(Modifier.width(8.dp))
      Text(if (keyCount > 0) "$keyCount route${if (keyCount == 1) "" else "s"}" else "no route", color = Muted, fontSize = 11.sp)
    }
    Spacer(Modifier.weight(1f))
    if (busy) {
      if (event?.kind == AgentEvent.Kind.THINKING) CircularProgressIndicator(color = Accent, strokeWidth = 2.dp, modifier = Modifier.size(15.dp))
      Spacer(Modifier.width(10.dp))
      Text(event?.text ?: "Working…", color = Muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
      Spacer(Modifier.width(10.dp))
      TextButton(onClick = onStop, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 0.dp)) { Text("Stop", color = Color(0xFFFF9A9A), fontSize = 12.sp) }
    } else if (event != null && event.kind != AgentEvent.Kind.DONE) {
      Text(event.text, color = Muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
  }
}

@Composable
private fun ChatWorkspace(
  viewModel: CodexViewModel,
  messages: List<ChatMessage>,
  busy: Boolean,
  event: AgentEvent?,
) {
  Column(Modifier.fillMaxSize().imePadding()) {
    val listState = rememberLazyListState()
    LaunchedEffect(messages.size) {
      if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
    }
    if (messages.isEmpty()) {
      EmptyChat(modifier = Modifier.weight(1f), onSuggestion = viewModel::send)
    } else {
      LazyColumn(
        state = listState,
        modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 22.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 16.dp, bottom = 14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
      ) {
        items(messages.filter { it.role != ChatMessage.Role.SYSTEM }, key = { it.id }) { message ->
          MessageBubble(message)
        }
      }
    }
    if (event?.kind == AgentEvent.Kind.TOOL_STARTED) {
      Row(Modifier.fillMaxWidth().padding(horizontal = 30.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("↳", color = Accent, fontSize = 16.sp)
        Spacer(Modifier.width(7.dp))
        Text(event.text, color = Muted, fontSize = 12.sp)
      }
    }
    Composer(busy = busy, onSend = viewModel::send, onStop = viewModel::stop)
  }
}

@Composable
private fun EmptyChat(modifier: Modifier = Modifier, onSuggestion: (String) -> Unit) {
  Column(
    modifier = modifier.fillMaxWidth().padding(horizontal = 28.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.Center,
  ) {
    Surface(color = Color(0xFF1B262B), shape = RoundedCornerShape(15.dp), modifier = Modifier.size(64.dp)) {
      Box(contentAlignment = Alignment.Center) { Text("›_", color = Accent, fontSize = 25.sp, fontWeight = FontWeight.Bold) }
    }
    Spacer(Modifier.height(18.dp))
    Text("What are you building?", color = Color.White, fontSize = 23.sp, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(8.dp))
    Text("Ask Codex to inspect, create, or run code in your workspace.", color = Muted, fontSize = 13.sp)
    Spacer(Modifier.height(22.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      Suggestion("Explain this project") { onSuggestion("Inspect the workspace and explain its structure and how to build it.") }
      Suggestion("Find a bug") { onSuggestion("Inspect the workspace for a likely bug and explain what you find.") }
    }
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      Suggestion("Create a file") { onSuggestion("Create a README.md that documents this workspace.") }
      Suggestion("Run tests") { onSuggestion("Detect the project type and run its most appropriate tests.") }
    }
  }
}

@Composable
private fun Suggestion(text: String, onClick: () -> Unit) {
  Surface(color = Panel, border = BorderStroke(1.dp, Border), shape = RoundedCornerShape(8.dp), modifier = Modifier.clickable(onClick = onClick)) {
    Text(text, color = Color(0xFFC9C9C9), fontSize = 12.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp))
  }
}

@Composable
private fun MessageBubble(message: ChatMessage) {
  val isUser = message.role == ChatMessage.Role.USER
  val isTool = message.role == ChatMessage.Role.TOOL
  val align = if (isUser) Alignment.End else Alignment.Start
  Column(Modifier.fillMaxWidth(), horizontalAlignment = align) {
    if (isTool) {
      Surface(color = Color(0xFF18241D), border = BorderStroke(1.dp, Color(0xFF294B32)), shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth(0.92f)) {
        Column(Modifier.padding(11.dp)) {
          Text("tool · ${message.toolName ?: "workspace"}", color = Good, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
          Spacer(Modifier.height(5.dp))
          Text(message.content, color = Color(0xFFB9D2BE), fontSize = 12.sp, fontFamily = FontFamily.Monospace, maxLines = 16, overflow = TextOverflow.Ellipsis)
        }
      }
    } else if (isUser) {
      Surface(color = Color(0xFF243E4B), shape = RoundedCornerShape(12.dp, 12.dp, 3.dp, 12.dp), modifier = Modifier.fillMaxWidth(0.88f)) {
        Text(message.content, color = Color.White, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp))
      }
    } else {
      Row(Modifier.fillMaxWidth(0.94f), verticalAlignment = Alignment.Top) {
        Surface(color = Accent, shape = RoundedCornerShape(5.dp), modifier = Modifier.size(22.dp)) {
          Box(contentAlignment = Alignment.Center) { Text("›", color = Color(0xFF092431), fontWeight = FontWeight.Bold, fontSize = 18.sp) }
        }
        Spacer(Modifier.width(10.dp))
        androidx.compose.foundation.text.selection.SelectionContainer {
          Text(message.content, color = Color(0xFFE2E2E2), fontSize = 14.sp, lineHeight = 21.sp, modifier = Modifier.padding(top = 1.dp))
        }
      }
    }
  }
}

@Composable
private fun Composer(busy: Boolean, onSend: (String) -> Unit, onStop: () -> Unit) {
  var text by rememberSaveable { mutableStateOf("") }
  Surface(color = Bg, modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp)) {
    Column {
      Surface(color = Panel, shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, if (busy) Color(0xFF375461) else Border), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 13.dp, vertical = 10.dp)) {
          BasicTextField(
            value = text,
            onValueChange = { if (it.length <= 20_000) text = it },
            textStyle = TextStyle(color = Color(0xFFE8E8E8), fontSize = 14.sp, lineHeight = 20.sp),
            minLines = 1,
            maxLines = 6,
            modifier = Modifier.fillMaxWidth().height(58.dp),
            decorationBox = { inner ->
              Box {
                if (text.isEmpty()) Text("Describe a task…", color = Color(0xFF777777), fontSize = 14.sp)
                inner()
              }
            },
          )
          Spacer(Modifier.height(6.dp))
          Row(verticalAlignment = Alignment.CenterVertically) {
            Text("＋", color = Muted, fontSize = 21.sp)
            Spacer(Modifier.width(6.dp))
            Text("Workspace tools enabled", color = Muted, fontSize = 11.sp)
            Spacer(Modifier.weight(1f))
            Surface(
              color = if (busy) Color(0xFF3B2424) else if (text.isBlank()) Color(0xFF2B2B2B) else Accent,
              shape = RoundedCornerShape(7.dp),
              modifier = Modifier.size(34.dp).clickable {
                if (busy) onStop() else if (text.isNotBlank()) { onSend(text); text = "" }
              },
            ) {
              Box(contentAlignment = Alignment.Center) { Text(if (busy) "■" else "↑", color = if (busy) Color(0xFFFFA8A8) else if (text.isBlank()) Muted else Color(0xFF08202B), fontSize = 16.sp) }
            }
          }
        }
      }
      Text("Codex can make mistakes. Review commands and file changes.", color = Color(0xFF5E5E5E), fontSize = 10.sp, modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 7.dp))
    }
  }
}

@Composable
private fun TerminalWorkspace(workspace: File) {
  val context = LocalContext.current
  val termuxAvailable = remember { TermuxBridge.isInstalled(context) }
  var message by remember { mutableStateOf<String?>(null) }
  Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp)) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
      Column(Modifier.weight(1f)) {
        Text("Workspace shell", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        Text(workspace.absolutePath, color = Muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
      }
      if (termuxAvailable) {
        SmallAction("Install tools in Termux") {
          message = if (TermuxBridge.installCommonTools(context)) "Termux is installing git, Python, Node, ripgrep, clang, and make." else "Termux rejected the command. Check RUN_COMMAND permission."
        }
        Spacer(Modifier.width(7.dp))
        SmallAction("Open Termux") { TermuxBridge.open(context) }
      }
    }
    if (message != null) Text(message.orEmpty(), color = Good, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
    Surface(color = Color(0xFF0A0A0A), shape = RoundedCornerShape(9.dp), border = BorderStroke(1.dp, Border), modifier = Modifier.fillMaxSize()) {
      TerminalPane(workspace, Modifier.fillMaxSize().padding(5.dp))
    }
  }
}

@Composable
private fun FilesWorkspace(workspace: File) {
  var tick by remember { mutableStateOf(0) }
  val files = remember(tick, workspace.absolutePath) {
    workspace.walkTopDown().maxDepth(3).filter { it != workspace && it.name !in setOf(".git", ".gradle", "build") }.take(300).toList()
  }
  Column(Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
    Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
      Column(Modifier.weight(1f)) {
        Text("Workspace files", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        Text(workspace.absolutePath, color = Muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
      }
      SmallAction("Refresh") { tick++ }
    }
    Surface(color = Panel, border = BorderStroke(1.dp, Border), shape = RoundedCornerShape(9.dp), modifier = Modifier.fillMaxSize()) {
      if (files.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Your workspace is empty.", color = Muted, fontSize = 13.sp) }
      } else {
        LazyColumn(contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp)) {
          items(files, key = { it.absolutePath }) { file ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 15.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
              Text(if (file.isDirectory) "□" else "▱", color = if (file.isDirectory) Accent else Muted, fontSize = 17.sp, modifier = Modifier.width(28.dp))
              Text(file.relativeTo(workspace).path + if (file.isDirectory) "/" else "", color = Color(0xFFD3D3D3), fontSize = 13.sp, fontFamily = if (file.isFile) FontFamily.Monospace else FontFamily.SansSerif)
              Spacer(Modifier.weight(1f))
              if (file.isFile) Text("${file.length()} B", color = Color(0xFF666666), fontSize = 10.sp)
            }
          }
        }
      }
    }
  }
}

@Composable
private fun KeysWorkspace(
  viewModel: CodexViewModel,
  keys: List<ApiKeyProfile>,
  selectedId: String?,
  onAdd: () -> Unit,
  onImport: () -> Unit,
) {
  var feedback by remember { mutableStateOf<String?>(null) }
  Column(Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
    Row(Modifier.fillMaxWidth().padding(bottom = 13.dp), verticalAlignment = Alignment.CenterVertically) {
      Column(Modifier.weight(1f)) {
        Text("API routes", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        Text("Keys are encrypted on-device. Rotation automatically skips rate-limited routes.", color = Muted, fontSize = 11.sp)
      }
      SmallAction("Import") { onImport() }
      Spacer(Modifier.width(8.dp))
      PrimaryAction("Add route") { onAdd() }
    }
    if (feedback != null) {
      Text(feedback.orEmpty(), color = if (feedback!!.startsWith("Connected")) Good else Warning, fontSize = 12.sp, modifier = Modifier.padding(bottom = 10.dp))
    }
    Surface(color = Panel, border = BorderStroke(1.dp, Border), shape = RoundedCornerShape(9.dp), modifier = Modifier.fillMaxSize()) {
      if (keys.isEmpty()) {
        Column(Modifier.fillMaxSize().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
          Text("No routes yet", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Medium)
          Spacer(Modifier.height(7.dp))
          Text("Add a provider key, a self-hosted OpenAI-compatible endpoint, or Ollama. You can import 100+ routes at once.", color = Muted, fontSize = 12.sp)
          Spacer(Modifier.height(16.dp))
          PrimaryAction("Add your first route") { onAdd() }
        }
      } else {
        LazyColumn(contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp)) {
          items(keys, key = { it.id }) { profile ->
            RouteRow(
              profile = profile,
              selected = profile.id == selectedId,
              onSelect = { viewModel.selectProfile(profile.id) },
              onToggle = { viewModel.toggleProfile(profile) },
              onDelete = { viewModel.removeProfile(profile) },
              onTest = { viewModel.testProfile(profile) { feedback = it } },
            )
          }
        }
      }
    }
  }
}

@Composable
private fun RouteRow(
  profile: ApiKeyProfile,
  selected: Boolean,
  onSelect: () -> Unit,
  onToggle: () -> Unit,
  onDelete: () -> Unit,
  onTest: () -> Unit,
) {
  Column(Modifier.fillMaxWidth()) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onSelect).padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
      RadioButton(selected = selected, onClick = onSelect)
      Column(Modifier.weight(1f).padding(start = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Text(profile.label, color = if (profile.enabled) Color.White else Muted, fontSize = 13.sp, fontWeight = FontWeight.Medium)
          Spacer(Modifier.width(8.dp))
          Surface(color = if (profile.provider.local) Color(0xFF263B2C) else Color(0xFF252F38), shape = RoundedCornerShape(4.dp)) {
            Text(if (profile.provider.local) "LOCAL" else profile.provider.displayName.uppercase(), color = if (profile.provider.local) Good else Accent, fontSize = 9.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 5.dp, vertical = 3.dp))
          }
        }
        Spacer(Modifier.height(3.dp))
        Text("${profile.displayModel()}  ·  ${profile.maskedSecret}", color = Muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (profile.isCoolingDown) Text("Cooling down for rotation", color = Warning, fontSize = 10.sp)
      }
      Column(horizontalAlignment = Alignment.End) {
        Switch(checked = profile.enabled, onCheckedChange = { onToggle() })
        Row {
          TextButton(onClick = onTest, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 5.dp, vertical = 0.dp)) { Text("Test", color = Accent, fontSize = 11.sp) }
          TextButton(onClick = onDelete, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 5.dp, vertical = 0.dp)) { Text("Delete", color = Color(0xFFFF9A9A), fontSize = 11.sp) }
        }
      }
    }
    HorizontalDivider(color = Border, thickness = 1.dp)
  }
}

@Composable
private fun SettingsWorkspace(workspace: File, keyCount: Int) {
  val context = LocalContext.current
  Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)) {
    Text("Settings", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(18.dp))
    SettingSection("Workspace") {
      SettingLine("Workspace directory", workspace.absolutePath)
      SettingLine("Tool access", "File tools and shell are enabled inside this directory")
    }
    SettingSection("Routing") {
      SettingLine("Configured routes", "$keyCount")
      SettingLine("Rotation", "Least-recently-used with exponential cooldown on failures")
      SettingLine("Local models", "Ollama, LM Studio, llama.cpp, and OpenAI-compatible URLs")
    }
    SettingSection("Privacy") {
      SettingLine("API key storage", "AES-GCM encrypted with Android Keystore")
      SettingLine("Network", "Only the provider URL you configure receives prompts")
      SettingLine("Telemetry", "None built in")
    }
    SettingSection("About") {
      SettingLine("Architecture", "64-bit arm64-v8a and x86_64")
      SettingLine("Terminal", "Embedded PTY terminal with AndroidIDE / Termux emulator")
      TextButton(onClick = {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/rudrosarkardev-web/AndroidIDE")))
      }) { Text("View source", color = Accent) }
    }
  }
}

@Composable
private fun SettingSection(title: String, content: @Composable () -> Unit) {
  Column(Modifier.fillMaxWidth().padding(bottom = 22.dp)) {
    Text(title.uppercase(), color = Accent, fontSize = 10.sp, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(7.dp))
    Surface(color = Panel, border = BorderStroke(1.dp, Border), shape = RoundedCornerShape(9.dp), modifier = Modifier.fillMaxWidth()) {
      Column(Modifier.padding(horizontal = 15.dp, vertical = 4.dp)) { content() }
    }
  }
}

@Composable
private fun SettingLine(title: String, value: String) {
  Column(Modifier.padding(vertical = 10.dp)) {
    Text(title, color = Color(0xFFE0E0E0), fontSize = 13.sp)
    Spacer(Modifier.height(3.dp))
    Text(value, color = Muted, fontSize = 11.sp)
  }
}

@Composable
private fun ModelPickerDialog(
  keys: List<ApiKeyProfile>,
  selectedId: String?,
  onSelect: (String?) -> Unit,
  onDismiss: () -> Unit,
  onAdd: () -> Unit,
) {
  Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
    Surface(color = Panel, shape = RoundedCornerShape(13.dp), border = BorderStroke(1.dp, Border), modifier = Modifier.fillMaxWidth(0.9f).fillMaxHeight(0.78f)) {
      Column(Modifier.padding(18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Column(Modifier.weight(1f)) {
            Text("Select route", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Text("The agent uses the selected route first, then rotates through healthy routes.", color = Muted, fontSize = 11.sp)
          }
          TextButton(onClick = onDismiss) { Text("Close", color = Muted) }
        }
        Spacer(Modifier.height(12.dp))
        if (keys.isEmpty()) {
          Text("No route configured. Add a provider key or local model to start.", color = Warning, fontSize = 12.sp, modifier = Modifier.padding(8.dp))
        } else {
          LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(keys, key = { it.id }) { profile ->
              Surface(
                color = if (profile.id == selectedId) PanelSelected else PanelRaised,
                border = BorderStroke(1.dp, if (profile.id == selectedId) Color(0xFF3D7185) else Border),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth().clickable { onSelect(profile.id) },
              ) {
                Row(Modifier.padding(horizontal = 10.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                  RadioButton(selected = profile.id == selectedId, onClick = { onSelect(profile.id) })
                  Column(Modifier.weight(1f).padding(start = 4.dp)) {
                    Text(profile.label, color = Color.White, fontSize = 13.sp)
                    Text("${profile.provider.displayName} · ${profile.displayModel()}", color = Muted, fontSize = 11.sp)
                  }
                  Text(if (profile.enabled) "ready" else "off", color = if (profile.enabled) Good else Muted, fontSize = 10.sp)
                }
              }
            }
          }
        }
        Spacer(Modifier.height(13.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          SmallAction("Add route") { onAdd() }
          if (keys.isNotEmpty()) SmallAction("Automatic rotation") { onSelect(null) }
        }
      }
    }
  }
}

@Composable
private fun AddProfileDialog(
  onDismiss: () -> Unit,
  onAdd: (ProviderKind, String, String, String, String) -> Unit,
) {
  var provider by remember { mutableStateOf(ProviderKind.OPENAI) }
  var label by remember { mutableStateOf("") }
  var secret by remember { mutableStateOf("") }
  var baseUrl by remember { mutableStateOf(provider.defaultBaseUrl()) }
  var model by remember { mutableStateOf(provider.defaultModel()) }
  var menuOpen by remember { mutableStateOf(false) }
  var error by remember { mutableStateOf<String?>(null) }
  LaunchedEffect(provider) {
    baseUrl = provider.defaultBaseUrl()
    model = provider.defaultModel()
  }
  Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
    Surface(color = Panel, shape = RoundedCornerShape(13.dp), border = BorderStroke(1.dp, Border), modifier = Modifier.fillMaxWidth(0.92f).verticalScroll(rememberScrollState())) {
      Column(Modifier.padding(20.dp)) {
        Text("Add API route", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(5.dp))
        Text("Add one route or use Import for 100+ keys. Secrets are stored encrypted on this device.", color = Muted, fontSize = 11.sp)
        Spacer(Modifier.height(16.dp))
        Text("Provider", color = Muted, fontSize = 11.sp)
        Spacer(Modifier.height(5.dp))
        Box {
          Surface(color = PanelRaised, border = BorderStroke(1.dp, Border), shape = RoundedCornerShape(7.dp), modifier = Modifier.fillMaxWidth().clickable { menuOpen = true }) {
            Row(Modifier.padding(horizontal = 12.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
              Text(provider.displayName, color = Color.White, fontSize = 13.sp)
              Spacer(Modifier.weight(1f))
              Text("⌄", color = Muted)
            }
          }
          DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            ProviderKind.entries.forEach { option ->
              DropdownMenuItem(text = { Text(option.displayName) }, onClick = { provider = option; menuOpen = false })
            }
          }
        }
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(label = { Text("Label") }, value = label, onValueChange = { label = it }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        if (provider.needsSecret) {
          OutlinedTextField(label = { Text("API key") }, value = secret, onValueChange = { secret = it }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
          Spacer(Modifier.height(8.dp))
        } else {
          Text("Local route — leave API key empty", color = Good, fontSize = 11.sp, modifier = Modifier.padding(vertical = 4.dp))
        }
        OutlinedTextField(label = { Text("Base URL") }, value = baseUrl, onValueChange = { baseUrl = it }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(label = { Text("Model") }, value = model, onValueChange = { model = it }, singleLine = true, modifier = Modifier.fillMaxWidth())
        if (error != null) Text(error.orEmpty(), color = Color(0xFFFF9A9A), fontSize = 11.sp, modifier = Modifier.padding(top = 8.dp))
        Spacer(Modifier.height(17.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
          TextButton(onClick = onDismiss) { Text("Cancel", color = Muted) }
          Spacer(Modifier.width(8.dp))
          PrimaryAction("Save route") {
            if (provider.needsSecret && secret.isBlank()) error = "An API key is required for ${provider.displayName}."
            else if (baseUrl.isBlank()) error = "Base URL is required."
            else onAdd(provider, label, secret, baseUrl, model)
          }
        }
      }
    }
  }
}

@Composable
private fun ImportProfilesDialog(onDismiss: () -> Unit, onImport: (String) -> Unit) {
  var text by remember { mutableStateOf("") }
  Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
    Surface(color = Panel, shape = RoundedCornerShape(13.dp), border = BorderStroke(1.dp, Border), modifier = Modifier.fillMaxWidth(0.92f)) {
      Column(Modifier.padding(20.dp)) {
        Text("Import routes", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(5.dp))
        Text("One per line: PROVIDER|label|api-key|base-url|model. JSON arrays and { keys: [...] } are also supported.", color = Muted, fontSize = 11.sp)
        Spacer(Modifier.height(13.dp))
        BasicTextField(
          value = text,
          onValueChange = { text = it },
          textStyle = TextStyle(color = Color.White, fontSize = 12.sp, fontFamily = FontFamily.Monospace),
          minLines = 9,
          maxLines = 14,
          modifier = Modifier.fillMaxWidth().height(210.dp).background(Color(0xFF0C0C0C), RoundedCornerShape(7.dp)).border(1.dp, Border, RoundedCornerShape(7.dp)).padding(10.dp),
          decorationBox = { inner ->
            Box { if (text.isEmpty()) Text("OPENAI|primary|sk-…|https://api.openai.com/v1|gpt-4o-mini", color = Color(0xFF666666), fontSize = 12.sp, fontFamily = FontFamily.Monospace); inner() }
          },
        )
        Spacer(Modifier.height(15.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
          TextButton(onClick = onDismiss) { Text("Cancel", color = Muted) }
          Spacer(Modifier.width(8.dp))
          PrimaryAction("Import") { if (text.isNotBlank()) onImport(text) }
        }
      }
    }
  }
}

@Composable
private fun PrimaryAction(label: String, onClick: () -> Unit) {
  Surface(color = Accent, shape = RoundedCornerShape(7.dp), modifier = Modifier.clickable(onClick = onClick)) {
    Text(label, color = Color(0xFF08202B), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp))
  }
}

@Composable
private fun SmallAction(label: String, onClick: () -> Unit) {
  Surface(color = PanelRaised, border = BorderStroke(1.dp, Border), shape = RoundedCornerShape(7.dp), modifier = Modifier.clickable(onClick = onClick)) {
    Text(label, color = Color(0xFFD0D0D0), fontSize = 12.sp, modifier = Modifier.padding(horizontal = 11.dp, vertical = 8.dp))
  }
}

/** Small compatibility wrapper so UI files can use `by` with plain StateFlow. */
@Composable
private fun <T> kotlinx.coroutines.flow.StateFlow<T>.collectAsStateCompat(): State<T> =
  collectAsState()
