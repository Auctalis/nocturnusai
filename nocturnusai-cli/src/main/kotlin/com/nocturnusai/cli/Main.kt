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
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    // Handle setup subcommand before creating Client (server may not be running)
    if (args.isNotEmpty() && args[0] == "uninstall") {
        uninstall()
        exitProcess(0)
    }

    if (args.isNotEmpty() && args[0] == "setup") {
        val setupArgs = parseSetupArgs(args.drop(1).toTypedArray())
        val code = Setup(
            dir = setupArgs.dir,
            port = setupArgs.port,
            ollamaFlag = setupArgs.ollama,
            hostOllamaFlag = setupArgs.hostOllama,
            llmKeys = setupArgs.keys,
            nonInteractive = setupArgs.nonInteractive,
        ).run()
        exitProcess(code)
    }

    val parsed = parseArgs(args)
    val client = Client(
        serverUrl = parsed.server,
        database = parsed.database,
        apiKey = parsed.apiKey,
        tenantId = parsed.tenantId,
    )

    if (parsed.exec != null) {
        // Single command mode: run one command and exit
        Repl(client).execSingle(parsed.exec)
    } else {
        Repl(client).run()
    }
}

// ── CLI arg parsing ────────────────────────────────────────────────────────

private data class CliArgs(
    val server: String = "http://localhost:9300",
    val database: String = "default",
    val apiKey: String? = null,
    val tenantId: String? = null,
    val exec: String? = null,
)

/** Read defaults from ~/.config/nocturnusai/config (written by `nocturnusai setup`). */
private fun loadCliConfig(): Map<String, String> {
    val configFile = File(System.getProperty("user.home"), ".config/nocturnusai/config")
    if (!configFile.exists()) return emptyMap()
    return configFile.readLines()
        .filter { it.contains('=') && !it.trimStart().startsWith("#") }
        .associate { line ->
            val key = line.substringBefore('=').trim()
            val value = line.substringAfter('=').trim()
            key to value
        }
}

private fun parseArgs(args: Array<String>): CliArgs {
    val config = loadCliConfig()

    // Defaults: CLI flags > env vars > config file > hardcoded
    var server = System.getenv("NOCTURNUSAI_SERVER") ?: config["server"] ?: "http://localhost:9300"
    var database = "default"
    var apiKey: String? = System.getenv("NOCTURNUSAI_API_KEY") ?: config["api_key"]
    var tenantId: String? = null
    var exec: String? = null

    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "--server", "-s"   -> { server = args.getOrElse(i + 1) { server }; i += 2 }
            "--db", "-d"       -> { database = args.getOrElse(i + 1) { database }; i += 2 }
            "--api-key", "-k"  -> { apiKey = args.getOrElse(i + 1) { null }; i += 2 }
            "--tenant", "-t"   -> { tenantId = args.getOrElse(i + 1) { null }; i += 2 }
            "-e", "--exec"     -> { exec = args.getOrElse(i + 1) { null }; i += 2 }
            "--help", "-h"     -> { printUsage(); exitProcess(0) }
            else               -> { i++ }
        }
    }

    return CliArgs(server, database, apiKey, tenantId, exec)
}

// ── Setup arg parsing ──────────────────────────────────────────────────────

internal data class SetupArgs(
    val dir: String = "./nocturnusai",
    val port: Int = 9300,
    val ollama: Boolean = false,
    val hostOllama: Boolean = false,
    val keys: List<String> = emptyList(),
    val nonInteractive: Boolean = false,
)

internal fun parseSetupArgs(args: Array<String>): SetupArgs {
    var dir = "./nocturnusai"
    var port = 9300
    var ollama = false
    var hostOllama = false
    val keys = mutableListOf<String>()
    var nonInteractive = false

    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "--dir"             -> { dir = args.getOrElse(i + 1) { dir }; i += 2 }
            "--port"            -> { port = args.getOrElse(i + 1) { "9300" }.toIntOrNull() ?: 9300; i += 2 }
            "--ollama"          -> { ollama = true; i++ }
            "--host-ollama"     -> { hostOllama = true; i++ }
            "--key"             -> { args.getOrElse(i + 1) { null }?.let { keys.add(it) }; i += 2 }
            "--non-interactive" -> { nonInteractive = true; i++ }
            "--help", "-h"      -> { printSetupUsage(); exitProcess(0) }
            else                -> { i++ }
        }
    }

    return SetupArgs(dir, port, ollama, hostOllama, keys, nonInteractive)
}

// ── Uninstall ─────────────────────────────────────────────────────────────

private fun uninstall() {
    val R = "\u001B[0m"
    val B = "\u001B[1m"
    val D = "\u001B[2m"
    val G = "\u001B[32m"
    val Y = "\u001B[33m"

    println()
    println("${B}NocturnusAI Uninstall$R")
    println()

    // 1. Stop and remove containers
    val compose = when {
        Runtime.getRuntime().exec(arrayOf("bash", "-c", "docker compose version >/dev/null 2>&1")).waitFor() == 0 -> "docker compose"
        Runtime.getRuntime().exec(arrayOf("bash", "-c", "command -v docker-compose >/dev/null 2>&1")).waitFor() == 0 -> "docker-compose"
        Runtime.getRuntime().exec(arrayOf("bash", "-c", "command -v podman-compose >/dev/null 2>&1")).waitFor() == 0 -> "podman-compose"
        else -> null
    }

    // Check common install directories for compose files
    val installDirs = listOf(
        File("./nocturnusai"),
        File(System.getProperty("user.home"), "nocturnusai"),
    ).filter { File(it, "docker-compose.yml").exists() }

    for (dir in installDirs) {
        if (compose != null) {
            println("${D}Stopping containers in ${dir.path}...$R")
            ProcessBuilder("bash", "-c", "$compose down")
                .directory(dir).inheritIO().start().waitFor()
        }
    }

    // 2. Remove containers by name (in case compose file is gone)
    for (name in listOf("nocturnusai", "nocturnusai-ollama")) {
        val inspect = ProcessBuilder("bash", "-c", "docker inspect $name >/dev/null 2>&1")
            .start().waitFor()
        if (inspect == 0) {
            println("${D}Removing container: $name$R")
            ProcessBuilder("bash", "-c", "docker rm -f $name")
                .inheritIO().start().waitFor()
        }
    }

    // 3. Remove CLI binary
    val binaryPaths = listOf(
        File("/usr/local/bin/nocturnusai"),
        File(System.getProperty("user.home"), ".local/bin/nocturnusai"),
    )
    for (bin in binaryPaths) {
        if (bin.exists()) {
            println("${D}Removing CLI binary: ${bin.path}$R")
            bin.delete()
        }
    }

    // 4. Remove config
    val configDir = File(System.getProperty("user.home"), ".config/nocturnusai")
    if (configDir.exists()) {
        println("${D}Removing CLI config: ${configDir.path}$R")
        configDir.deleteRecursively()
    }

    // 5. Summary
    println()
    println("${G}NocturnusAI uninstalled.$R")
    if (installDirs.isNotEmpty()) {
        println()
        println("${Y}Your data was preserved:$R")
        for (dir in installDirs) {
            val dataDir = File(dir, "data")
            if (dataDir.exists()) {
                println("  ${dir.canonicalPath}/data/")
            } else {
                println("  ${dir.canonicalPath}/")
            }
        }
        println("${D}Remove manually if no longer needed: rm -rf <dir>$R")
    }
    println()
}

// ── Help text ──────────────────────────────────────────────────────────────

private fun printUsage() {
    println("""
NocturnusAI CLI — logic server for agentic AI

Usage:
  nocturnusai                          Interactive REPL
  nocturnusai setup [options]          Set up & start server (Docker/Podman)
  nocturnusai -e "tell likes(a,b)"    Run one command and exit
  cat kb.ab | nocturnusai -e "import /dev/stdin"

Subcommands:
  setup       Install and configure NocturnusAI server
  uninstall   Remove NocturnusAI (containers, CLI binary, config)

Options:
  -s, --server <url>      Server URL (default: http://localhost:9300)
  -d, --db <name>         Database name (default: default)
  -k, --api-key <key>     API key for authentication
  -t, --tenant <id>       Tenant ID for multi-tenant databases
  -e, --exec <command>    Execute a single command and exit
  -h, --help              Show this help

Examples:
  nocturnusai
  nocturnusai setup --ollama
  nocturnusai -d mydb -e "tell human(socrates)"
  nocturnusai -d mydb -e "ask mortal(?who)"
  nocturnusai -d mydb -e "export"
  nocturnusai -d mydb -e "import knowledge.ab"
    """.trimIndent())
}

private fun printSetupUsage() {
    println("""
nocturnusai setup — Install and configure NocturnusAI

Sets up a NocturnusAI server using Docker or Podman:
  - Detects Docker/Podman and pulls the container image
  - Configures LLM provider (Anthropic, OpenAI, Google, or Ollama)
  - Optionally generates an API key for authentication
  - Creates docker-compose.yml and .env
  - Starts the server and waits for it to be ready

Usage: nocturnusai setup [options]

Options:
  --dir DIR              Install directory (default: ./nocturnusai)
  --port PORT            Server port (default: 9300)
  --ollama               Run Ollama in Docker (no API key needed)
  --host-ollama          Use existing Ollama on your machine
  --key KEY              LLM API key (repeatable, auto-detects provider)
  --non-interactive      Skip interactive prompts, use defaults
  -h, --help             Show this help

Examples:
  nocturnusai setup                           # interactive wizard
  nocturnusai setup --ollama                  # Ollama in Docker
  nocturnusai setup --host-ollama             # use existing local Ollama
  nocturnusai setup --key sk-ant-abc123...    # Anthropic Claude
  nocturnusai setup --key sk-ant-... --key sk-...  # multiple providers
  nocturnusai setup --port 8080 --dir ./ai    # custom port and directory
    """.trimIndent())
}
