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
    val tenantId: String = "default",
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
    var tenantId: String = "default"
    var exec: String? = null

    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "--server", "-s"   -> { server = args.getOrElse(i + 1) { server }; i += 2 }
            "--db", "-d"       -> { database = args.getOrElse(i + 1) { database }; i += 2 }
            "--api-key", "-k"  -> { apiKey = args.getOrElse(i + 1) { null }; i += 2 }
            "--tenant", "-t"   -> { tenantId = args.getOrElse(i + 1) { "default" }; i += 2 }
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
    val hostOllama: Boolean = false,
    val keys: List<String> = emptyList(),
    val nonInteractive: Boolean = false,
)

internal fun parseSetupArgs(args: Array<String>): SetupArgs {
    var dir = "./nocturnusai"
    var port = 9300
    var hostOllama = false
    val keys = mutableListOf<String>()
    var nonInteractive = false

    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "--dir"             -> { dir = args.getOrElse(i + 1) { dir }; i += 2 }
            "--port"            -> { port = args.getOrElse(i + 1) { "9300" }.toIntOrNull() ?: 9300; i += 2 }
            "--host-ollama"     -> { hostOllama = true; i++ }
            "--key"             -> { args.getOrElse(i + 1) { null }?.let { keys.add(it) }; i += 2 }
            "--non-interactive" -> { nonInteractive = true; i++ }
            "--help", "-h"      -> { printSetupUsage(); exitProcess(0) }
            else                -> { i++ }
        }
    }

    return SetupArgs(dir, port, hostOllama, keys, nonInteractive)
}

// ── Uninstall ─────────────────────────────────────────────────────────────

private fun uninstall() {
    val R = "\u001B[0m"
    val B = "\u001B[1m"
    val D = "\u001B[2m"
    val G = "\u001B[32m"
    val Y = "\u001B[33m"
    val RED = "\u001B[31m"

    println()
    println("${B}NocturnusAI Uninstall$R")
    println()

    // Detect container runtime
    val compose = when {
        Runtime.getRuntime().exec(arrayOf("bash", "-c", "docker compose version >/dev/null 2>&1")).waitFor() == 0 -> "docker compose"
        Runtime.getRuntime().exec(arrayOf("bash", "-c", "command -v docker-compose >/dev/null 2>&1")).waitFor() == 0 -> "docker-compose"
        Runtime.getRuntime().exec(arrayOf("bash", "-c", "command -v podman-compose >/dev/null 2>&1")).waitFor() == 0 -> "podman-compose"
        else -> null
    }

    // Find install directories
    val installDirs = listOf(
        File("./nocturnusai"),
        File(System.getProperty("user.home"), "nocturnusai"),
    ).filter { File(it, "docker-compose.yml").exists() }

    // Find data directories
    val dataDirs = installDirs.map { File(it, "data") }.filter { it.exists() && it.isDirectory }
    val ollamaDirs = installDirs.map { File(it, "ollama-models") }.filter { it.exists() && it.isDirectory }

    // ── Show what will be removed ──
    println("${B}This will remove:$R")
    println("  • NocturnusAI containers")
    val binaryPaths = listOf(
        File("/usr/local/bin/nocturnusai"),
        File(System.getProperty("user.home"), ".local/bin/nocturnusai"),
    ).filter { it.exists() }
    for (bin in binaryPaths) {
        println("  • CLI binary: ${bin.path}")
    }
    val configDir = File(System.getProperty("user.home"), ".config/nocturnusai")
    if (configDir.exists()) {
        println("  • CLI config: ${configDir.path}")
    }
    println()

    if (dataDirs.isNotEmpty() || ollamaDirs.isNotEmpty()) {
        println("${Y}${B}Data directories found:$R")
        for (dir in dataDirs) {
            println("  ${dir.canonicalPath}/")
        }
        for (dir in ollamaDirs) {
            println("  ${dir.canonicalPath}/")
        }
        println()
    }

    // ── Ask for confirmation ──
    print("${B}Proceed with uninstall? [y/N]:$R ")
    System.out.flush()
    val confirm = readlnOrNull()?.trim()?.lowercase()
    if (confirm != "y" && confirm != "yes") {
        println("${D}Cancelled.$R")
        return
    }
    println()

    // 1. Stop containers
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
    for (bin in binaryPaths) {
        println("${D}Removing: ${bin.path}$R")
        bin.delete()
    }

    // 4. Remove config
    if (configDir.exists()) {
        println("${D}Removing: ${configDir.path}$R")
        configDir.deleteRecursively()
    }

    // 5. Handle data — user decides
    if (dataDirs.isNotEmpty() || ollamaDirs.isNotEmpty()) {
        println()
        println("${Y}${B}What about your data?$R")
        for (dir in dataDirs) {
            println("  ${dir.canonicalPath}/")
        }
        for (dir in ollamaDirs) {
            println("  ${dir.canonicalPath}/")
        }
        println()
        println("  ${B}1)$R Keep data (you can reimport later)")
        println("  ${B}2)$R ${RED}Delete everything permanently$R")
        println()
        print("${B}Choose [1]:$R ")
        System.out.flush()
        val dataChoice = readlnOrNull()?.trim()

        if (dataChoice == "2") {
            // Double-confirm destructive action
            print("${RED}${B}Are you sure? This cannot be undone. Type 'delete' to confirm:$R ")
            System.out.flush()
            val deleteConfirm = readlnOrNull()?.trim()?.lowercase()
            if (deleteConfirm == "delete") {
                for (dir in installDirs) {
                    println("${D}Deleting: ${dir.canonicalPath}$R")
                    dir.deleteRecursively()
                }
                println("${G}All data deleted.$R")
            } else {
                println("${D}Data preserved.$R")
            }
        } else {
            println("${G}Data preserved.$R")
            for (dir in installDirs) {
                println("  ${dir.canonicalPath}/")
            }
        }
    } else {
        // No data dirs, safe to clean up install dirs
        for (dir in installDirs) {
            dir.deleteRecursively()
        }
    }

    println()
    println("${G}NocturnusAI uninstalled.$R")
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
  nocturnusai setup --host-ollama
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
  --host-ollama          Use existing Ollama on your machine
  --key KEY              LLM API key (repeatable, auto-detects provider)
  --non-interactive      Skip interactive prompts, use defaults
  -h, --help             Show this help

Examples:
  nocturnusai setup                           # interactive wizard
  nocturnusai setup --host-ollama             # use existing local Ollama
  nocturnusai setup --key sk-ant-abc123...    # Anthropic Claude
  nocturnusai setup --key sk-ant-... --key sk-...  # multiple providers
  nocturnusai setup --port 8080 --dir ./ai    # custom port and directory
    """.trimIndent())
}
