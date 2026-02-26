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
    private var serverApiKey: String? = null  // saved for config file + banner
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

        // 2. Prepare install directory (data/ subdir for bind mount)
        installDir = File(dir).canonicalFile
        installDir.mkdirs()
        File(installDir, "data").mkdirs()
        println("${GREEN}Directory:$RESET ${installDir.path}")

        // 3. Check for published image or source
        checkImage()

        // 4. Set up .env
        val envFile = setupEnvFile()
        setEnvKey(envFile, "PORT", port.toString())

        // 5. Configure LLM
        configureLlm(envFile)

        // 6. Select model
        selectModel(envFile)

        // 7. Configure auth
        configureAuth(envFile)

        // 8. Save CLI config (so `nocturnusai` auto-connects)
        saveCliConfig()

        // 9. Detect LLM/auth status for banner
        detectStatus(envFile)

        // 10. Generate compose file (fast path only — build path uses repo compose)
        if (!needBuild) generateCompose()

        // 11. Build if needed
        if (needBuild) {
            println()
            println("${BOLD}Building NocturnusAI from source...$RESET")
            println("${DIM}(first build takes 2-3 minutes — subsequent starts are instant)$RESET")
            if (shVisible("$composeCmd build nocturnusai", installDir) != 0) {
                println("${RED}Build failed.$RESET Check the output above.")
                return 1
            }
        }

        // 12. Handle existing containers, then start server
        println()
        if (!resolveExistingContainers()) return 0

        println("${BOLD}Starting NocturnusAI...$RESET")
        val upCmd = buildString {
            append(composeCmd)
            if (needBuild && useOllama) append(" --profile ollama")
            append(" up -d")
        }
        shVisible(upCmd, installDir)

        // 13. Wait for health
        println()
        val healthy = waitForHealth()

        // 14. Pull Ollama model if needed
        if (useOllama && healthy) pullOllamaModel()

        // 15. Success banner
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

    private fun getEnvValue(file: File, key: String): String? =
        if (file.exists()) file.readLines()
            .firstOrNull { it.trimStart().startsWith("$key=") }
            ?.substringAfter("=")?.trim()
        else null

    /** Save CLI config so `nocturnusai` auto-connects without flags. */
    private fun saveCliConfig() {
        val configDir = File(System.getProperty("user.home"), ".config/nocturnusai")
        configDir.mkdirs()
        val configFile = File(configDir, "config")
        val content = buildString {
            append("server=http://localhost:$port\n")
            if (serverApiKey != null) append("api_key=$serverApiKey\n")
        }
        configFile.writeText(content)
        println("${DIM}CLI config saved: ${configFile.path}$RESET")
    }

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

    // ── Model selection ────────────────────────────────────────────────────────

    private fun selectModel(envFile: File) {
        // Skip if Ollama (model is always llama3.2) or non-interactive
        if (useOllama || useHostOllama || !interactive) return

        // Build model options based on configured providers
        val hasAnthropic = envHasKey(envFile, "ANTHROPIC_API_KEY")
        val hasOpenAI = envHasKey(envFile, "OPENAI_API_KEY")
        val hasGoogle = envHasKey(envFile, "GOOGLE_API_KEY")

        if (!hasAnthropic && !hasOpenAI && !hasGoogle) return

        val models = mutableListOf<Pair<String, String>>() // label to provider:model
        if (hasAnthropic) {
            models.add("Anthropic — Claude Sonnet 4.5 (recommended)" to "anthropic:claude-sonnet-4-5-20250514")
            models.add("Anthropic — Claude Opus 4" to "anthropic:claude-opus-4-20250514")
            models.add("Anthropic — Claude Haiku 4.5 (fastest)" to "anthropic:claude-haiku-4-5-20251001")
            models.add("Anthropic — Claude Sonnet 4" to "anthropic:claude-sonnet-4-20250514")
        }
        if (hasOpenAI) {
            models.add("OpenAI — GPT-4.1 (latest)" to "openai:gpt-4.1")
            models.add("OpenAI — GPT-4.1 mini (fastest)" to "openai:gpt-4.1-mini")
            models.add("OpenAI — GPT-4.1 nano" to "openai:gpt-4.1-nano")
            models.add("OpenAI — GPT-4o" to "openai:gpt-4o")
            models.add("OpenAI — o3" to "openai:o3")
            models.add("OpenAI — o3 mini" to "openai:o3-mini")
            models.add("OpenAI — o4 mini (reasoning)" to "openai:o4-mini")
        }
        if (hasGoogle) {
            models.add("Google — Gemini 2.5 Pro (latest)" to "google:gemini-2.5-pro")
            models.add("Google — Gemini 2.5 Flash (fastest)" to "google:gemini-2.5-flash")
            models.add("Google — Gemini 2.0 Flash" to "google:gemini-2.0-flash")
        }

        val labels = models.map { it.first }.toTypedArray()
        val choice = menu("Select the default LLM model:", *labels)
        val (provider, model) = models[choice].second.split(":")
        setEnvKey(envFile, "LLM_PROVIDER", provider)
        setEnvKey(envFile, "LLM_MODEL", model)
        println("  ${GREEN}Default model:$RESET ${models[choice].first}")
    }

    // ── Auth configuration ─────────────────────────────────────────────────────

    private fun configureAuth(envFile: File) {
        if (envHasKey(envFile, "API_KEY")) {
            authConfigured = true
            serverApiKey = getEnvValue(envFile, "API_KEY")
            return
        }
        if (!interactive) return

        // For localhost, skip auth by default — no friction for dev
        println()
        println("  ${DIM}Auth is optional for local development. The server accepts all")
        println("  requests when no API key is set. Add one later in .env if needed.$RESET")

        val choice = menu(
            "Set up an API key?",
            "No — open access (recommended for local dev)",
            "Generate a random key",
            "Enter my own key",
        )

        when (choice) {
            1 -> {
                val key = generateApiKey()
                setEnvKey(envFile, "API_KEY", key)
                authConfigured = true
                serverApiKey = key
                println("${GREEN}API key set:$RESET $key")
            }
            2 -> {
                val key = prompt("API key") ?: return
                if (key.isNotBlank()) {
                    setEnvKey(envFile, "API_KEY", key)
                    authConfigured = true
                    serverApiKey = key
                    println("${GREEN}Saved.$RESET")
                }
            }
            else -> println("${GREEN}No auth — server is open.$RESET ${DIM}Add API_KEY to .env later to secure it.$RESET")
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
        if (useOllama)
            println("  ${BOLD}Ollama$RESET       http://localhost:11434 ${DIM}(Docker)$RESET")
        if (useHostOllama)
            println("  ${BOLD}Ollama$RESET       ${GREEN}using host Ollama$RESET ${DIM}(host.docker.internal:11434)$RESET")
        if (authConfigured)
            println("  ${BOLD}Auth$RESET         ${GREEN}API key set$RESET")
        println()

        // ── Try it now — commands that work immediately ──
        println("  ${BOLD}Try it now$RESET  ${DIM}(these work immediately — just paste)$RESET")
        println()
        println("    ${CYAN}# Start the interactive REPL$RESET")
        println("    nocturnusai")
        println()
        println("    ${CYAN}# Store a fact$RESET")
        println("    nocturnusai -e \"tell human(socrates)\"")
        println()
        println("    ${CYAN}# Define a rule$RESET")
        println("    nocturnusai -e \"teach mortal(?x) :- human(?x)\"")
        println()
        println("    ${CYAN}# Query$RESET")
        println("    nocturnusai -e \"ask mortal(?who)\"")
        println()

        if (llmConfigured) {
            println("    ${CYAN}# Natural language (LLM-powered)$RESET")
            println("    nocturnusai -e 'ingest Alice is a doctor. Bob is a lawyer.'")
            println("    nocturnusai -e 'ask what is Alice?'")
            println()
        }

        // ── MCP config — copy-paste ready ──
        println("  ${BOLD}Connect your AI agent$RESET  ${DIM}(Claude Desktop, Cursor, Windsurf, etc.)$RESET")
        println()
        if (authConfigured) {
            println("    ${DIM}Add to your MCP config:$RESET")
            println("    {")
            println("      \"mcpServers\": {")
            println("        \"nocturnusai\": {")
            println("          \"url\": \"http://localhost:$port/mcp/sse\",")
            println("          \"transport\": \"sse\",")
            println("          \"headers\": { \"X-API-Key\": \"$serverApiKey\" }")
            println("        }")
            println("      }")
            println("    }")
        } else {
            println("    ${DIM}Add to your MCP config:$RESET")
            println("    {")
            println("      \"mcpServers\": {")
            println("        \"nocturnusai\": {")
            println("          \"url\": \"http://localhost:$port/mcp/sse\",")
            println("          \"transport\": \"sse\"")
            println("        }")
            println("      }")
            println("    }")
        }
        println()

        // ── REST API ──
        println("  ${BOLD}REST API$RESET")
        println("    Health       http://localhost:$port/health")
        println("    API Docs     http://localhost:$port/llm.txt")
        println("    Agent Card   http://localhost:$port/.well-known/agent.json")
        println()

        // ── Manage ──
        println("  ${BOLD}Manage$RESET")
        println("    cd ${installDir.path}")
        println("    $composeCmd logs -f nocturnusai   ${DIM}# tail logs$RESET")
        println("    $composeCmd down                   ${DIM}# stop$RESET")
        println("    $composeCmd up -d                  ${DIM}# restart$RESET")
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
      - ./data:/data
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
      - ./data:/data
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
      - ./data:/data
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
      - ./ollama-models:/root/.ollama
    healthcheck:
      test: ["CMD", "curl", "-sf", "http://localhost:11434/api/tags"]
      interval: 10s
      timeout: 5s
      retries: 10
      start_period: 15s
""".trimIndent() + "\n"
    }
}
