package com.nocturnusai.cli

fun main(args: Array<String>) {
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
            "--help", "-h"     -> { printUsage(); return CliArgs() }
            else               -> { i++ }
        }
    }

    return CliArgs(server, database, apiKey, tenantId, exec)
}

private fun printUsage() {
    println("""
NocturnusAI CLI — logic server for agentic AI

Usage:
  nocturnusai-cli [options]             Interactive REPL
  nocturnusai-cli -e "tell likes(a,b)"  Run one command and exit
  cat kb.ab | nocturnusai-cli -e "import /dev/stdin"

Options:
  -s, --server <url>      Server URL (default: http://localhost:9300)
  -d, --db <name>         Database name (default: default)
  -k, --api-key <key>     API key for authentication
  -t, --tenant <id>       Tenant ID for multi-tenant databases
  -e, --exec <command>    Execute a single command and exit
  -h, --help              Show this help

Examples:
  nocturnusai-cli
  nocturnusai-cli -d mydb -e "tell human(socrates)"
  nocturnusai-cli -d mydb -e "ask mortal(?who)"
  nocturnusai-cli -d mydb -e "export"
  nocturnusai-cli -d mydb -e "import knowledge.ab"
    """.trimIndent())
}
