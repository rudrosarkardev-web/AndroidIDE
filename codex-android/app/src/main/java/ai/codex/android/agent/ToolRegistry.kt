/*
 * Codex for Android — workspace tools.
 *
 * Every file operation is rooted in the app's workspace directory. Shell commands
 * run with that directory as cwd and have a bounded timeout/output buffer. This
 * keeps an agent useful on-device without silently giving it access to private
 * app data outside the selected workspace.
 *
 * Copyright (C) 2026 Codex for Android contributors.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package ai.codex.android.agent

import ai.codex.android.data.ToolSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class WorkspaceTools(private val root: File) {
  val workspaceRootPath: String get() = root.absolutePath

  init {
    root.mkdirs()
  }

  val specs: List<ToolSpec> = listOf(
    ToolSpec(
      name = "list_files",
      description = "List files and directories in the workspace.",
      parametersJson = """{"type":"object","properties":{"path":{"type":"string","description":"Relative path, default ."},"depth":{"type":"integer","description":"Maximum depth, default 2"}},"required":[]}""",
    ),
    ToolSpec(
      name = "read_file",
      description = "Read a UTF-8 text file from the workspace.",
      parametersJson = """{"type":"object","properties":{"path":{"type":"string"},"start_line":{"type":"integer"},"end_line":{"type":"integer"}},"required":["path"]}""",
    ),
    ToolSpec(
      name = "write_file",
      description = "Create or replace a UTF-8 text file in the workspace.",
      parametersJson = """{"type":"object","properties":{"path":{"type":"string"},"content":{"type":"string"}},"required":["path","content"]}""",
    ),
    ToolSpec(
      name = "edit_file",
      description = "Replace the first exact occurrence of old_text in a workspace file.",
      parametersJson = """{"type":"object","properties":{"path":{"type":"string"},"old_text":{"type":"string"},"new_text":{"type":"string"}},"required":["path","old_text","new_text"]}""",
    ),
    ToolSpec(
      name = "search_files",
      description = "Search UTF-8 text files for a query in the workspace.",
      parametersJson = """{"type":"object","properties":{"query":{"type":"string"},"path":{"type":"string"},"max_results":{"type":"integer"}},"required":["query"]}""",
    ),
    ToolSpec(
      name = "run_command",
      description = "Run a shell command in the workspace and return stdout and stderr.",
      parametersJson = """{"type":"object","properties":{"command":{"type":"string"},"timeout_seconds":{"type":"integer"}},"required":["command"]}""",
    ),
    ToolSpec(
      name = "make_directory",
      description = "Create a directory in the workspace.",
      parametersJson = """{"type":"object","properties":{"path":{"type":"string"}},"required":["path"]}""",
    ),
  )

  suspend fun execute(name: String, arguments: String): ToolResult = withContext(Dispatchers.IO) {
    val args = runCatching { JSONObject(arguments.ifBlank { "{}" }) }.getOrElse {
      return@withContext ToolResult(false, "Invalid JSON arguments for $name: ${it.message}")
    }
    return@withContext try {
      when (name) {
        "list_files" -> listFiles(args)
        "read_file" -> readFile(args)
        "write_file" -> writeFile(args)
        "edit_file" -> editFile(args)
        "search_files" -> searchFiles(args)
        "run_command" -> runCommand(args)
        "make_directory" -> makeDirectory(args)
        else -> ToolResult(false, "Unknown tool: $name")
      }
    } catch (error: Throwable) {
      ToolResult(false, "${error::class.simpleName}: ${error.message ?: "operation failed"}")
    }
  }

  private fun listFiles(args: JSONObject): ToolResult {
    val directory = resolve(args.optString("path", "."))
    if (!directory.isDirectory) return ToolResult(false, "Not a directory: ${relative(directory)}")
    val maxDepth = args.optInt("depth", 2).coerceIn(0, 5)
    val lines = mutableListOf<String>()
    fun walk(file: File, depth: Int, prefix: String) {
      if (lines.size >= MAX_LIST_ITEMS) return
      val children = file.listFiles()?.sortedWith(compareBy<File> { !it.isDirectory }.thenBy { it.name }) ?: return
      for (child in children) {
        if (child.name in IGNORED_NAMES) continue
        lines += prefix + child.name + if (child.isDirectory) "/" else ""
        if (child.isDirectory && depth < maxDepth) walk(child, depth + 1, "$prefix  ")
        if (lines.size >= MAX_LIST_ITEMS) return
      }
    }
    walk(directory, 0, "")
    val header = "Workspace: ${relative(directory)}\n"
    return ToolResult(true, header + if (lines.isEmpty()) "(empty)" else lines.joinToString("\n"))
  }

  private fun readFile(args: JSONObject): ToolResult {
    val file = resolve(args.optString("path"))
    if (!file.isFile) return ToolResult(false, "File not found: ${relative(file)}")
    if (file.length() > MAX_FILE_BYTES) return ToolResult(false, "File is larger than ${MAX_FILE_BYTES / 1024} KiB")
    val all = file.readText(Charsets.UTF_8).lineSequence().toList()
    val start = (args.optInt("start_line", 1) - 1).coerceAtLeast(0)
    val end = args.optInt("end_line", all.size).coerceIn(start, all.size)
    val selected = all.subList(start, end)
    return ToolResult(true, selected.mapIndexed { index, line -> "${start + index + 1}: $line" }.joinToString("\n"))
  }

  private fun writeFile(args: JSONObject): ToolResult {
    val file = resolve(args.optString("path"))
    val content = args.optString("content")
    check(content.toByteArray(Charsets.UTF_8).size <= MAX_FILE_BYTES) { "File is larger than ${MAX_FILE_BYTES / 1024} KiB" }
    file.parentFile?.mkdirs()
    file.writeText(content, Charsets.UTF_8)
    return ToolResult(true, "Wrote ${content.length} characters to ${relative(file)}")
  }

  private fun editFile(args: JSONObject): ToolResult {
    val file = resolve(args.optString("path"))
    if (!file.isFile) return ToolResult(false, "File not found: ${relative(file)}")
    val oldText = args.optString("old_text")
    val newText = args.optString("new_text")
    val original = file.readText(Charsets.UTF_8)
    val at = original.indexOf(oldText)
    if (at < 0) return ToolResult(false, "old_text was not found in ${relative(file)}")
    file.writeText(original.substring(0, at) + newText + original.substring(at + oldText.length), Charsets.UTF_8)
    return ToolResult(true, "Edited ${relative(file)}")
  }

  private fun searchFiles(args: JSONObject): ToolResult {
    val query = args.optString("query")
    if (query.isBlank()) return ToolResult(false, "query is required")
    val start = resolve(args.optString("path", "."))
    val limit = args.optInt("max_results", 50).coerceIn(1, 200)
    val matches = mutableListOf<String>()
    start.walkTopDown()
      .onEnter { !it.name.startsWith(".") || it == start }
      .filter { it.isFile && it.length() <= MAX_FILE_BYTES && it.name !in IGNORED_NAMES }
      .forEach { file ->
        if (matches.size >= limit) return@forEach
        val lines = runCatching { file.readLines(Charsets.UTF_8) }.getOrNull() ?: return@forEach
        lines.forEachIndexed { index, line ->
          if (matches.size < limit && line.contains(query, ignoreCase = true)) {
            matches += "${relative(file)}:${index + 1}: ${line.take(300)}"
          }
        }
      }
    return ToolResult(true, if (matches.isEmpty()) "No matches for '$query'" else matches.joinToString("\n"))
  }

  private fun runCommand(args: JSONObject): ToolResult {
    val command = args.optString("command")
    if (command.isBlank()) return ToolResult(false, "command is required")
    val seconds = args.optInt("timeout_seconds", 60).coerceIn(1, 300)
    val process = ProcessBuilder("/system/bin/sh", "-c", command)
      .directory(root)
      .redirectErrorStream(true)
      .apply {
        environment()["HOME"] = root.absolutePath
        environment()["PWD"] = root.absolutePath
      }
      .start()
    val outputRef = AtomicReference("")
    val outputReader = thread(name = "codex-command-output", start = true) {
      val collected = StringBuilder()
      process.inputStream.bufferedReader().useLines { lines ->
        lines.forEach { line ->
          if (collected.length < MAX_COMMAND_OUTPUT) {
            if (collected.isNotEmpty()) collected.append('\n')
            collected.append(line.take(MAX_COMMAND_OUTPUT - collected.length))
          }
        }
      }
      outputRef.set(collected.toString())
    }
    val finished = process.waitFor(seconds.toLong(), TimeUnit.SECONDS)
    if (!finished) {
      process.destroyForcibly()
      outputReader.join(1_000L)
      return ToolResult(false, (outputRef.get() + "\nCommand timed out after ${seconds}s").trim())
    }
    outputReader.join(1_000L)
    val suffix = "\n[exit ${process.exitValue()}]"
    return ToolResult(process.exitValue() == 0, (outputRef.get() + suffix).trim())
  }

  private fun makeDirectory(args: JSONObject): ToolResult {
    val directory = resolve(args.optString("path"))
    if (!directory.mkdirs() && !directory.isDirectory) return ToolResult(false, "Could not create ${relative(directory)}")
    return ToolResult(true, "Created ${relative(directory)}")
  }

  private fun resolve(path: String): File {
    if (path.isBlank()) throw IOException("path is required")
    val candidate = File(root, path).canonicalFile
    val canonicalRoot = root.canonicalFile
    if (candidate != canonicalRoot && !candidate.path.startsWith(canonicalRoot.path + File.separator)) {
      throw SecurityException("Path escapes the workspace")
    }
    return candidate
  }

  private fun relative(file: File): String = runCatching {
    file.canonicalFile.relativeTo(root.canonicalFile).path.ifBlank { "." }
  }.getOrDefault(file.name)

  companion object {
    private const val MAX_FILE_BYTES = 2 * 1024 * 1024
    private const val MAX_COMMAND_OUTPUT = 40_000
    private const val MAX_LIST_ITEMS = 1_000
    private val IGNORED_NAMES = setOf(".git", ".gradle", "node_modules", "build", ".idea")
  }
}

data class ToolResult(val success: Boolean, val output: String)
