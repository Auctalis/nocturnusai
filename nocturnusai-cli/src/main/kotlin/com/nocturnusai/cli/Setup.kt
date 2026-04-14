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
 * Called via:  nocturnusai setup [--dir DIR] [--port PORT] [--ollama] [--host-ollama] [--key KEY]
 */
class Setup(
    private val dir: String = "./nocturnusai",
    private val port: Int = 9300,
    private val bundledOllamaFlag: Boolean = false,
    private val hostOllamaFlag: Boolean = false,
    private val llmKeys: List<String> = emptyList(),
    private val nonInteractive: Boolean = false,
) {
    private val interactive = !nonInteractive && System.console() != null
    private var useBundledOllama = bundledOllamaFlag
    private var useHostOllama = hostOllamaFlag  // true = connect to existing host Ollama (no Docker container)
    private var composeCmd = ""
    private var containerCmd = ""
    private var llmConfigured = false
    private var llmProviderLabel = "none"
    private var ollamaModel = "granite3.3:8b"
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
        if (port != 9300) {
            setEnvKey(envFile, "PORT", port.toString())
        }

        // 5. Configure LLM
        configureLlm(envFile)

        // 6. Configure auth
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
                println("${RED}Build failed.$RESET")
                println("${DIM}Run:  $composeCmd logs nocturnusai  — to see what went wrong.$RESET")
                return 1
            }
        }

        // 12. Handle existing containers, then start server
        println()
        if (!resolveExistingContainers()) return 0

        if (!startStack()) return 1

        // 13. Wait for health
        println()
        waitForHealth()

        // 14. Success banner
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

    // ── Host address resolution (for reaching host Ollama from Docker) ────────

    /**
     * Determine the address the Docker container can use to reach the host machine.
     * On Docker Desktop (macOS/Windows), `host.docker.internal` resolves natively.
     * On Linux Docker Engine, try the Docker bridge gateway IP (typically 172.17.0.1).
     * Falls back to `host.docker.internal` if detection fails.
     */
    private fun detectHostAddress(): String {
        val os = System.getProperty("os.name", "").lowercase()
        // Docker Desktop (macOS, Windows) — host.docker.internal works natively
        if ("mac" in os || "darwin" in os || "windows" in os) {
            return "host.docker.internal"
        }
        // Linux: get the docker bridge gateway IP
        val bridgeIp = sh("$containerCmd network inspect bridge -f '{{range .IPAM.Config}}{{.Gateway}}{{end}}' 2>/dev/null")
        if (bridgeIp.success && bridgeIp.output.isNotBlank()) {
            return bridgeIp.output.trim()
        }
        // Fallback: host.docker.internal (works on Docker Engine 20.10+ if configured)
        return "host.docker.internal"
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

    private fun setupEnvFile(): File = File(installDir, ".env")

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

    private fun clearEnvKey(file: File, key: String) {
        if (!file.exists()) return
        val lines = file.readLines().filter { !it.trimStart().startsWith("$key=") }
        file.writeText(lines.joinToString("\n") + if (lines.isNotEmpty()) "\n" else "")
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

    private fun hostOllamaAvailable(): Boolean =
        sh("curl -sf http://localhost:11434/api/tags >/dev/null 2>&1").success

    private fun configureHostOllama(envFile: File, modelUrl: String? = "http://localhost:11434", announce: String? = null) {
        useHostOllama = true
        useBundledOllama = false
        ollamaModel = selectOllamaModel(modelUrl)
        val hostAddr = detectHostAddress()
        setEnvKey(envFile, "LLM_PROVIDER", "ollama")
        setEnvKey(envFile, "LLM_MODEL", ollamaModel)
        setEnvKey(envFile, "LLM_BASE_URL", "http://$hostAddr:11434/v1")
        setEnvKey(envFile, "EXTRACTION_ENABLED", "true")
        if (announce != null) {
            println(announce)
        } else {
            println("${GREEN}Using:$RESET existing Ollama on host with model ${BOLD}$ollamaModel$RESET")
        }
    }

    private fun configureBundledOllama(envFile: File, announce: String? = null) {
        useBundledOllama = true
        useHostOllama = false
        ollamaModel = selectOllamaModel(null)
        setEnvKey(envFile, "LLM_PROVIDER", "ollama")
        setEnvKey(envFile, "LLM_MODEL", ollamaModel)
        setEnvKey(envFile, "LLM_BASE_URL", "http://ollama:11434/v1")
        setEnvKey(envFile, "EXTRACTION_ENABLED", "true")
        if (announce != null) {
            println(announce)
        } else {
            println("${GREEN}Using:$RESET bundled Ollama with model ${BOLD}$ollamaModel$RESET")
        }
    }

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
            // Cloud provider — clear any stale Ollama base URL and enable extraction
            clearEnvKey(envFile, "LLM_BASE_URL")
            setEnvKey(envFile, "EXTRACTION_ENABLED", "true")
            return
        }

        // Flag: --ollama (reuse host Ollama when available, else start bundled Ollama)
        if (useBundledOllama) {
            if (hostOllamaAvailable()) {
                configureHostOllama(
                    envFile,
                    announce = "${GREEN}Found:$RESET host Ollama on localhost:11434 — reusing it instead of starting a second container."
                )
            } else {
                configureBundledOllama(envFile)
            }
            return
        }

        // Flag: --host-ollama (existing host Ollama)
        if (useHostOllama) {
            configureHostOllama(envFile)
            return
        }

        // Non-interactive: server only
        if (!interactive) {
            println("${GREEN}Server-only mode$RESET (no LLM — core API works without one)")
            println("${DIM}  Use --host-ollama for local Ollama, or --key <api-key> for cloud$RESET")
            return
        }

        // Interactive wizard — top-level LLM choice
        println()
        println("${DIM}Optional — the core logic API works without an LLM.$RESET")
        val llmChoice = menu(
            "LLM Provider",
            "Ollama — automatic local mode  ${DIM}(reuse host or start bundled, recommended)$RESET",
            "Ollama — already running on this machine${RESET}",
            "Anthropic Claude  ${DIM}(claude-sonnet-4-5)$RESET",
            "OpenAI GPT  ${DIM}(gpt-4.1-mini)$RESET",
            "Google Gemini  ${DIM}(gemini-2.5-flash)$RESET",
            "Skip — configure later",
        )

        when (llmChoice) {
            0 -> {
                if (hostOllamaAvailable()) {
                    configureHostOllama(
                        envFile,
                        announce = "${GREEN}Using Ollama$RESET from localhost:11434  ${DIM}(reused existing host service)$RESET"
                    )
                } else {
                    configureBundledOllama(
                        envFile,
                        announce = "${GREEN}Using Ollama$RESET in Docker  ${DIM}(No host Ollama found, bundled service will be started)$RESET"
                    )
                }
            }
            1 -> {
                configureHostOllama(envFile)
                println("${DIM}Make sure Ollama is running: ollama serve$RESET")
            }
            2 -> {
                val key = prompt("Anthropic API key ${DIM}(sk-ant-...)$RESET")
                if (!key.isNullOrBlank()) {
                    setEnvKey(envFile, "ANTHROPIC_API_KEY", key)
                    setEnvKey(envFile, "LLM_PROVIDER", "anthropic")
                    setEnvKey(envFile, "LLM_MODEL", "claude-sonnet-4-5-20250514")
                    clearEnvKey(envFile, "LLM_BASE_URL")
                    setEnvKey(envFile, "EXTRACTION_ENABLED", "true")
                    println("  ${GREEN}Anthropic Claude configured$RESET  ${DIM}model: claude-sonnet-4-5$RESET")
                } else {
                    println("${YELLOW}No key entered.$RESET LLM features won't be available.")
                }
            }
            3 -> {
                val key = prompt("OpenAI API key ${DIM}(sk-...)$RESET")
                if (!key.isNullOrBlank()) {
                    setEnvKey(envFile, "OPENAI_API_KEY", key)
                    setEnvKey(envFile, "LLM_PROVIDER", "openai")
                    setEnvKey(envFile, "LLM_MODEL", "gpt-4.1-mini")
                    clearEnvKey(envFile, "LLM_BASE_URL")
                    setEnvKey(envFile, "EXTRACTION_ENABLED", "true")
                    println("  ${GREEN}OpenAI configured$RESET  ${DIM}model: gpt-4.1-mini$RESET")
                } else {
                    println("${YELLOW}No key entered.$RESET LLM features won't be available.")
                }
            }
            4 -> {
                val key = prompt("Google API key ${DIM}(AIza...)$RESET")
                if (!key.isNullOrBlank()) {
                    setEnvKey(envFile, "GOOGLE_API_KEY", key)
                    setEnvKey(envFile, "LLM_PROVIDER", "google")
                    setEnvKey(envFile, "LLM_MODEL", "gemini-2.5-flash")
                    clearEnvKey(envFile, "LLM_BASE_URL")
                    setEnvKey(envFile, "EXTRACTION_ENABLED", "true")
                    println("  ${GREEN}Google Gemini configured$RESET  ${DIM}model: gemini-2.5-flash$RESET")
                } else {
                    println("${YELLOW}No key entered.$RESET LLM features won't be available.")
                }
            }
            else -> {
                println("${DIM}Skipped. Defaults are in docker-compose.yml; create .env in ${installDir.path}/ only if you want overrides later.$RESET")
            }
        }
    }

    // ── Ollama model selection ─────────────────────────────────────────────────

    /**
     * Select an Ollama model. If [ollamaUrl] is provided, query the running
     * instance for installed models and show them first, followed by popular
     * models not already installed. Otherwise offer only the popular list.
     */
    private fun selectOllamaModel(ollamaUrl: String?): String {
        if (!interactive) return "granite3.3:8b"

        val installed = if (ollamaUrl != null) queryOllamaModels(ollamaUrl) else emptyList()

        val popular = listOf(
            "granite3.3:8b"  to "IBM — strongest local extraction default",
            "llama3.2"       to "Meta — fast, general purpose",
            "llama3.1:8b"    to "Meta — larger, more capable",
            "mistral"        to "Mistral AI — good reasoning",
            "qwen2.5-coder"  to "Alibaba — great for code",
            "codellama"      to "Meta — code specialist",
            "phi4"           to "Microsoft — compact and capable",
            "gemma3"         to "Google — fast and efficient",
            "deepseek-r1"    to "DeepSeek — strong reasoning",
        ).filter { (name, _) -> installed.none { it == name || it.startsWith("$name:") } }

        val options = mutableListOf<String>()
        val values  = mutableListOf<String>()

        if (installed.isNotEmpty()) {
            installed.forEach { model ->
                options.add("$model  ${DIM}(installed)${RESET}")
                values.add(model)
            }
        }
        popular.forEach { (model, desc) ->
            options.add("$model  ${DIM}$desc${RESET}")
            values.add(model)
        }
        options.add("Other — enter model name")
        values.add("__other__")

        val header = if (installed.isNotEmpty())
            "Select Ollama model  ${DIM}(installed first, then popular)${RESET}"
        else
            "Select Ollama model  ${DIM}(Ollama not reachable — choose to pull on start)${RESET}"

        val choice = menu(header, *options.toTypedArray())
        return if (values[choice] == "__other__") {
            prompt("Model name", "granite3.3:8b") ?: "granite3.3:8b"
        } else {
            values[choice]
        }
    }

    /** Query a running Ollama instance for installed models via /api/tags. */
    private fun queryOllamaModels(baseUrl: String): List<String> {
        return try {
            val result = sh("curl -sf $baseUrl/api/tags 2>/dev/null")
            if (!result.success) return emptyList()
            // Parse JSON: {"models":[{"name":"llama3.2:latest",...},...]}
            val models = mutableListOf<String>()
            val regex = """"name"\s*:\s*"([^"]+)"""".toRegex()
            for (match in regex.findAll(result.output)) {
                val name = match.groupValues[1]
                    .removeSuffix(":latest") // clean up display
                models.add(name)
            }
            models
        } catch (_: Exception) {
            emptyList()
        }
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
        if (useBundledOllama) providers.add("Ollama (bundled)")
        if (useHostOllama) providers.add("Ollama (host)")
        if (providers.isNotEmpty()) {
            llmConfigured = true
            llmProviderLabel = providers.joinToString(", ")
        }
        authConfigured = envHasKey(envFile, "API_KEY")
    }

    // ── Compose generation ─────────────────────────────────────────────────────

    private fun generateCompose() {
        // @{ is a placeholder for ${ to avoid Kotlin string interpolation
        File(installDir, "docker-compose.yml").writeText(COMPOSE_TEMPLATE.replace("@{", "\${"))
    }

    // ── Existing container resolution ─────────────────────────────────────────

    /**
     * Check for existing containers that would conflict with `docker compose up`.
     * Returns true to proceed with start, false to skip (user chose to keep existing).
     */
    private fun resolveExistingContainers(): Boolean {
        val names = mutableListOf("nocturnusai", "nocturnusai-ollama")

        val conflicts = names.filter { name ->
            sh("$containerCmd inspect $name >/dev/null 2>&1").success
        }
        if (conflicts.isEmpty()) {
            // No containers, but check for existing data directory
            offerCleanInstall()
            return true
        }

        // Check if the existing container is already running and healthy
        val running = conflicts.filter { name ->
            sh("$containerCmd inspect -f '{{.State.Running}}' $name 2>/dev/null").output.contains("true")
        }

        // Check for existing data
        val dataDir = File(installDir, "data")
        val hasData = dataDir.exists() && dataDir.list()?.isNotEmpty() == true

        if (running.isNotEmpty()) {
            println("${YELLOW}Found existing NocturnusAI container(s): ${running.joinToString(", ")}$RESET")
            if (hasData) {
                println("${DIM}Data directory: ${dataDir.canonicalPath}/$RESET")
            }

            if (!interactive) {
                println("${DIM}Non-interactive mode: upgrading (data is preserved on disk).$RESET")
                removeContainers(conflicts)
                return true
            }

            if (hasData) {
                val choice = menu(
                    "An existing NocturnusAI server is running with data on disk.",
                    "Upgrade (keep data, replace container)",
                    "Clean install (delete all data, start fresh)",
                    "Keep existing (skip setup)",
                    "Stop and exit",
                )

                return when (choice) {
                    0 -> {
                        println("${DIM}Upgrading (data preserved in ${dataDir.canonicalPath}/)...$RESET")
                        removeContainers(conflicts)
                        true
                    }
                    1 -> {
                        confirmCleanInstall(dataDir, conflicts)
                    }
                    2 -> {
                        println("${GREEN}Keeping existing server.$RESET")
                        val healthy = checkHealth()
                        if (healthy) println("${GREEN}${BOLD}Ready!$RESET")
                        printSuccessBanner()
                        false
                    }
                    3 -> {
                        println("${DIM}Stopping containers...$RESET")
                        removeContainers(conflicts)
                        println("${GREEN}Stopped. Data preserved in ${dataDir.canonicalPath}/$RESET")
                        false
                    }
                    else -> false
                }
            } else {
                val choice = menu(
                    "An existing NocturnusAI server is already running.",
                    "Replace container and continue",
                    "Keep existing (skip setup)",
                    "Stop and exit",
                )

                return when (choice) {
                    0 -> {
                        println("${DIM}Replacing containers...$RESET")
                        removeContainers(conflicts)
                        true
                    }
                    1 -> {
                        println("${GREEN}Keeping existing server.$RESET")
                        val healthy = checkHealth()
                        if (healthy) println("${GREEN}${BOLD}Ready!$RESET")
                        printSuccessBanner()
                        false
                    }
                    2 -> {
                        println("${DIM}Stopping containers...$RESET")
                        removeContainers(conflicts)
                        println("${GREEN}Stopped.$RESET")
                        false
                    }
                    else -> false
                }
            }
        } else {
            // Containers exist but are stopped — need to remove to avoid name conflicts
            println("${YELLOW}Found stopped container(s): ${conflicts.joinToString(", ")}$RESET")
            if (hasData) {
                println("${DIM}Data directory: ${dataDir.canonicalPath}/$RESET")
            }

            if (interactive && hasData) {
                val choice = menu(
                    "Stopped containers found. How would you like to proceed?",
                    "Upgrade (remove old containers, keep data)",
                    "Clean install (delete all data, start fresh)",
                    "Cancel setup",
                )
                return when (choice) {
                    0 -> {
                        println("${DIM}Removing stopped containers (data preserved)...$RESET")
                        removeContainers(conflicts)
                        true
                    }
                    1 -> {
                        confirmCleanInstall(dataDir, conflicts)
                    }
                    2 -> {
                        println("${GREEN}Cancelled.$RESET")
                        false
                    }
                    else -> false
                }
            }

            println("${DIM}Removing stopped containers...$RESET")
            removeContainers(conflicts)
            return true
        }
    }

    /** If data/ exists but no containers, offer clean install option. */
    private fun offerCleanInstall() {
        val dataDir = File(installDir, "data")
        val hasData = dataDir.exists() && dataDir.list()?.isNotEmpty() == true
        if (!hasData || !interactive) return

        println("${YELLOW}Existing data found:$RESET ${dataDir.canonicalPath}/")
        val choice = menu(
            "Keep existing data or start fresh?",
            "Keep data (upgrade)",
            "Clean install (delete all data)",
        )
        if (choice == 1) {
            confirmCleanInstall(dataDir, emptyList())
        }
    }

    /** Double-confirm destructive data deletion, then execute. */
    private fun confirmCleanInstall(dataDir: File, containers: List<String>): Boolean {
        println()
        println("${RED}${BOLD}This will permanently delete all NocturnusAI data:$RESET")
        println("  ${dataDir.canonicalPath}/")
        println()
        print("${BOLD}Type 'delete' to confirm:$RESET ")
        System.out.flush()
        val confirm = readlnOrNull()?.trim()?.lowercase()
        if (confirm != "delete") {
            println("${DIM}Cancelled — keeping data.$RESET")
            if (containers.isNotEmpty()) removeContainers(containers)
            return true
        }
        if (containers.isNotEmpty()) removeContainers(containers)
        println("${DIM}Deleting data...$RESET")
        dataDir.deleteRecursively()
        dataDir.mkdirs()
        println("${GREEN}Clean install — starting fresh.$RESET")
        return true
    }

    private fun removeContainers(names: List<String>) {
        // compose down handles pods, networks, and name variants (works for both Docker and Podman)
        if (::installDir.isInitialized) {
            sh("${composePrefix(includeOllamaProfile = true)} down --remove-orphans 2>/dev/null", installDir)
        } else {
            // Fallback when installDir not yet set
            for (name in names) {
                sh("$containerCmd stop $name 2>/dev/null")
                sh("$containerCmd rm -f $name 2>/dev/null")
            }
        }
    }

    private fun composePrefix(includeOllamaProfile: Boolean = false): String =
        if (includeOllamaProfile) "$composeCmd --profile ollama" else composeCmd

    private fun startStack(): Boolean {
        if (!useBundledOllama) {
            println("${BOLD}Starting NocturnusAI...$RESET")
            return shVisible("${composePrefix()} up -d", installDir) == 0
        }

        val compose = composePrefix(includeOllamaProfile = true)
        println("${BOLD}Starting Ollama...$RESET")
        if (shVisible("$compose up -d ollama", installDir) != 0) {
            println("${RED}Failed to start bundled Ollama.$RESET")
            return false
        }

        println()
        if (!waitForOllama()) return false

        println("${DIM}Pulling Ollama model: $ollamaModel$RESET")
        if (shVisible("$compose exec -T ollama ollama pull \"$ollamaModel\"", installDir) != 0) {
            println("${RED}Failed to pull Ollama model: $ollamaModel$RESET")
            return false
        }

        println()
        println("${BOLD}Starting NocturnusAI...$RESET")
        return shVisible("$compose up -d nocturnusai", installDir) == 0
    }

    // ── Health check ───────────────────────────────────────────────────────────

    private fun waitForOllama(): Boolean {
        print("Waiting for Ollama")
        System.out.flush()
        for (i in 1..30) {
            if (checkOllama()) {
                println()
                println("${GREEN}Ollama is ready.$RESET")
                return true
            }
            print(".")
            System.out.flush()
            Thread.sleep(2000)
        }
        println()
        println("${YELLOW}Ollama is taking longer than expected to start.$RESET")
        println("${DIM}Check logs:  ${composePrefix(includeOllamaProfile = true)} logs -f ollama$RESET")
        return false
    }

    private fun checkOllama(): Boolean = try {
        val conn = URI("http://localhost:11434/api/tags").toURL().openConnection() as HttpURLConnection
        conn.connectTimeout = 2000
        conn.readTimeout = 2000
        val ok = conn.responseCode == 200
        conn.disconnect()
        ok
    } catch (_: Exception) {
        false
    }

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
        println("${YELLOW}Server is taking longer than expected to start.$RESET")
        println("${DIM}Check logs:  ${composePrefix(useBundledOllama)} logs -f nocturnusai$RESET")
        println("${DIM}Health:      curl http://localhost:$port/health$RESET")
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
        if (useBundledOllama)
            println("  ${BOLD}Ollama$RESET       ${GREEN}bundled Docker service$RESET ${DIM}(localhost:11434)$RESET")
        if (useHostOllama)
            println("  ${BOLD}Ollama$RESET       ${GREEN}using host Ollama$RESET ${DIM}(${detectHostAddress()}:11434)$RESET")
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
        println("    ${composePrefix(useBundledOllama)} logs -f nocturnusai   ${DIM}# tail logs$RESET")
        println("    ${composePrefix(useBundledOllama)} down                   ${DIM}# stop$RESET")
        println("    ${composePrefix(useBundledOllama)} up -d                  ${DIM}# restart$RESET")
        println()
        val envFile = File(installDir, ".env")
        if (envFile.exists()) {
            println("  ${DIM}Config overrides: ${envFile.path}$RESET")
        } else {
            println("  ${DIM}Config overrides: create ${envFile.path} only if you want to change the defaults.$RESET")
        }
        println("  ${DIM}Docs:   https://nocturnus.ai/$RESET")
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

        // Compose template — @{ is a placeholder for ${ (avoids Kotlin interpolation)
        // Compose reads .env automatically for variable substitution.
        // extra_hosts allows the container to reach Ollama running on the host machine.

        private val COMPOSE_TEMPLATE = """
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
      - LLM_API_KEY=@{LLM_API_KEY:-}
      - LLM_PROVIDER=@{LLM_PROVIDER:-}
      - LLM_MODEL=@{LLM_MODEL:-}
      - LLM_BASE_URL=@{LLM_BASE_URL:-}
      - ANTHROPIC_API_KEY=@{ANTHROPIC_API_KEY:-}
      - OPENAI_API_KEY=@{OPENAI_API_KEY:-}
      - GOOGLE_API_KEY=@{GOOGLE_API_KEY:-}
      - EXTRACTION_ENABLED=@{EXTRACTION_ENABLED:-false}
      - ENCRYPTION_KEY
    extra_hosts:
      - "host.docker.internal:host-gateway"
    healthcheck:
      test: ["CMD", "curl", "-sf", "http://localhost:@{PORT:-9300}/health"]
      interval: 10s
      timeout: 5s
      retries: 5
      start_period: 30s

  ollama:
    image: ollama/ollama:latest
    container_name: nocturnusai-ollama
    profiles:
      - ollama
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
      start_period: 20s
""".trimIndent() + "\n"
    }
}
