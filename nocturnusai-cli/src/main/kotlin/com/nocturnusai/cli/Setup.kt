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
import java.net.HttpURLConnection
import java.net.URI
import java.security.SecureRandom

/**
 * Standalone server setup wizard. Runs without a server connection.
 *
 * Handles: container runtime detection, LLM/auth configuration,
 * compose generation, server start, health check, and success banner.
 *
 * Called via:  nocturnusai setup [--dir DIR] [--port PORT] [--ollama] [--key KEY]
 */
class Setup(
    private val dir: String = "./nocturnusai",
    private val port: Int = 9300,
    private val ollamaFlag: Boolean = false,
    private val hostOllamaFlag: Boolean = false,
    private val llmKeys: List<String> = emptyList(),
    private val nonInteractive: Boolean = false,
) {
    private val interactive = !nonInteractive && System.console() != null
    private var useOllama = ollamaFlag
    private var useHostOllama = hostOllamaFlag  // true = connect to existing host Ollama (no Docker container)
    private var composeCmd = ""
    private var containerCmd = ""
    private var llmConfigured = false
    private var llmProviderLabel = "none"
    private var authConfigured = false
    private var needBuild = false
    private lateinit var installDir: File

    fun run(): Int {
        println()
        println("$CYAN${BOLD}NocturnusAI Setup$RESET")
        println("${DIM}Logic server for Agentic AI$RESET")
        println()

        // 1. Detect container runtime
        if (!detectContainerRuntime()) {
            printContainerHelp()
            return 1
        }
        println("${GREEN}Found:$RESET $composeCmd ($containerCmd)")

        // 2. Prepare install directory
        installDir = File(dir).absoluteFile
        installDir.mkdirs()
        println("${GREEN}Directory:$RESET ${installDir.path}")

        // 3. Check for published image or source
        checkImage()

        // 4. Set up .env
        val envFile = setupEnvFile()
        setEnvKey(envFile, "PORT", port.toString())

        // 5. Configure LLM
        configureLlm(envFile)

        // 6. Configure auth
        configureAuth(envFile)

        // 7. Detect LLM/auth status for banner
        detectStatus(envFile)

        // 8. Generate compose file (fast path only — build path uses repo compose)
        if (!needBuild) generateCompose()

        // 9. Build if needed
        if (needBuild) {
            println()
            println("${BOLD}Building NocturnusAI from source...$RESET")
            println("${DIM}(first build takes 2-3 minutes — subsequent starts are instant)$RESET")
            if (shVisible("$composeCmd build nocturnusai", installDir) != 0) {
                println("${RED}Build failed.$RESET Check the output above.")
                return 1
            }
        }

        // 10. Handle existing containers, then start server
        println()
        if (!resolveExistingContainers()) return 0

        println("${BOLD}Starting NocturnusAI...$RESET")
        val upCmd = buildString {
            append(composeCmd)
            if (needBuild && useOllama) append(" --profile ollama")
            append(" up -d")
        }
        shVisible(upCmd, installDir)

        // 11. Wait for health
        println()
        val healthy = waitForHealth()

        // 12. Pull Ollama model if needed
        if (useOllama && healthy) pullOllamaModel()

        // 13. Success banner
        printSuccessBanner()
        return 0
    }

    // ── Shell helpers ──────────────────────────────────────────────────────────

    private data class ShellResult(val exitCode: Int, val output: String) {
        val success get() = exitCode == 0
    }

    /** Run a command quietly, capturing output. */
    private fun sh(command: String, workDir: File? = null): ShellResult {
        val pb = ProcessBuilder("bash", "-c", command)
        if (workDir != null) pb.directory(workDir)
        pb.redirectErrorStream(true)
        val process = pb.start()
        val output = process.inputStream.bufferedReader().readText().trim()
        val exitCode = process.waitFor()
        return ShellResult(exitCode, output)
    }

    /** Run a command with output visible to the user. */
    private fun shVisible(command: String, workDir: File? = null): Int {
        val pb = ProcessBuilder("bash", "-c", command)
        if (workDir != null) pb.directory(workDir)
        pb.inheritIO()
        return pb.start().waitFor()
    }

    // ── Container detection ────────────────────────────────────────────────────

    private fun detectContainerRuntime(): Boolean {
        // Docker
        if (sh("command -v docker >/dev/null 2>&1 && docker info >/dev/null 2>&1").success) {
            if (sh("docker compose version >/dev/null 2>&1").success) {
                composeCmd = "docker compose"; containerCmd = "docker"; return true
            }
            if (sh("command -v docker-compose >/dev/null 2>&1").success) {
                composeCmd = "docker-compose"; containerCmd = "docker"; return true
            }
        }
        // Podman
        if (sh("command -v podman >/dev/null 2>&1 && podman info >/dev/null 2>&1").success) {
            if (sh("command -v podman-compose >/dev/null 2>&1").success) {
                composeCmd = "podman-compose"; containerCmd = "podman"; return true
            }
        }
        return false
    }

    private fun printContainerHelp() {
        println("${RED}${BOLD}A container runtime with compose is required.$RESET")
        println()
        println("Install Docker:")
        println("  macOS:   brew install --cask docker")
        println("  Ubuntu:  curl -fsSL https://get.docker.com | sh")
        println("  Windows: https://docs.docker.com/desktop/install/windows-install/")
        println()
        println("Or install Podman:")
        println("  macOS:   brew install podman podman-compose")
        println("  Ubuntu:  sudo apt install podman podman-compose")
        println("  Fedora:  sudo dnf install podman podman-compose")
    }

    // ── Image check ────────────────────────────────────────────────────────────

    private fun checkImage() {
        print("${DIM}Checking for published container image...$RESET")
        System.out.flush()

        if (sh("$containerCmd pull $IMAGE 2>/dev/null").success) {
            println(" ${GREEN}found$RESET")
            // Grab .env.example for reference
            sh("curl -fsSL $REPO_RAW/.env.example -o .env.example 2>/dev/null", installDir)
            return
        }

        println(" ${YELLOW}not found$RESET")

        // Check if we're inside the repo (has Dockerfile)
        if (File(installDir, "Dockerfile").exists()) {
            println("${DIM}Dockerfile found — will build from source$RESET")
            needBuild = true
            return
        }

        // Need to clone or download source
        println("${YELLOW}No published image.$RESET Building from source...")
        if (sh("command -v git >/dev/null 2>&1").success) {
            sh("git clone --depth 1 $REPO_URL .", installDir)
        } else {
            sh("curl -fsSL https://github.com/Auctalis/nocturnusai/archive/refs/heads/main.tar.gz | tar -xz --strip-components=1", installDir)
        }
        needBuild = true
    }

    // ── .env management ────────────────────────────────────────────────────────

    private fun setupEnvFile(): File {
        val envFile = File(installDir, ".env")
        if (!envFile.exists()) {
            val example = File(installDir, ".env.example")
            if (example.exists()) example.copyTo(envFile) else envFile.createNewFile()
        }
        return envFile
    }

    private fun setEnvKey(file: File, key: String, value: String) {
        val lines = if (file.exists() && file.length() > 0) {
            file.readLines().filter { !it.trimStart().startsWith("$key=") }
        } else {
            emptyList()
        }
        val content = buildString {
            if (lines.isNotEmpty()) {
                append(lines.joinToString("\n"))
                append("\n")
            }
            append("$key=$value\n")
        }
        file.writeText(content)
    }

    private fun envHasKey(file: File, key: String): Boolean =
        file.exists() && file.readLines().any { it.trimStart().startsWith("$key=") }

    // ── Interactive helpers ─────────────────────────────────────────────────────

    private fun prompt(message: String, default: String? = null): String? {
        if (!interactive) return default
        val suffix = if (default != null) " [$default]" else ""
        print("$message$suffix: ")
        System.out.flush()
        val input = readlnOrNull()?.trim()
        return if (input.isNullOrBlank()) default else input
    }

    private fun menu(header: String, vararg options: String): Int {
        if (!interactive) return 0 // default to first option
        println()
        println("$BOLD$header$RESET")
        println()
        for ((i, opt) in options.withIndex()) {
            println("  ${i + 1}) $opt")
        }
        println()
        val raw = prompt("Choice", "1") ?: "1"
        val idx = (raw.toIntOrNull() ?: 1) - 1
        return idx.coerceIn(0, options.size - 1)
    }

    // ── LLM configuration ──────────────────────────────────────────────────────

    private fun configureLlm(envFile: File) {
        // Flag: --key (can be passed multiple times via repeated --key args)
        if (llmKeys.isNotEmpty()) {
            for (k in llmKeys) {
                when {
                    k.startsWith("sk-ant-") -> {
                        println("${GREEN}Detected:$RESET Anthropic Claude")
                        setEnvKey(envFile, "ANTHROPIC_API_KEY", k)
                    }
                    k.startsWith("sk-") -> {
                        println("${GREEN}Detected:$RESET OpenAI")
                        setEnvKey(envFile, "OPENAI_API_KEY", k)
                    }
                    k.startsWith("AIza") -> {
                        println("${GREEN}Detected:$RESET Google Gemini")
                        setEnvKey(envFile, "GOOGLE_API_KEY", k)
                    }
                    else -> {
                        println("${YELLOW}Unknown key format — setting as LLM_API_KEY$RESET")
                        setEnvKey(envFile, "LLM_API_KEY", k)
                    }
                }
            }
            useOllama = false
            return
        }

        // Flag: --ollama (Docker Ollama)
        if (useOllama) {
            setEnvKey(envFile, "LLM_PROVIDER", "ollama")
            setEnvKey(envFile, "LLM_MODEL", "llama3.2")
            println("${GREEN}Using:$RESET Ollama in Docker (local LLM — no API key needed)")
            return
        }

        // Flag: --host-ollama (existing host Ollama)
        if (useHostOllama) {
            setEnvKey(envFile, "LLM_PROVIDER", "ollama")
            setEnvKey(envFile, "LLM_MODEL", "llama3.2")
            setEnvKey(envFile, "LLM_BASE_URL", "http://host.docker.internal:11434")
            println("${GREEN}Using:$RESET existing Ollama on host (host.docker.internal:11434)")
            return
        }

        // Non-interactive: server only
        if (!interactive) {
            println("${GREEN}Server-only mode$RESET (no LLM — core API works without one)")
            println("${DIM}  Use --ollama for local LLM, or --key <api-key> for cloud$RESET")
            return
        }

        // Interactive wizard — configure each provider, allowing multiple
        println()
        println("${BOLD}LLM Configuration$RESET ${DIM}(optional — core API works without one)$RESET")
        println("${DIM}You can configure multiple providers. The server uses whichever key is available.$RESET")
        println()

        // Anthropic
        val antKey = prompt("Anthropic API key ${DIM}(sk-ant-... or Enter to skip)$RESET")
        if (!antKey.isNullOrBlank()) {
            setEnvKey(envFile, "ANTHROPIC_API_KEY", antKey)
            println("  ${GREEN}Anthropic Claude configured$RESET")
        }

        // OpenAI
        val oaiKey = prompt("OpenAI API key ${DIM}(sk-... or Enter to skip)$RESET")
        if (!oaiKey.isNullOrBlank()) {
            setEnvKey(envFile, "OPENAI_API_KEY", oaiKey)
            println("  ${GREEN}OpenAI GPT configured$RESET")
        }

        // Google
        val gglKey = prompt("Google API key ${DIM}(AIza... or Enter to skip)$RESET")
        if (!gglKey.isNullOrBlank()) {
            setEnvKey(envFile, "GOOGLE_API_KEY", gglKey)
            println("  ${GREEN}Google Gemini configured$RESET")
        }

        // Ollama — only if no cloud keys were entered
        val hasCloudKey = !antKey.isNullOrBlank() || !oaiKey.isNullOrBlank() || !gglKey.isNullOrBlank()
        if (!hasCloudKey) {
            val choice = menu(
                "No API keys entered. Use Ollama for local LLM?",
                "Skip (configure later in .env)",
                "Install Ollama in Docker (free, private — downloads ~2GB)",
                "I already have Ollama running locally",
            )
            when (choice) {
                1 -> {
                    useOllama = true
                    setEnvKey(envFile, "LLM_PROVIDER", "ollama")
                    setEnvKey(envFile, "LLM_MODEL", "llama3.2")
                    println("${GREEN}Using Ollama.$RESET Model will download on first start (~2GB).")
                }
                2 -> {
                    useHostOllama = true
                    setEnvKey(envFile, "LLM_PROVIDER", "ollama")
                    setEnvKey(envFile, "LLM_MODEL", "llama3.2")
                    setEnvKey(envFile, "LLM_BASE_URL", "http://host.docker.internal:11434")
                    println("${GREEN}Using existing Ollama$RESET at host.docker.internal:11434")
                    println("${DIM}Make sure Ollama is running: ollama serve$RESET")
                }
                else -> {
                    println("${DIM}Skipped — edit .env later to add LLM provider keys.$RESET")
                }
            }
        }
    }

    // ── Auth configuration ─────────────────────────────────────────────────────

    private fun configureAuth(envFile: File) {
        if (envHasKey(envFile, "API_KEY")) {
            authConfigured = true
            return
        }
        if (!interactive) return

        val choice = menu(
            "Secure your server with an API key?",
            "Skip — leave open (localhost only)",
            "Generate a random key",
            "Enter my own key",
        )

        when (choice) {
            1 -> {
                val key = generateApiKey()
                setEnvKey(envFile, "API_KEY", key)
                authConfigured = true
                println("${GREEN}API key set:$RESET $key")
                println("${DIM}Use header:  X-API-Key: $key$RESET")
            }
            2 -> {
                val key = prompt("API key") ?: return
                if (key.isNotBlank()) {
                    setEnvKey(envFile, "API_KEY", key)
                    authConfigured = true
                    println("${GREEN}Saved.$RESET")
                }
            }
            else -> println("${DIM}Skipped — fine for localhost development.$RESET")
        }
    }

    private fun generateApiKey(): String {
        val bytes = ByteArray(20)
        SecureRandom().nextBytes(bytes)
        return "nai-" + bytes.joinToString("") { "%02x".format(it) }
    }

    // ── Status detection ───────────────────────────────────────────────────────

    private fun detectStatus(envFile: File) {
        val providers = mutableListOf<String>()
        if (envHasKey(envFile, "ANTHROPIC_API_KEY")) providers.add("Anthropic Claude")
        if (envHasKey(envFile, "OPENAI_API_KEY"))    providers.add("OpenAI GPT")
        if (envHasKey(envFile, "GOOGLE_API_KEY"))    providers.add("Google Gemini")
        if (envHasKey(envFile, "LLM_API_KEY"))       providers.add("Custom LLM")
        if (useOllama) providers.add("Ollama (Docker)")
        if (useHostOllama) providers.add("Ollama (host)")
        if (providers.isNotEmpty()) {
            llmConfigured = true
            llmProviderLabel = providers.joinToString(", ")
        }
        authConfigured = envHasKey(envFile, "API_KEY")
    }

    // ── Compose generation ─────────────────────────────────────────────────────

    private fun generateCompose() {
        val template = when {
            useOllama -> COMPOSE_WITH_OLLAMA
            useHostOllama -> COMPOSE_HOST_OLLAMA
            else -> COMPOSE_BASIC
        }
        // @{ is a placeholder for ${ to avoid Kotlin string interpolation
        File(installDir, "docker-compose.yml").writeText(template.replace("@{", "\${"))
    }

    // ── Existing container resolution ─────────────────────────────────────────

    /**
     * Check for existing containers that would conflict with `docker compose up`.
     * Returns true to proceed with start, false to skip (user chose to keep existing).
     */
    private fun resolveExistingContainers(): Boolean {
        val names = mutableListOf("nocturnusai")
        if (useOllama) names.add("nocturnusai-ollama")

        val conflicts = names.filter { name ->
            sh("$containerCmd inspect $name >/dev/null 2>&1").success
        }
        if (conflicts.isEmpty()) return true

        // Check if the existing container is already running and healthy
        val running = conflicts.filter { name ->
            sh("$containerCmd inspect -f '{{.State.Running}}' $name 2>/dev/null").output.contains("true")
        }

        if (running.isNotEmpty()) {
            println("${YELLOW}Found existing NocturnusAI container(s): ${running.joinToString(", ")}$RESET")

            if (!interactive) {
                // Non-interactive: replace silently
                removeContainers(conflicts)
                return true
            }

            val choice = menu(
                "An existing NocturnusAI server is already running.",
                "Keep it (skip starting a new one)",
                "Replace it (stop old, start fresh)",
                "Stop it and exit",
            )

            return when (choice) {
                0 -> {
                    println("${GREEN}Keeping existing server.$RESET")
                    // Still print the banner — server is running
                    val healthy = checkHealth()
                    if (healthy) println("${GREEN}${BOLD}Ready!$RESET")
                    printSuccessBanner()
                    false
                }
                1 -> {
                    println("${DIM}Removing old containers...$RESET")
                    removeContainers(conflicts)
                    true
                }
                2 -> {
                    println("${DIM}Stopping containers...$RESET")
                    removeContainers(conflicts)
                    println("${GREEN}Stopped.$RESET")
                    false
                }
                else -> false
            }
        } else {
            // Containers exist but are stopped — remove them to avoid name conflicts
            println("${DIM}Removing stopped containers: ${conflicts.joinToString(", ")}$RESET")
            removeContainers(conflicts)
            return true
        }
    }

    private fun removeContainers(names: List<String>) {
        for (name in names) {
            sh("$containerCmd rm -f $name 2>/dev/null")
        }
    }

    // ── Health check ───────────────────────────────────────────────────────────

    private fun waitForHealth(): Boolean {
        print("Waiting for server")
        System.out.flush()
        for (i in 1..30) {
            if (checkHealth()) {
                println()
                println("${GREEN}${BOLD}Ready!$RESET")
                return true
            }
            print(".")
            System.out.flush()
            Thread.sleep(2000)
        }
        println()
        println("${YELLOW}Server still starting...$RESET")
        println("${DIM}Check: $composeCmd logs -f nocturnusai$RESET")
        return false
    }

    private fun checkHealth(): Boolean = try {
        val conn = URI("http://localhost:$port/health").toURL().openConnection() as HttpURLConnection
        conn.connectTimeout = 2000
        conn.readTimeout = 2000
        val ok = conn.responseCode == 200
        conn.disconnect()
        ok
    } catch (_: Exception) {
        false
    }

    // ── Ollama model pull ──────────────────────────────────────────────────────

    private fun pullOllamaModel() {
        print("${DIM}Waiting for Ollama...")
        System.out.flush()
        for (i in 1..15) {
            if (sh("curl -sf http://localhost:11434/api/tags >/dev/null 2>&1").success) {
                println(" ready$RESET")
                println("${DIM}Pulling model (llama3.2)... runs in background.$RESET")
                sh("curl -sf http://localhost:11434/api/pull -d '{\"name\":\"llama3.2\"}' >/dev/null 2>&1 &")
                return
            }
            Thread.sleep(2000)
        }
        println(" ${YELLOW}not ready yet$RESET")
        println("${DIM}Model will pull automatically when Ollama is healthy.$RESET")
    }

    // ── Success banner ─────────────────────────────────────────────────────────

    private fun printSuccessBanner() {
        println()
        println("${GREEN}${BOLD}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━$RESET")
        println("${GREEN}${BOLD}  NocturnusAI is running!$RESET")
        println("${GREEN}${BOLD}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━$RESET")
        println()

        // ── Status summary ──
        println("  ${BOLD}Server$RESET       http://localhost:$port")
        if (llmConfigured)
            println("  ${BOLD}LLM$RESET          ${GREEN}$llmProviderLabel$RESET")
        else
            println("  ${BOLD}LLM$RESET          ${DIM}none — add a key to .env and restart$RESET")
        if (authConfigured)
            println("  ${BOLD}Auth$RESET         ${GREEN}API key required$RESET  ${DIM}(X-API-Key header)$RESET")
        else
            println("  ${BOLD}Auth$RESET         ${DIM}open (localhost dev)$RESET")
        if (useOllama)
            println("  ${BOLD}Ollama$RESET       http://localhost:11434 ${DIM}(Docker)$RESET")
        if (useHostOllama)
            println("  ${BOLD}Ollama$RESET       ${GREEN}using host Ollama$RESET ${DIM}(host.docker.internal:11434)$RESET")
        println()

        // ── Quick start ──
        println("  ${BOLD}Quick start$RESET")
        println()
        println("    ${CYAN}# Start the REPL — just type and go$RESET")
        println("    nocturnusai")
        println()
        println("    ${CYAN}# Or use one-liners$RESET")
        println("    nocturnusai -e \"tell human(socrates)\"")
        println("    nocturnusai -e \"teach mortal(?x) :- human(?x)\"")
        println("    nocturnusai -e \"ask mortal(?who)\"")
        println()

        // ── curl examples ──
        println("    ${CYAN}# Or use curl directly$RESET")
        println("    curl -s http://localhost:$port/tell \\")
        println("      -H 'Content-Type: application/json' \\")
        println("      -H 'X-Tenant-ID: default' \\")
        println("      -d '{\"predicate\":\"human\",\"args\":[\"socrates\"]}'")
        println()

        // ── LLM examples ──
        if (llmConfigured)
            println("  ${BOLD}LLM-powered$RESET  ${GREEN}ready to use$RESET")
        else
            println("  ${BOLD}LLM-powered$RESET  ${DIM}(add API key to .env, restart, then try these)$RESET")
        println()
        println("    ${CYAN}# Extract facts from natural language$RESET")
        println("    curl -s http://localhost:$port/extract \\")
        println("      -H 'Content-Type: application/json' \\")
        println("      -H 'X-Tenant-ID: default' \\")
        println("      -d '{\"text\":\"Socrates is human. All humans are mortal.\",\"assert\":true}'")
        println()
        println("    ${CYAN}# Ask a question in plain English$RESET")
        println("    curl -s http://localhost:$port/synthesize \\")
        println("      -H 'Content-Type: application/json' \\")
        println("      -H 'X-Tenant-ID: default' \\")
        println("      -d '{\"question\":\"Is Socrates mortal?\"}'")
        println()

        // ── Endpoints ──
        println("  ${BOLD}Endpoints$RESET")
        println("    Health       http://localhost:$port/health")
        println("    API Docs     http://localhost:$port/llm.txt")
        println("    MCP          http://localhost:$port/mcp")
        println("    Agent Card   http://localhost:$port/.well-known/agent.json")
        println()

        // ── Manage ──
        println("  ${BOLD}Manage$RESET")
        println("    cd ${installDir.path}")
        println("    $composeCmd logs -f nocturnusai   ${DIM}# tail logs$RESET")
        println("    $composeCmd down                   ${DIM}# stop$RESET")
        println("    $composeCmd up -d                  ${DIM}# restart$RESET")
        println()

        // ── MCP config ──
        println("  ${BOLD}MCP config$RESET (Claude Desktop, Cursor, Windsurf, etc.):")
        println()
        println("    {")
        println("      \"mcpServers\": {")
        println("        \"nocturnusai\": {")
        println("          \"url\": \"http://localhost:$port/mcp/sse\",")
        println("          \"transport\": \"sse\"")
        println("        }")
        println("      }")
        println("    }")
        println()
        println("  ${DIM}Config: ${installDir.path}/.env$RESET")
        println("  ${DIM}Docs:   https://github.com/Auctalis/nocturnusai$RESET")
        println()
    }

    companion object {
        private const val RESET  = "\u001B[0m"
        private const val BOLD   = "\u001B[1m"
        private const val DIM    = "\u001B[2m"
        private const val GREEN  = "\u001B[32m"
        private const val CYAN   = "\u001B[36m"
        private const val YELLOW = "\u001B[33m"
        private const val RED    = "\u001B[31m"

        private const val IMAGE = "ghcr.io/auctalis/nocturnusai:latest"
        private const val REPO_URL = "https://github.com/Auctalis/nocturnusai.git"
        private const val REPO_RAW = "https://raw.githubusercontent.com/Auctalis/nocturnusai/main"

        // Compose templates — @{ is a placeholder for ${ (avoids Kotlin interpolation)
        // Compose reads .env automatically for variable substitution.

        private val COMPOSE_BASIC = """
services:
  nocturnusai:
    image: ghcr.io/auctalis/nocturnusai:latest
    container_name: nocturnusai
    restart: unless-stopped
    ports:
      - "@{PORT:-9300}:@{PORT:-9300}"
    volumes:
      - nocturnusai-data:/data
    environment:
      - PORT=@{PORT:-9300}
      - HOST=0.0.0.0
      - STORAGE_DIR=/data
      - API_KEY=@{API_KEY:-}
      - LLM_PROVIDER=@{LLM_PROVIDER:-}
      - LLM_MODEL=@{LLM_MODEL:-}
      - LLM_BASE_URL=@{LLM_BASE_URL:-}
      - ANTHROPIC_API_KEY=@{ANTHROPIC_API_KEY:-}
      - OPENAI_API_KEY=@{OPENAI_API_KEY:-}
      - GOOGLE_API_KEY=@{GOOGLE_API_KEY:-}
    healthcheck:
      test: ["CMD", "curl", "-sf", "http://localhost:@{PORT:-9300}/health"]
      interval: 10s
      timeout: 5s
      retries: 5
      start_period: 30s

volumes:
  nocturnusai-data:
    driver: local
""".trimIndent() + "\n"

        private val COMPOSE_HOST_OLLAMA = """
services:
  nocturnusai:
    image: ghcr.io/auctalis/nocturnusai:latest
    container_name: nocturnusai
    restart: unless-stopped
    ports:
      - "@{PORT:-9300}:@{PORT:-9300}"
    volumes:
      - nocturnusai-data:/data
    extra_hosts:
      - "host.docker.internal:host-gateway"
    environment:
      - PORT=@{PORT:-9300}
      - HOST=0.0.0.0
      - STORAGE_DIR=/data
      - API_KEY=@{API_KEY:-}
      - LLM_PROVIDER=@{LLM_PROVIDER:-}
      - LLM_MODEL=@{LLM_MODEL:-}
      - LLM_BASE_URL=@{LLM_BASE_URL:-http://host.docker.internal:11434}
      - ANTHROPIC_API_KEY=@{ANTHROPIC_API_KEY:-}
      - OPENAI_API_KEY=@{OPENAI_API_KEY:-}
      - GOOGLE_API_KEY=@{GOOGLE_API_KEY:-}
    healthcheck:
      test: ["CMD", "curl", "-sf", "http://localhost:@{PORT:-9300}/health"]
      interval: 10s
      timeout: 5s
      retries: 5
      start_period: 30s

volumes:
  nocturnusai-data:
    driver: local
""".trimIndent() + "\n"

        private val COMPOSE_WITH_OLLAMA = """
services:
  nocturnusai:
    image: ghcr.io/auctalis/nocturnusai:latest
    container_name: nocturnusai
    restart: unless-stopped
    ports:
      - "@{PORT:-9300}:@{PORT:-9300}"
    volumes:
      - nocturnusai-data:/data
    environment:
      - PORT=@{PORT:-9300}
      - HOST=0.0.0.0
      - STORAGE_DIR=/data
      - API_KEY=@{API_KEY:-}
      - LLM_PROVIDER=@{LLM_PROVIDER:-}
      - LLM_MODEL=@{LLM_MODEL:-}
      - LLM_BASE_URL=@{LLM_BASE_URL:-}
      - ANTHROPIC_API_KEY=@{ANTHROPIC_API_KEY:-}
      - OPENAI_API_KEY=@{OPENAI_API_KEY:-}
      - GOOGLE_API_KEY=@{GOOGLE_API_KEY:-}
    healthcheck:
      test: ["CMD", "curl", "-sf", "http://localhost:@{PORT:-9300}/health"]
      interval: 10s
      timeout: 5s
      retries: 5
      start_period: 30s

  ollama:
    image: ollama/ollama:latest
    container_name: nocturnusai-ollama
    restart: unless-stopped
    ports:
      - "11434:11434"
    volumes:
      - ollama-models:/root/.ollama
    healthcheck:
      test: ["CMD", "curl", "-sf", "http://localhost:11434/api/tags"]
      interval: 10s
      timeout: 5s
      retries: 10
      start_period: 15s

volumes:
  nocturnusai-data:
    driver: local
  ollama-models:
    driver: local
""".trimIndent() + "\n"
    }
}
