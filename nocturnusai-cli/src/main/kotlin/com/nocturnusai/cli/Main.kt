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

import kotlin.system.exitProcess

fun main(args: Array<String>) {
    // Handle setup subcommand before creating Client (server may not be running)
    if (args.isNotEmpty() && args[0] == "setup") {
        val setupArgs = parseSetupArgs(args.drop(1).toTypedArray())
        val code = Setup(
            dir = setupArgs.dir,
            port = setupArgs.port,
            ollamaFlag = setupArgs.ollama,
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

private fun parseArgs(args: Array<String>): CliArgs {
    var server = "http://localhost:9300"
    var database = "default"
    var apiKey: String? = null
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
    val keys: List<String> = emptyList(),
    val nonInteractive: Boolean = false,
)

internal fun parseSetupArgs(args: Array<String>): SetupArgs {
    var dir = "./nocturnusai"
    var port = 9300
    var ollama = false
    val keys = mutableListOf<String>()
    var nonInteractive = false

    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "--dir"             -> { dir = args.getOrElse(i + 1) { dir }; i += 2 }
            "--port"            -> { port = args.getOrElse(i + 1) { "9300" }.toIntOrNull() ?: 9300; i += 2 }
            "--ollama"          -> { ollama = true; i++ }
            "--key"             -> { args.getOrElse(i + 1) { null }?.let { keys.add(it) }; i += 2 }
            "--non-interactive" -> { nonInteractive = true; i++ }
            "--help", "-h"      -> { printSetupUsage(); exitProcess(0) }
            else                -> { i++ }
        }
    }

    return SetupArgs(dir, port, ollama, keys, nonInteractive)
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
  setup     Install and configure NocturnusAI server

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
  --ollama               Use Ollama for local LLM (no API key needed)
  --key KEY              LLM API key (repeatable, auto-detects provider)
  --non-interactive      Skip interactive prompts, use defaults
  -h, --help             Show this help

Examples:
  nocturnusai setup                           # interactive wizard
  nocturnusai setup --ollama                  # local Ollama, no API key
  nocturnusai setup --key sk-ant-abc123...    # Anthropic Claude
  nocturnusai setup --key sk-ant-... --key sk-...  # multiple providers
  nocturnusai setup --port 8080 --dir ./ai    # custom port and directory
    """.trimIndent())
}
