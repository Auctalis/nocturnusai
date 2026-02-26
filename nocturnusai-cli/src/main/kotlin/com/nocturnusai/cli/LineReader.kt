// Copyright (c) 2026 Auctalis LLC. All rights reserved.
//
// Licensed under the Business Source License 1.1 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     https://github.com/auctalis/nocturnusai/blob/main/LICENSE
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
//
// For commercial licensing, please contact: licensing@nocturnus.ai

package com.nocturnusai.cli

import java.io.File
import java.io.InputStream

/**
 * A pure-Kotlin, zero-dependency terminal line editor with:
 *  - Persistent history (saved to ~/.config/nocturnusai/history)
 *  - Tab completion (commands + context-sensitive hints)
 *  - Arrow key navigation (up/down history, left/right cursor)
 *  - Home/End key support
 *  - Ctrl+A (home), Ctrl+E (end), Ctrl+U (clear line), Ctrl+W (delete word),
 *    Ctrl+K (kill to end), Ctrl+C (interrupt), Ctrl+D (EOF)
 *  - Colored prompt
 *
 * Uses `stty` to put the terminal into raw mode — this is available on macOS
 * and every Linux distribution. On GraalVM native-image this works perfectly
 * because stty is a subprocess, not JVM reflection. Falls back to plain
 * readLine() when stdin is not a TTY (pipes, -e flag, CI).
 */
class LineReader(
    private val completionProvider: CompletionProvider,
    private val historyFile: File = defaultHistoryFile(),
) {
    // ── History ───────────────────────────────────────────────────────────────

    private val history: MutableList<String> = mutableListOf()
    private var historyIndex = -1          // -1 = not browsing history
    private var savedLine = ""             // the line typed before browsing history

    init {
        loadHistory()
    }

    private fun loadHistory() {
        if (historyFile.exists()) {
            historyFile.readLines()
                .filter { it.isNotBlank() }
                .takeLast(MAX_HISTORY)
                .forEach { history.add(it) }
        }
    }

    private fun saveToHistory(line: String) {
        if (line.isBlank()) return
        // Deduplicate: remove previous identical entry so newest is at end
        history.remove(line)
        history.add(line)
        if (history.size > MAX_HISTORY) history.removeAt(0)
        appendToHistoryFile(line)
    }

    private fun appendToHistoryFile(line: String) {
        try {
            historyFile.parentFile?.mkdirs()
            historyFile.appendText("$line\n")
        } catch (_: Exception) {
            // Non-fatal — history just won't persist
        }
    }

    // ── Terminal raw mode (stty) ──────────────────────────────────────────────

    private val isTty: Boolean by lazy {
        try {
            // Works on macOS and Linux; fails on Windows or piped stdin
            val p = ProcessBuilder("stty", "-a")
                .redirectInput(ProcessBuilder.Redirect.INHERIT)
                .redirectOutput(ProcessBuilder.Redirect.PIPE)
                .redirectError(ProcessBuilder.Redirect.PIPE)
                .start()
            p.waitFor() == 0
        } catch (_: Exception) {
            false
        }
    }

    private fun setRawMode() {
        try {
            ProcessBuilder("stty", "raw", "-echo")
                .redirectInput(ProcessBuilder.Redirect.INHERIT)
                .redirectOutput(ProcessBuilder.Redirect.INHERIT)
                .redirectError(ProcessBuilder.Redirect.PIPE)
                .start().waitFor()
        } catch (_: Exception) {}
    }

    private fun restoreMode() {
        try {
            ProcessBuilder("stty", "sane")
                .redirectInput(ProcessBuilder.Redirect.INHERIT)
                .redirectOutput(ProcessBuilder.Redirect.INHERIT)
                .redirectError(ProcessBuilder.Redirect.PIPE)
                .start().waitFor()
        } catch (_: Exception) {}
    }

    // ── Terminal dimensions ───────────────────────────────────────────────────

    private fun terminalWidth(): Int {
        return try {
            val p = ProcessBuilder("stty", "size")
                .redirectInput(ProcessBuilder.Redirect.INHERIT)
                .redirectOutput(ProcessBuilder.Redirect.PIPE)
                .redirectError(ProcessBuilder.Redirect.PIPE)
                .start()
            val out = p.inputStream.bufferedReader().readText().trim()
            p.waitFor()
            // format: "rows cols"
            out.split(" ").getOrNull(1)?.toIntOrNull() ?: 80
        } catch (_: Exception) {
            80
        }
    }

    // ── Core readline ─────────────────────────────────────────────────────────

    /**
     * Read a line with the given prompt string. Returns null on EOF (Ctrl+D on
     * empty line). Handles raw-mode terminal on TTY, falls back to readLine()
     * when stdin is not a TTY.
     *
     * @param prompt The prompt to display — may contain ANSI codes.
     */
    fun readLine(prompt: String): String? {
        if (!isTty) {
            // Piped/scripted mode — print prompt and use buffered reader
            print(prompt)
            System.out.flush()
            return readlnOrNull()
        }

        print(prompt)
        System.out.flush()

        val buf = StringBuilder()
        var cursor = 0
        historyIndex = -1
        savedLine = ""

        setRawMode()
        try {
            val stdin: InputStream = System.`in`
            var tabPressCount = 0

            while (true) {
                val b = stdin.read()
                if (b == -1) {
                    // EOF
                    restoreMode()
                    println()
                    return if (buf.isEmpty()) null else buf.toString()
                }

                val ch = b.toChar()

                // Track consecutive tab presses for double-tab listing
                val isTab = (b == TAB)
                if (!isTab) {
                    tabPressCount = 0
                } else {
                    tabPressCount++
                }

                when {
                    // ── Control characters ──
                    b == CTRL_C -> {
                        restoreMode()
                        println("^C")
                        return ""
                    }
                    b == CTRL_D -> {
                        if (buf.isEmpty()) {
                            restoreMode()
                            println()
                            return null
                        }
                        // Ctrl+D with content = delete char at cursor
                        if (cursor < buf.length) {
                            buf.deleteCharAt(cursor)
                            redrawLine(prompt, buf, cursor)
                        }
                    }
                    b == CTRL_U -> {
                        buf.clear()
                        cursor = 0
                        redrawLine(prompt, buf, cursor)
                    }
                    b == CTRL_K -> {
                        buf.delete(cursor, buf.length)
                        redrawLine(prompt, buf, cursor)
                    }
                    b == CTRL_W -> {
                        // Delete word before cursor
                        if (cursor > 0) {
                            var i = cursor - 1
                            while (i > 0 && buf[i - 1] == ' ') i--
                            while (i > 0 && buf[i - 1] != ' ') i--
                            buf.delete(i, cursor)
                            cursor = i
                            redrawLine(prompt, buf, cursor)
                        }
                    }
                    b == CTRL_A -> {
                        cursor = 0
                        moveCursorToPosition(prompt, buf, cursor)
                    }
                    b == CTRL_E -> {
                        cursor = buf.length
                        moveCursorToPosition(prompt, buf, cursor)
                    }
                    b == CTRL_L -> {
                        // Clear screen
                        print("\u001B[2J\u001B[H")
                        redrawLine(prompt, buf, cursor)
                    }

                    // ── Tab completion ──
                    isTab -> {
                        val completions = completionProvider.complete(buf.toString(), cursor)
                        when {
                            completions.isEmpty() -> {
                                // Nothing — ring bell
                                print("\u0007")
                                System.out.flush()
                            }
                            completions.size == 1 -> {
                                val completion = completions[0]
                                // Replace the current word with the completion
                                val wordStart = findWordStart(buf.toString(), cursor)
                                buf.delete(wordStart, cursor)
                                buf.insert(wordStart, completion)
                                if (!completion.endsWith(" ")) buf.insert(wordStart + completion.length, " ")
                                cursor = wordStart + completion.length + 1
                                redrawLine(prompt, buf, cursor)
                                tabPressCount = 0
                            }
                            tabPressCount >= 2 || completions.size <= 8 -> {
                                // Double-tab or few options: show all
                                restoreMode()
                                println()
                                val colWidth = (completions.maxOfOrNull { it.length } ?: 0) + 2
                                val termW = terminalWidth()
                                val cols = maxOf(1, termW / colWidth)
                                completions.forEachIndexed { idx, c ->
                                    print("  ${CYAN}$c${RESET}".padEnd(colWidth + 10))
                                    if ((idx + 1) % cols == 0) println()
                                }
                                if (completions.size % cols != 0) println()
                                setRawMode()
                                print(prompt)
                                print(buf)
                                moveCursorToPosition(prompt, buf, cursor)
                                System.out.flush()
                            }
                            else -> {
                                // Single tab, multiple options: complete common prefix
                                val prefix = commonPrefix(completions)
                                val wordStart = findWordStart(buf.toString(), cursor)
                                val currentWord = buf.substring(wordStart, cursor)
                                if (prefix.length > currentWord.length) {
                                    buf.delete(wordStart, cursor)
                                    buf.insert(wordStart, prefix)
                                    cursor = wordStart + prefix.length
                                    redrawLine(prompt, buf, cursor)
                                } else {
                                    // Already at common prefix — ring bell
                                    print("\u0007")
                                    System.out.flush()
                                }
                            }
                        }
                    }

                    // ── Enter / Return ──
                    b == ENTER || b == '\r'.code -> {
                        restoreMode()
                        println()
                        val line = buf.toString()
                        if (line.isNotBlank()) {
                            saveToHistory(line)
                        }
                        return line
                    }

                    // ── Backspace / Delete ──
                    b == BACKSPACE || b == DEL -> {
                        if (cursor > 0) {
                            buf.deleteCharAt(cursor - 1)
                            cursor--
                            redrawLine(prompt, buf, cursor)
                        }
                    }

                    // ── Escape sequences (arrows, Home, End, Delete key) ──
                    b == ESC -> {
                        val next = stdin.read()
                        if (next == '['.code) {
                            when (val arrow = stdin.read().toChar()) {
                                'A' -> {
                                    // Up arrow — previous history
                                    if (history.isNotEmpty()) {
                                        if (historyIndex == -1) {
                                            savedLine = buf.toString()
                                            historyIndex = history.size - 1
                                        } else if (historyIndex > 0) {
                                            historyIndex--
                                        }
                                        buf.clear()
                                        buf.append(history[historyIndex])
                                        cursor = buf.length
                                        redrawLine(prompt, buf, cursor)
                                    }
                                }
                                'B' -> {
                                    // Down arrow — next history
                                    if (historyIndex != -1) {
                                        if (historyIndex < history.size - 1) {
                                            historyIndex++
                                            buf.clear()
                                            buf.append(history[historyIndex])
                                        } else {
                                            historyIndex = -1
                                            buf.clear()
                                            buf.append(savedLine)
                                        }
                                        cursor = buf.length
                                        redrawLine(prompt, buf, cursor)
                                    }
                                }
                                'C' -> {
                                    // Right arrow
                                    if (cursor < buf.length) {
                                        cursor++
                                        moveCursorToPosition(prompt, buf, cursor)
                                    }
                                }
                                'D' -> {
                                    // Left arrow
                                    if (cursor > 0) {
                                        cursor--
                                        moveCursorToPosition(prompt, buf, cursor)
                                    }
                                }
                                'H' -> {
                                    // Home (some terminals send ESC[H)
                                    cursor = 0
                                    moveCursorToPosition(prompt, buf, cursor)
                                }
                                'F' -> {
                                    // End (some terminals send ESC[F)
                                    cursor = buf.length
                                    moveCursorToPosition(prompt, buf, cursor)
                                }
                                '1', '2', '3', '4', '5', '6', '7', '8' -> {
                                    // Extended escape: ESC [ n ~
                                    val tilde = stdin.read()
                                    if (tilde == '~'.code) {
                                        when (arrow) {
                                            '1', '7' -> { cursor = 0; moveCursorToPosition(prompt, buf, cursor) }
                                            '4', '8' -> { cursor = buf.length; moveCursorToPosition(prompt, buf, cursor) }
                                            '3' -> {
                                                // Delete key
                                                if (cursor < buf.length) {
                                                    buf.deleteCharAt(cursor)
                                                    redrawLine(prompt, buf, cursor)
                                                }
                                            }
                                            else -> {}
                                        }
                                    }
                                }
                                else -> {}
                            }
                        } else if (next == 'O'.code) {
                            // SS3 sequences (xterm Home/End)
                            when (stdin.read().toChar()) {
                                'H' -> { cursor = 0; moveCursorToPosition(prompt, buf, cursor) }
                                'F' -> { cursor = buf.length; moveCursorToPosition(prompt, buf, cursor) }
                                else -> {}
                            }
                        }
                        // else: lone ESC — ignore
                    }

                    // ── Printable characters ──
                    b >= 0x20 && b != 0x7F -> {
                        buf.insert(cursor, ch)
                        cursor++
                        if (cursor == buf.length) {
                            // Appending at end — just print char
                            print(ch)
                            System.out.flush()
                        } else {
                            // Inserting in middle — redraw
                            redrawLine(prompt, buf, cursor)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            restoreMode()
            return null
        }
    }

    // ── Rendering helpers ─────────────────────────────────────────────────────

    /** Visible length of a string (strips ANSI escape codes). */
    private fun visLen(s: String): Int {
        return s.replace(Regex("\u001B\\[[0-9;]*m"), "").length
    }

    /**
     * Redraw the current line in-place using ANSI codes.
     * Moves cursor to start of line, clears to end, writes prompt+buffer,
     * then positions cursor at [cursorPos].
     */
    private fun redrawLine(prompt: String, buf: StringBuilder, cursorPos: Int) {
        // Move to beginning of line, clear to EOL, re-print prompt+content
        print("\r\u001B[K")
        print(prompt)
        print(buf)
        // Move cursor back to correct position
        val promptLen = visLen(prompt)
        val targetCol = promptLen + cursorPos + 1   // 1-based column
        print("\r\u001B[${targetCol}C")
        System.out.flush()
    }

    /**
     * Move only the cursor without redrawing content.
     */
    private fun moveCursorToPosition(prompt: String, @Suppress("UNUSED_PARAMETER") buf: StringBuilder, cursorPos: Int) {
        val promptLen = visLen(prompt)
        val targetCol = promptLen + cursorPos + 1
        print("\r\u001B[${targetCol}C")
        System.out.flush()
    }

    // ── Completion helpers ────────────────────────────────────────────────────

    /** Find the start index of the current word at [cursor] in [line]. */
    private fun findWordStart(line: String, cursor: Int): Int {
        var i = cursor - 1
        while (i > 0 && line[i - 1] != ' ') i--
        return i
    }

    /** Find the longest common prefix of a list of strings. */
    private fun commonPrefix(strs: List<String>): String {
        if (strs.isEmpty()) return ""
        var prefix = strs[0]
        for (s in strs.drop(1)) {
            while (!s.startsWith(prefix)) {
                prefix = prefix.dropLast(1)
                if (prefix.isEmpty()) return ""
            }
        }
        return prefix
    }

    companion object {
        private const val MAX_HISTORY = 1000
        private const val TAB       = 0x09
        private const val ENTER     = 0x0A
        private const val ESC       = 0x1B
        private const val BACKSPACE = 0x7F
        private const val DEL       = 0x08
        private const val CTRL_A    = 0x01
        private const val CTRL_C    = 0x03
        private const val CTRL_D    = 0x04
        private const val CTRL_E    = 0x05
        private const val CTRL_K    = 0x0B
        private const val CTRL_L    = 0x0C
        private const val CTRL_U    = 0x15
        private const val CTRL_W    = 0x17

        // ANSI colors (minimal set for completion display)
        private const val CYAN  = "\u001B[36m"
        private const val RESET = "\u001B[0m"

        fun defaultHistoryFile(): File =
            File(System.getProperty("user.home"), ".config/nocturnusai/history")
    }
}

// ── Completion ────────────────────────────────────────────────────────────────

/**
 * Provides tab-completion candidates for the current line and cursor position.
 */
interface CompletionProvider {
    fun complete(line: String, cursor: Int): List<String>
}

/**
 * The NocturnusAI-specific completion provider.
 *
 * Completion rules:
 *  - Empty / first word → complete command name
 *  - After `use ` → complete database names (fetched from server)
 *  - After `tell ` / `ask ` / `teach ` / `forget ` → show predicate syntax hints
 *  - After `keys ` → complete subcommand
 *  - After `inspect ` / `ls ` / `export ` → no meaningful completion (hint only)
 */
class NaiCompletionProvider(
    private val databasesFetcher: () -> List<String>,
) : CompletionProvider {

    companion object {
        private val COMMANDS = listOf(
            "ask", "tell", "teach", "forget",
            "inspect", "context", "compress", "cleanup",
            "ingest", "status", "setup", "use",
            "dbs", "health", "help", "export", "import",
            "dsl", "login", "whoami", "keys",
            "clear", "history",
            "exit", "quit",
        )
        private val SHORTCUTS = listOf("?", "+", "++", "-", "ls", "ctx")

        private val PREDICATE_HINTS = listOf(
            "predicate(arg1, arg2)",
            "predicate(?var)",
            "likes(alice, bob)",
            "mortal(?who)",
            "parent(?x, ?y)",
        )
        private val RULE_HINTS = listOf(
            "head(?x) :- body(?x)",
            "mortal(?x) :- human(?x)",
            "grandparent(?x, ?z) :- parent(?x, ?y), parent(?y, ?z)",
        )
        private val KEYS_SUBCOMMANDS = listOf("list", "create", "revoke")
        private val KEY_ROLES = listOf("admin", "writer", "reader")
        private val CLEANUP_HINTS = listOf("0.05", "0.1", "0.2", "0.5")
        private val CONTEXT_HINTS = listOf("10", "25", "50", "100")
        private val IMPORT_EXPORT_HINTS = listOf("knowledge.ab", "backup.ab")
    }

    override fun complete(line: String, cursor: Int): List<String> {
        val trimmed = line.trimStart()
        val beforeCursor = line.substring(0, cursor)

        // ── No input yet → all commands ──
        if (trimmed.isEmpty()) return COMMANDS + SHORTCUTS

        val spaceIdx = beforeCursor.indexOf(' ')

        // ── Still typing the first word → complete command ──
        if (spaceIdx == -1) {
            val prefix = beforeCursor.trimStart().lowercase()
            return (COMMANDS + SHORTCUTS).filter { it.startsWith(prefix) }
        }

        // ── First word is done — context-sensitive second-word completion ──
        val cmd = beforeCursor.substring(0, spaceIdx).trim().lowercase()
        val afterCmd = beforeCursor.substring(spaceIdx + 1)

        return when (cmd) {
            "use" -> {
                val dbPrefix = afterCmd.trimStart()
                val dbs = try { databasesFetcher() } catch (_: Exception) { emptyList() }
                dbs.filter { it.startsWith(dbPrefix) }
            }
            "tell", "+" -> {
                val prefix = afterCmd.trimStart()
                if (prefix.isEmpty()) PREDICATE_HINTS else emptyList()
            }
            "teach", "++" -> {
                val prefix = afterCmd.trimStart()
                if (prefix.isEmpty()) RULE_HINTS else emptyList()
            }
            "ask", "?" -> {
                val prefix = afterCmd.trimStart()
                if (prefix.isEmpty()) PREDICATE_HINTS else emptyList()
            }
            "forget", "-" -> {
                val prefix = afterCmd.trimStart()
                if (prefix.isEmpty()) PREDICATE_HINTS else emptyList()
            }
            "ingest" -> {
                val partial = afterCmd.trimStart()
                when {
                    partial.isEmpty()              -> PREDICATE_HINTS + listOf("-f ")
                    partial == "-f" || partial == "-f " -> listOf("-f ")
                    partial.startsWith("-f ")      -> completeFilePath(partial.removePrefix("-f ").trimStart(), listOf(".txt", ".md"))
                    else                           -> emptyList()
                }
            }
            "keys" -> {
                val sub = afterCmd.trimStart()
                KEYS_SUBCOMMANDS.filter { it.startsWith(sub) }
            }
            "cleanup" -> CLEANUP_HINTS.filter { it.startsWith(afterCmd.trimStart()) }
            "context", "ctx" -> CONTEXT_HINTS.filter { it.startsWith(afterCmd.trimStart()) }
            "import", "load" -> {
                // File path completion from current directory
                val partial = afterCmd.trimStart()
                completeFilePath(partial, listOf(".ab", ".txt", ".nai"))
            }
            "export", "dump" -> {
                val partial = afterCmd.trimStart()
                completeFilePath(partial, listOf(".ab", ".txt", ".nai"))
            }
            "dsl", "exec" -> emptyList()
            else -> emptyList()
        }
    }

    private fun completeFilePath(partial: String, extensions: List<String>): List<String> {
        return try {
            val dir = if (partial.contains('/')) {
                File(partial.substringBeforeLast('/'))
            } else {
                File(".")
            }
            val filePrefix = if (partial.contains('/')) partial.substringAfterLast('/') else partial
            dir.listFiles()
                ?.filter { it.name.startsWith(filePrefix) }
                ?.filter { it.isDirectory || extensions.any { ext -> it.name.endsWith(ext) } }
                ?.map { if (it.isDirectory) it.name + "/" else it.name }
                ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }
}
