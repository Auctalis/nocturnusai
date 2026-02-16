package com.axiombase.cli

fun main(args: Array<String>) {
    val parsed = parseArgs(args)
    val client = Client(
        serverUrl = parsed.server,
        database = parsed.database,
        apiKey = parsed.apiKey,
        tenantId = parsed.tenantId,
    )
    Repl(client).run()
}

private data class CliArgs(
    val server: String = "http://localhost:9300",
    val database: String = "default",
    val apiKey: String? = null,
    val tenantId: String? = null,
)

private fun parseArgs(args: Array<String>): CliArgs {
    var server = "http://localhost:9300"
    var database = "default"
    var apiKey: String? = null
    var tenantId: String? = null

    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "--server", "-s"   -> { server = args.getOrElse(i + 1) { server }; i += 2 }
            "--db", "-d"       -> { database = args.getOrElse(i + 1) { database }; i += 2 }
            "--api-key", "-k"  -> { apiKey = args.getOrElse(i + 1) { null }; i += 2 }
            "--tenant", "-t"   -> { tenantId = args.getOrElse(i + 1) { null }; i += 2 }
            "--help", "-h"     -> { printUsage(); return CliArgs() }
            else               -> { i++ }
        }
    }

    return CliArgs(server, database, apiKey, tenantId)
}

private fun printUsage() {
    println("""
AxiomBase CLI — logic server for agentic AI

Usage:
  axiombase-cli [options]

Options:
  -s, --server <url>      Server URL (default: http://localhost:9300)
  -d, --db <name>         Database name (default: default)
  -k, --api-key <key>     API key for authentication
  -t, --tenant <id>       Tenant ID for multi-tenant databases
  -h, --help              Show this help

Examples:
  axiombase-cli
  axiombase-cli --server http://prod:9300 --db mydb --api-key secret
    """.trimIndent())
}
