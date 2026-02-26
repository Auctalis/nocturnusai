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

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

// ANSI colors
private const val RESET   = "\u001B[0m"
private const val BOLD    = "\u001B[1m"
private const val DIM     = "\u001B[2m"
private const val GREEN   = "\u001B[32m"
private const val CYAN    = "\u001B[36m"
private const val YELLOW  = "\u001B[33m"
private const val RED     = "\u001B[31m"
private const val MAGENTA = "\u001B[35m"

/** Short HH:mm:ss timestamp for result headers. */
private fun timestamp(): String {
    val fmt = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())
    return fmt.format(Instant.now())
}

/** Horizontal rule sized to content. */
private fun separator(width: Int = 60) = "${DIM}${"─".repeat(width)}${RESET}"

class Repl(private val client: Client) {

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    // Lazy-init so the database-fetcher closure captures the live client.database
    private val lineReader: LineReader by lazy {
        LineReader(
            completionProvider = NaiCompletionProvider {
                try {
                    val resp = runBlocking { client.listDatabases() }
                    tryParseArray(resp).mapNotNull { it.jsonObject["name"]?.jsonPrimitive?.contentOrNull }
                } catch (_: Exception) { emptyList() }
            }
        )
    }

    /** Render the colored db/tenant prompt. */
    private fun prompt(): String {
        val db = client.database
        val tenant = client.tenantId
        return "${CYAN}${BOLD}$db${RESET}${DIM}/${RESET}${MAGENTA}$tenant${RESET}${DIM}>${RESET} "
    }

    /** Execute a single command and exit (for -e flag / scripting) */
    fun execSingle(command: String) {
        val (cmd, rest) = splitFirst(command.trim())
        try {
            when (cmd.lowercase()) {
                "ask", "?"        -> doAsk(rest)
                "tell", "+"       -> doTell(rest)
                "teach", "++"     -> doTeach(rest)
                "forget", "-"     -> doForget(rest)
                "ingest"          -> doIngest(rest)
                "inspect", "ls"   -> doInspect(rest)
                "context", "ctx"  -> doContext(rest)
                "compress"        -> doCompress()
                "cleanup"         -> doCleanup(rest)
                "dsl", "exec"     -> doDsl(rest)
                "import", "load"  -> doImport(rest)
                "export", "dump"  -> doExport(rest)
                "dbs"             -> doDbs()
                "health"          -> doHealth()
                "status"          -> doStatus()
                "setup"           -> doSetup()
                "login"           -> doLogin()
                "whoami"          -> doWhoAmI()
                "keys"            -> doKeys(rest)
                else              -> System.err.println("Unknown command: $cmd")
            }
        } catch (e: Exception) {
            System.err.println("Error: ${e.message}")
        } finally {
            client.close()
        }
    }

    fun run() {
        printBanner()

        while (true) {
            val line = lineReader.readLine(prompt())?.trim() ?: break
            if (line.isBlank()) continue

            val (cmd, rest) = splitFirst(line)
            try {
                when (cmd.lowercase()) {
                    "ask", "?"          -> doAsk(rest)
                    "tell", "+"         -> doTell(rest)
                    "teach", "++"       -> doTeach(rest)
                    "forget", "-"       -> doForget(rest)
                    "ingest"            -> doIngest(rest)
                    "inspect", "ls"     -> doInspect(rest)
                    "context", "ctx"    -> doContext(rest)
                    "compress"          -> doCompress()
                    "cleanup"           -> doCleanup(rest)
                    "dsl", "exec"       -> doDsl(rest)
                    "import", "load"    -> doImport(rest)
                    "export", "dump"    -> doExport(rest)
                    "use"               -> doUse(rest)
                    "tenant"            -> doTenant(rest)
                    "dbs"               -> doDbs()
                    "health"            -> doHealth()
                    "status"            -> doStatus()
                    "setup"             -> doSetup()
                    "login"             -> doLogin()
                    "whoami"            -> doWhoAmI()
                    "keys"              -> doKeys(rest)
                    "help", "h"         -> printHelp()
                    "clear"             -> print("\u001B[2J\u001B[H")
                    "history"           -> printHistory()
                    "exit", "quit", "q" -> { client.close(); return }
                    else                -> {
                        println("${RED}Unknown command:${RESET} $cmd")
                        println("${DIM}Type 'help' for available commands.${RESET}")
                    }
                }
            } catch (e: Exception) {
                println()
                println("${RED}${BOLD}Error${RESET}  ${e.message}")
                println(separator())
            }
        }

        client.close()
    }

    /** Show recent history entries. */
    private fun printHistory() {
        val hist = LineReader.defaultHistoryFile()
        if (!hist.exists()) { println("${DIM}No history yet.${RESET}"); return }
        val lines = hist.readLines().filter { it.isNotBlank() }.takeLast(50)
        lines.forEachIndexed { i, l ->
            println("  ${DIM}${(lines.size - lines.size + i + 1).toString().padStart(3)}${RESET}  $l")
        }
    }

    // ── commands ──

    /**
     * Smart ask — auto-detects natural language vs predicate syntax.
     *   ask mortal(?who)                → structured /ask query
     *   ask who is mortal?              → NL /synthesize
     *   ask what did Alice tell Bob?    → NL /synthesize
     */
    private fun doAsk(text: String) = runBlocking {
        require(text.isNotBlank()) { "Usage: ask mortal(?who)  OR  ask <natural language question>" }
        if (looksLikePredicate(text)) {
            val resp = client.ask(Parser.atomToJson(text))
            printResult(resp)
        } else {
            doSynthesize(text)
        }
    }

    /** NL question → /synthesize → LLM-powered answer from the knowledge base */
    private fun doSynthesize(question: String) = runBlocking {
        println("${DIM}Querying knowledge base...${RESET}")
        val resp = client.synthesize(question)
        try {
            val obj = json.parseToJsonElement(resp).jsonObject

            // Check for error
            val errCode = obj["code"]?.jsonPrimitive?.contentOrNull
            if (errCode != null) {
                printError(errCode, obj["message"]?.jsonPrimitive?.contentOrNull ?: "Unknown error")
                return@runBlocking
            }

            val answer = obj["answer"]?.jsonPrimitive?.contentOrNull ?: resp
            val confidence = obj["confidence"]?.jsonPrimitive?.floatOrNull ?: 0f
            val derivation = obj["derivation"]?.jsonArray ?: JsonArray(emptyList())
            val missing = obj["missingContext"]?.jsonPrimitive?.contentOrNull ?: ""
            val provider = obj["provider"]?.jsonPrimitive?.contentOrNull
            val model = obj["model"]?.jsonPrimitive?.contentOrNull

            println()
            println("  ${BOLD}$answer${RESET}")
            println()

            if (derivation.isNotEmpty()) {
                println("${DIM}Reasoning chain:${RESET}")
                for ((idx, step) in derivation.withIndex()) {
                    val fact = step.jsonObject["fact"]?.jsonPrimitive?.contentOrNull ?: ""
                    val type = step.jsonObject["type"]?.jsonPrimitive?.contentOrNull ?: ""
                    val rule = step.jsonObject["rule"]?.jsonPrimitive?.contentOrNull
                    val typeTag = when (type) {
                        "fact_match"       -> "${GREEN}fact${RESET}"
                        "rule_application" -> "${CYAN}rule${RESET}"
                        else               -> "${DIM}$type${RESET}"
                    }
                    val idx1 = (idx + 1).toString().padStart(2)
                    print("  ${DIM}$idx1${RESET}  $typeTag  $fact")
                    if (rule != null) print("  ${DIM}via $rule${RESET}")
                    println()
                }
            }

            if (missing.isNotBlank()) {
                println("  ${YELLOW}Missing context:${RESET} $missing")
            }

            val confStr = String.format("%.0f%%", confidence * 100)
            val confColor = when {
                confidence >= 0.8f -> GREEN
                confidence >= 0.5f -> YELLOW
                else               -> RED
            }
            val providerStr = if (model != null) "  ${DIM}$provider/$model${RESET}" else ""
            println("  ${DIM}Confidence:${RESET} ${confColor}$confStr${RESET}$providerStr  ${DIM}${timestamp()}${RESET}")

        } catch (_: Exception) {
            println(resp)
        }
    }

    private fun doTell(text: String) = runBlocking {
        require(text.isNotBlank()) { "Usage: tell predicate(arg1, arg2)" }
        if (looksLikePredicate(text)) {
            val resp = client.tell(Parser.factToJson(text))
            printOk(resp, "Fact stored")
        } else {
            // Natural language → extract and assert
            doIngest(text)
        }
    }

    private fun doTeach(text: String) = runBlocking {
        require(text.isNotBlank()) { "Usage: teach head(?x) :- body(?x)" }
        require(Parser.isRule(text)) { "Rules use :- syntax:  head(?x) :- body(?x)" }
        val resp = client.teach(Parser.ruleToJson(text))
        printOk(resp, "Rule added")
    }

    private fun doForget(text: String) = runBlocking {
        require(text.isNotBlank()) { "Usage: forget predicate(arg1, arg2)" }
        val resp = client.forget(Parser.atomToJson(text))
        printOk(resp, "Retracted")
    }

    /**
     * Ingest natural language text — LLM extracts facts & rules, auto-asserts.
     *   ingest Alice is Bob's mother. Bob has three children.
     *   ingest -f article.txt
     */
    private fun doIngest(text: String) = runBlocking {
        require(text.isNotBlank()) { "Usage: ingest <text>  OR  ingest -f <file>" }

        val inputText = if (text.startsWith("-f ")) {
            val path = text.removePrefix("-f ").trim()
            val file = File(path)
            require(file.exists()) { "File not found: $path" }
            file.readText()
        } else {
            text
        }

        println("${DIM}Extracting knowledge...${RESET}")
        val resp = client.extract(inputText, assert = true, rules = true)
        try {
            val obj = json.parseToJsonElement(resp).jsonObject

            // Check for error
            val errCode = obj["code"]?.jsonPrimitive?.contentOrNull
            if (errCode != null) {
                printError(errCode, obj["message"]?.jsonPrimitive?.contentOrNull ?: "Unknown error")
                return@runBlocking
            }

            val facts = obj["facts"]?.jsonArray ?: JsonArray(emptyList())
            val rules = obj["rules"]?.jsonArray ?: JsonArray(emptyList())
            val asserted = obj["asserted"]?.jsonPrimitive?.booleanOrNull ?: false
            val provider = obj["provider"]?.jsonPrimitive?.contentOrNull
            val model = obj["model"]?.jsonPrimitive?.contentOrNull

            if (facts.isEmpty() && rules.isEmpty()) {
                println("${YELLOW}No facts extracted.${RESET}")
                return@runBlocking
            }

            for (f in facts) {
                val pred = f.jsonObject["predicate"]?.jsonPrimitive?.contentOrNull ?: "?"
                val args = f.jsonObject["args"]?.jsonArray?.joinToString(", ") { it.jsonPrimitive.content } ?: ""
                val conf = f.jsonObject["confidence"]?.jsonPrimitive?.floatOrNull ?: 0f
                val confStr = String.format("%.0f%%", conf * 100)
                println("  ${GREEN}+${RESET} $CYAN$pred$RESET($args)  ${DIM}$confStr${RESET}")
            }

            for (r in rules) {
                val head = r.jsonObject["head"]?.jsonObject
                val body = r.jsonObject["body"]?.jsonArray
                if (head != null) {
                    val headStr = formatAtomPlain(head)
                    val bodyStr = body?.joinToString(", ") { formatAtomPlain(it) } ?: ""
                    val conf = r.jsonObject["confidence"]?.jsonPrimitive?.floatOrNull ?: 0f
                    val confStr = String.format("%.0f%%", conf * 100)
                    println("  ${GREEN}+${RESET} $headStr :- $bodyStr  ${DIM}$confStr${RESET}")
                }
            }

            val status = if (asserted) "${GREEN}asserted${RESET}" else "${YELLOW}extracted only${RESET}"
            val providerStr = if (model != null) "  ${DIM}via $provider/$model${RESET}" else ""
            println("${facts.size} facts, ${rules.size} rules — $status$providerStr")

        } catch (_: Exception) {
            println(resp)
        }
    }

    private fun doInspect(filter: String) = runBlocking {
        val factsRaw = client.listFacts()
        val rulesRaw = client.listRules()
        val facts = tryParseArray(factsRaw)
        val rules = tryParseArray(rulesRaw)

        val filterLower = filter.lowercase()
        val filterLabel = if (filterLower.isBlank()) "" else "  ${DIM}filter: $filterLower${RESET}"

        var factCount = 0
        var ruleCount = 0

        for (f in facts) {
            val line = formatAtom(f)
            if (filterLower.isBlank() || line.lowercase().contains(filterLower)) {
                println("  ${GREEN}fact${RESET}  $line")
                factCount++
            }
        }
        for (r in rules) {
            val line = formatRule(r)
            if (filterLower.isBlank() || line.lowercase().contains(filterLower)) {
                println("  ${CYAN}rule${RESET}  $line")
                ruleCount++
            }
        }

        println()
        val total = factCount + ruleCount
        if (total == 0) {
            println("${DIM}No knowledge stored yet.${RESET}")
        } else {
            println("${DIM}$factCount fact(s), $ruleCount rule(s)$filterLabel — ${timestamp()}${RESET}")
        }
    }

    private fun doContext(text: String) = runBlocking {
        val max = text.toIntOrNull() ?: 50
        val resp = client.contextWindow(max)
        val obj = json.parseToJsonElement(resp).jsonObject
        val scored = obj["facts"]?.jsonArray ?: run {
            println(resp)
            return@runBlocking
        }
        val total = obj["totalAvailable"]?.jsonPrimitive?.intOrNull ?: scored.size

        println("${BOLD}Context Window${RESET}  ${DIM}${scored.size} of $total (by salience) — ${timestamp()}${RESET}")
        println(separator())
        for (s in scored) {
            val salience = s.jsonObject["salience"]?.jsonPrimitive?.doubleOrNull ?: 0.0
            val atom = s.jsonObject["atom"]?.jsonObject
            if (atom != null) {
                val line = formatAtom(atom)
                val salienceColor = when {
                    salience >= 0.7 -> GREEN
                    salience >= 0.4 -> YELLOW
                    else            -> DIM
                }
                val bar = buildSalienceBar(salience)
                println("  $salienceColor${String.format("%.3f", salience)}${RESET}  $bar  $line")
            }
        }
        println(separator())
    }

    /** Renders a compact 5-char salience bar like [████░] */
    private fun buildSalienceBar(salience: Double): String {
        val filled = (salience * 5).toInt().coerceIn(0, 5)
        val bar = "█".repeat(filled) + "░".repeat(5 - filled)
        return "${DIM}[$bar]${RESET}"
    }

    private fun doCompress() = runBlocking {
        val resp = client.compress()
        val obj = json.parseToJsonElement(resp).jsonObject
        val consolidated = obj["factsConsolidated"]?.jsonPrimitive?.intOrNull ?: 0
        val newFacts = obj["newFacts"]?.jsonArray?.size ?: 0
        println("${GREEN}Compressed$RESET — $consolidated consolidated, $newFacts new summary facts")
    }

    private fun doCleanup(text: String) = runBlocking {
        val threshold = text.toDoubleOrNull() ?: 0.05
        val resp = client.cleanup(threshold)
        val obj = json.parseToJsonElement(resp).jsonObject
        val expired = obj["expiredCount"]?.jsonPrimitive?.intOrNull ?: 0
        val evicted = obj["evictedCount"]?.jsonPrimitive?.intOrNull ?: 0
        println("${GREEN}Cleanup$RESET — $expired expired, $evicted evicted (threshold=$threshold)")
    }

    private fun doDsl(text: String) = runBlocking {
        require(text.isNotBlank()) { "Usage: dsl ASSERT human(socrates)." }
        val resp = client.execute(text)
        println(resp)
    }

    private fun doImport(path: String) = runBlocking {
        require(path.isNotBlank()) { "Usage: import <file.ab>" }
        val file = File(path)
        require(file.exists()) { "File not found: $path" }

        val lines = file.readLines()
            .map { it.trim() }
            .filter { it.isNotBlank() && !it.startsWith("#") }

        var facts = 0
        var rules = 0
        var errors = 0

        for ((idx, line) in lines.withIndex()) {
            try {
                if (Parser.isRule(line)) {
                    client.teach(Parser.ruleToJson(line))
                    rules++
                } else {
                    client.tell(Parser.factToJson(line))
                    facts++
                }
            } catch (e: Exception) {
                errors++
                println("  ${RED}line ${idx + 1}: ${e.message}$RESET  $DIM$line$RESET")
            }
        }

        println("${GREEN}Imported$RESET — $facts facts, $rules rules" +
            if (errors > 0) ", ${RED}$errors errors$RESET" else "")
    }

    private fun doExport(path: String) = runBlocking {
        val factsRaw = client.listFacts()
        val rulesRaw = client.listRules()
        val facts = tryParseArray(factsRaw)
        val rules = tryParseArray(rulesRaw)

        val sb = StringBuilder()
        sb.appendLine("# NocturnusAI knowledge dump — ${client.database}")
        sb.appendLine("# ${java.time.Instant.now()}")
        sb.appendLine()

        if (facts.isNotEmpty()) {
            sb.appendLine("# Facts")
            for (f in facts) {
                sb.appendLine(formatAtomPlain(f))
            }
            sb.appendLine()
        }

        if (rules.isNotEmpty()) {
            sb.appendLine("# Rules")
            for (r in rules) {
                sb.appendLine(formatRulePlain(r))
            }
            sb.appendLine()
        }

        val text = sb.toString()

        if (path.isBlank()) {
            // Print to stdout
            println(text)
        } else {
            File(path).writeText(text)
            println("${GREEN}Exported$RESET — ${facts.size} facts, ${rules.size} rules → $path")
        }
    }

    private fun doUse(name: String) {
        require(name.isNotBlank()) { "Usage: use <database> or use <database>/<tenant>" }
        if ('/' in name) {
            val (db, tenant) = name.split("/", limit = 2)
            client.database = db
            client.tenantId = tenant.ifBlank { "default" }
            println("${GREEN}Switched to ${BOLD}${client.database}${RESET}${DIM}/${RESET}${MAGENTA}${client.tenantId}${RESET}")
        } else {
            client.database = name
            println("${GREEN}Switched to ${BOLD}$name${RESET}${DIM}/${RESET}${MAGENTA}${client.tenantId}${RESET}")
        }
    }

    private fun doTenant(name: String) {
        if (name.isBlank()) {
            println("${DIM}Current tenant:${RESET} ${MAGENTA}${BOLD}${client.tenantId}${RESET}")
            println("${DIM}Usage: tenant <name>  (e.g., tenant alice)${RESET}")
            return
        }
        client.tenantId = name
        println("${GREEN}Tenant set to ${MAGENTA}${BOLD}$name${RESET}")
    }

    private fun doDbs() = runBlocking {
        val resp = client.listDatabases()
        val arr = tryParseArray(resp)
        if (arr.isEmpty()) {
            println("${DIM}No databases.${RESET}")
        } else {
            for (db in arr) {
                val name = db.jsonObject["name"]?.jsonPrimitive?.contentOrNull ?: db.toString()
                val marker = if (name == client.database) " ${GREEN}<-${RESET}" else ""
                println("  $name$marker")
            }
        }
    }

    private fun doHealth() = runBlocking {
        val resp = client.health()
        try {
            val obj = json.parseToJsonElement(resp).jsonObject
            val status = obj["status"]?.jsonPrimitive?.contentOrNull ?: "unknown"
            val statusColor = when (status) {
                "healthy" -> GREEN
                "degraded" -> YELLOW
                else -> RED
            }
            println("${statusColor}$status${RESET}")
            val checks = obj["checks"]?.jsonObject
            if (checks != null) {
                for ((name, check) in checks) {
                    val checkStatus = check.jsonObject["status"]?.jsonPrimitive?.contentOrNull ?: "?"
                    val checkColor = when (checkStatus) {
                        "pass" -> GREEN
                        "warn" -> YELLOW
                        else -> RED
                    }
                    val detail = check.jsonObject["message"]?.jsonPrimitive?.contentOrNull ?: ""
                    println("  ${checkColor}$checkStatus${RESET}  $name  ${DIM}$detail${RESET}")
                }
            }
        } catch (_: Exception) {
            println(resp)
        }
    }

    /** Show server status: health, LLM provider, databases, features */
    private fun doStatus() = runBlocking {
        println("${BOLD}NocturnusAI Status${RESET}")
        println("${DIM}Server: ${client.server}${RESET}")
        println("${DIM}Database: ${client.database}${RESET}")
        println()

        // Health
        try {
            val healthResp = client.health()
            val health = json.parseToJsonElement(healthResp).jsonObject
            val status = health["status"]?.jsonPrimitive?.contentOrNull ?: "unknown"
            val statusColor = when (status) { "healthy" -> GREEN; "degraded" -> YELLOW; else -> RED }
            println("  Health:     ${statusColor}$status${RESET}")
        } catch (e: Exception) {
            println("  Health:     ${RED}unreachable (${e.message})${RESET}")
        }

        // LLM — check via extract endpoint probe
        try {
            val extractResp = client.extract("test", assert = false, rules = false)
            val extractObj = json.parseToJsonElement(extractResp).jsonObject
            val provider = extractObj["provider"]?.jsonPrimitive?.contentOrNull
            val model = extractObj["model"]?.jsonPrimitive?.contentOrNull
            val errCode = extractObj["code"]?.jsonPrimitive?.contentOrNull
            if (errCode != null) {
                val msg = extractObj["message"]?.jsonPrimitive?.contentOrNull ?: ""
                when (errCode) {
                    "LLM_NOT_CONFIGURED" -> println("  LLM:        ${YELLOW}not configured${RESET}  ${DIM}(set API key in .env)${RESET}")
                    "EXTRACTION_DISABLED" -> println("  Extraction: ${YELLOW}disabled${RESET}  ${DIM}(set EXTRACTION_ENABLED=true)${RESET}")
                    else -> println("  LLM:        ${YELLOW}$errCode${RESET}  ${DIM}$msg${RESET}")
                }
            } else if (provider != null) {
                println("  LLM:        ${GREEN}$provider/$model${RESET}")
                println("  Extraction: ${GREEN}enabled${RESET}")
            }
        } catch (_: Exception) {
            println("  LLM:        ${DIM}unknown${RESET}")
        }

        // Databases
        try {
            val dbsResp = client.listDatabases()
            val dbs = tryParseArray(dbsResp)
            println("  Databases:  ${dbs.size}")
        } catch (_: Exception) {}

        // Auth status
        try {
            val authResp = client.authStatus()
            val auth = json.parseToJsonElement(authResp).jsonObject
            val mode = auth["mode"]?.jsonPrimitive?.contentOrNull ?: "unknown"
            val keyCount = auth["keyCount"]?.jsonPrimitive?.intOrNull ?: 0
            val modeColor = when (mode) {
                "rbac" -> GREEN
                "legacy" -> CYAN
                else -> YELLOW
            }
            val modeLabel = when (mode) {
                "rbac" -> "RBAC ($keyCount keys)"
                "legacy" -> "legacy (single key)"
                else -> "disabled (dev mode)"
            }
            println("  Auth:       ${modeColor}$modeLabel${RESET}")
        } catch (_: Exception) {}

        // Facts/rules in current db
        try {
            val facts = tryParseArray(client.listFacts())
            val rules = tryParseArray(client.listRules())
            println("  Knowledge:  ${facts.size} facts, ${rules.size} rules (in ${client.database})")
        } catch (_: Exception) {}

        println()
    }

    /** Interactive setup — configure LLM keys and server options */
    private fun doSetup() {
        println()
        println("${BOLD}NocturnusAI Setup${RESET}")
        println("${DIM}Configure your LLM provider for natural language features.${RESET}")
        println()
        println("  1) Ollama (local, free, private)")
        println("  2) Anthropic Claude")
        println("  3) OpenAI GPT")
        println("  4) Google Gemini")
        println("  5) Custom (any OpenAI-compatible endpoint)")
        println("  q) Cancel")
        println()
        print("Choice [1]: ")

        val choice = readlnOrNull()?.trim() ?: return
        if (choice == "q") return

        val envLines = mutableListOf<String>()

        when (choice.ifBlank { "1" }) {
            "1" -> {
                println()
                println("${GREEN}Ollama selected.${RESET}")
                println("${DIM}Make sure Ollama is running: ollama serve${RESET}")
                println("${DIM}Or start with Docker: make up-ollama${RESET}")
                print("Model [llama3.2]: ")
                val model = readlnOrNull()?.trim()?.ifBlank { "llama3.2" } ?: "llama3.2"
                print("Ollama URL [http://localhost:11434/v1]: ")
                val url = readlnOrNull()?.trim()?.ifBlank { "http://localhost:11434/v1" } ?: "http://localhost:11434/v1"
                envLines.add("LLM_PROVIDER=ollama")
                envLines.add("LLM_MODEL=$model")
                envLines.add("LLM_BASE_URL=$url")
            }
            "2" -> {
                print("Anthropic API key (sk-ant-...): ")
                val key = readlnOrNull()?.trim() ?: return
                if (key.isBlank()) { println("${RED}No key entered.${RESET}"); return }
                envLines.add("ANTHROPIC_API_KEY=$key")
            }
            "3" -> {
                print("OpenAI API key (sk-...): ")
                val key = readlnOrNull()?.trim() ?: return
                if (key.isBlank()) { println("${RED}No key entered.${RESET}"); return }
                envLines.add("OPENAI_API_KEY=$key")
            }
            "4" -> {
                print("Google API key (AIza...): ")
                val key = readlnOrNull()?.trim() ?: return
                if (key.isBlank()) { println("${RED}No key entered.${RESET}"); return }
                envLines.add("GOOGLE_API_KEY=$key")
            }
            "5" -> {
                print("Base URL (e.g. https://api.groq.com/openai/v1): ")
                val url = readlnOrNull()?.trim() ?: return
                print("Model name: ")
                val model = readlnOrNull()?.trim() ?: return
                print("API key (leave blank if none): ")
                val key = readlnOrNull()?.trim() ?: ""
                envLines.add("LLM_PROVIDER=custom")
                envLines.add("LLM_BASE_URL=$url")
                envLines.add("LLM_MODEL=$model")
                if (key.isNotBlank()) envLines.add("LLM_API_KEY=$key")
            }
            else -> { println("${RED}Invalid choice.${RESET}"); return }
        }

        envLines.add("EXTRACTION_ENABLED=true")

        println()
        println("${BOLD}Configuration:${RESET}")
        for (line in envLines) {
            val parts = line.split("=", limit = 2)
            val value = if (parts[0].contains("KEY") && parts[1].length > 8) {
                parts[1].take(6) + "..." + parts[1].takeLast(4)
            } else parts[1]
            println("  ${CYAN}${parts[0]}${RESET} = $value")
        }
        println()
        println("${DIM}Add these to your server's .env file, then restart the server.${RESET}")
        println("${DIM}If using Docker: edit .env then run 'docker compose restart'${RESET}")

        // Try to write to .env if it exists in current directory or parent
        val envFile = listOf(File(".env"), File("../.env"), File(System.getProperty("user.dir", ".") + "/.env"))
            .firstOrNull { it.exists() }

        if (envFile != null) {
            println()
            print("Write to ${envFile.absolutePath}? [y/N]: ")
            val confirm = readlnOrNull()?.trim()?.lowercase()
            if (confirm == "y" || confirm == "yes") {
                val existing = envFile.readText()
                val newContent = StringBuilder(existing)
                if (!existing.endsWith("\n")) newContent.append("\n")
                newContent.append("\n# Added by 'setup' command\n")
                for (line in envLines) {
                    newContent.appendLine(line)
                }
                envFile.writeText(newContent.toString())
                println("${GREEN}Written!${RESET} Restart the server for changes to take effect.")
            }
        }
        println()
    }

    // ── auth commands ──

    /** Bootstrap or login: create first admin key or show auth status */
    private fun doLogin() = runBlocking {
        println()
        println("${BOLD}NocturnusAI Auth${RESET}")

        // Check current auth status
        try {
            val statusResp = client.authStatus()
            val obj = json.parseToJsonElement(statusResp).jsonObject
            val mode = obj["mode"]?.jsonPrimitive?.contentOrNull ?: "unknown"
            val bootstrapRequired = obj["bootstrapRequired"]?.jsonPrimitive?.booleanOrNull ?: false
            val keyCount = obj["keyCount"]?.jsonPrimitive?.intOrNull ?: 0

            println("  Mode: $CYAN$mode$RESET")

            when {
                mode == "disabled" -> {
                    println("${YELLOW}Auth is disabled (dev mode).${RESET}")
                    println("${DIM}To enable: set AUTH_ENABLED=true in .env and restart.${RESET}")
                }
                mode == "legacy" -> {
                    println("${YELLOW}Legacy single-key auth.${RESET}")
                    println("${DIM}To upgrade: set AUTH_ENABLED=true in .env and restart.${RESET}")
                }
                bootstrapRequired -> {
                    println("${GREEN}RBAC auth enabled — no keys yet.${RESET}")
                    println("${DIM}Let's create your first admin key.${RESET}")
                    println()

                    print("Admin username [admin]: ")
                    val user = readlnOrNull()?.trim()?.ifBlank { "admin" } ?: "admin"
                    print("Admin password: ")
                    val pass = readlnOrNull()?.trim() ?: return@runBlocking
                    if (pass.isBlank()) { println("${RED}Password required.${RESET}"); return@runBlocking }
                    print("Key name [admin]: ")
                    val keyName = readlnOrNull()?.trim()?.ifBlank { "admin" } ?: "admin"

                    val resp = client.authBootstrap(user, pass, keyName)
                    val respObj = json.parseToJsonElement(resp).jsonObject

                    val errCode = respObj["code"]?.jsonPrimitive?.contentOrNull
                    if (errCode != null) {
                        val errMsg = respObj["message"]?.jsonPrimitive?.contentOrNull ?: ""
                        println("${RED}$errCode: $errMsg${RESET}")
                        return@runBlocking
                    }

                    val key = respObj["key"]?.jsonPrimitive?.contentOrNull
                    val prefix = respObj["prefix"]?.jsonPrimitive?.contentOrNull
                    val id = respObj["id"]?.jsonPrimitive?.contentOrNull

                    println()
                    println("${GREEN}Admin key created!${RESET}")
                    println()
                    println("  ${BOLD}API Key:${RESET}  $key")
                    println("  ${DIM}Prefix:   $prefix${RESET}")
                    println("  ${DIM}ID:       $id${RESET}")
                    println()
                    println("${YELLOW}Save this key — it won't be shown again.${RESET}")
                    println("${DIM}Use it with: --api-key $key${RESET}")
                    println("${DIM}Or set in .env: API_KEY=$key${RESET}")
                }
                else -> {
                    println("${GREEN}RBAC auth active — $keyCount key(s) configured.${RESET}")
                    println("${DIM}Use 'whoami' to check your current identity.${RESET}")
                    println("${DIM}Use 'keys list' to manage keys.${RESET}")
                }
            }
        } catch (e: Exception) {
            // Server may not support auth routes yet
            println("${RED}Error: ${e.message}${RESET}")
            println("${DIM}Is the server running and up to date?${RESET}")
        }
        println()
    }

    /** Show current key identity and permissions */
    private fun doWhoAmI() = runBlocking {
        val resp = client.authWhoAmI()
        try {
            val obj = json.parseToJsonElement(resp).jsonObject
            val errCode = obj["code"]?.jsonPrimitive?.contentOrNull
            if (errCode != null) {
                println("${RED}${obj["message"]?.jsonPrimitive?.contentOrNull ?: errCode}${RESET}")
                return@runBlocking
            }

            val mode = obj["mode"]?.jsonPrimitive?.contentOrNull ?: "?"
            val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: "?"
            val role = obj["role"]?.jsonPrimitive?.contentOrNull ?: "?"
            val perms = obj["permissions"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
            val dbs = obj["databases"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
            val tenants = obj["tenants"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()

            val roleColor = when (role) { "admin" -> GREEN; "writer" -> CYAN; else -> DIM }
            println("  Mode:     $mode")
            println("  Name:     $BOLD$name$RESET")
            println("  Role:     $roleColor$role$RESET")
            println("  DBs:      ${if (dbs.isEmpty()) "${DIM}all${RESET}" else dbs.joinToString(", ")}")
            println("  Tenants:  ${if (tenants.isEmpty()) "${DIM}all${RESET}" else tenants.joinToString(", ")}")
            println("  Perms:    ${DIM}${perms.joinToString(", ")}${RESET}")
        } catch (_: Exception) {
            println(resp)
        }
    }

    /** Key management: keys list, keys create <name> <role>, keys revoke <id> */
    private fun doKeys(args: String) = runBlocking {
        val (subcmd, rest) = splitFirst(args.ifBlank { "list" })

        when (subcmd.lowercase()) {
            "list", "ls" -> {
                val resp = client.authListKeys()
                try {
                    val arr = json.parseToJsonElement(resp).jsonArray
                    if (arr.isEmpty()) {
                        println("${DIM}No keys.${RESET}")
                        return@runBlocking
                    }
                    println("${BOLD}API Keys:${RESET}")
                    for (k in arr) {
                        val obj = k.jsonObject
                        val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: "?"
                        val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: "?"
                        val prefix = obj["prefix"]?.jsonPrimitive?.contentOrNull ?: "?"
                        val role = obj["role"]?.jsonPrimitive?.contentOrNull ?: "?"
                        val enabled = obj["enabled"]?.jsonPrimitive?.booleanOrNull ?: true
                        val dbs = obj["databases"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()

                        val roleColor = when (role) { "admin" -> GREEN; "writer" -> CYAN; else -> DIM }
                        val statusStr = if (enabled) "" else " ${RED}[disabled]${RESET}"
                        val dbStr = if (dbs.isEmpty()) "" else " ${DIM}dbs=${dbs.joinToString(",")}${RESET}"
                        println("  $prefix...  $roleColor$role$RESET  $name$statusStr$dbStr  ${DIM}$id${RESET}")
                    }
                } catch (_: Exception) {
                    println(resp)
                }
            }

            "create", "new" -> {
                val parts = rest.split(" ", limit = 2)
                val name = parts.getOrNull(0)?.takeIf { it.isNotBlank() }
                val role = parts.getOrNull(1)?.takeIf { it.isNotBlank() } ?: "writer"

                if (name == null) {
                    println("Usage: keys create <name> [role]")
                    println("  Roles: admin, writer (default), reader")
                    return@runBlocking
                }

                val resp = client.authCreateKey(name, role)
                try {
                    val obj = json.parseToJsonElement(resp).jsonObject
                    val errCode = obj["code"]?.jsonPrimitive?.contentOrNull
                    if (errCode != null) {
                        println("${RED}${obj["message"]?.jsonPrimitive?.contentOrNull ?: errCode}${RESET}")
                        return@runBlocking
                    }

                    val key = obj["key"]?.jsonPrimitive?.contentOrNull
                    val prefix = obj["prefix"]?.jsonPrimitive?.contentOrNull
                    println("${GREEN}Key created:${RESET}")
                    println("  Key:    $key")
                    println("  Prefix: $prefix")
                    println("  Role:   $role")
                    println("${YELLOW}Save this key — it won't be shown again.${RESET}")
                } catch (_: Exception) {
                    println(resp)
                }
            }

            "revoke", "delete", "rm" -> {
                if (rest.isBlank()) {
                    println("Usage: keys revoke <key-id>")
                    return@runBlocking
                }
                val resp = client.authRevokeKey(rest.trim())
                try {
                    val obj = json.parseToJsonElement(resp).jsonObject
                    val errCode = obj["code"]?.jsonPrimitive?.contentOrNull
                    if (errCode != null) {
                        println("${RED}${obj["message"]?.jsonPrimitive?.contentOrNull ?: errCode}${RESET}")
                    } else {
                        println("${GREEN}Key revoked.${RESET}")
                    }
                } catch (_: Exception) {
                    println(resp)
                }
            }

            else -> {
                println("Usage: keys <list|create|revoke>")
                println("  keys list              — show all keys")
                println("  keys create <name> [role] — create a new key")
                println("  keys revoke <id>       — revoke a key")
            }
        }
    }

    // ── formatting ──

    private fun formatAtom(obj: JsonObject): String {
        val pred = obj["predicate"]?.jsonPrimitive?.contentOrNull ?: "?"
        val args = obj["args"]?.jsonArray?.joinToString(", ") { it.jsonPrimitive.content } ?: ""
        val neg = if (obj["negated"]?.jsonPrimitive?.booleanOrNull == true) "NOT " else ""
        return "$CYAN$neg$pred$RESET($args)"
    }

    private fun formatAtom(el: JsonElement): String {
        return if (el is JsonObject) formatAtom(el) else el.toString()
    }

    private fun formatRule(el: JsonElement): String {
        if (el !is JsonObject) return el.toString()
        val head = el["head"]?.jsonObject
        val body = el["body"]?.jsonArray
        if (head == null) return el.toString()
        val headStr = formatAtom(head)
        val bodyStr = body?.joinToString(", ") { formatAtom(it) } ?: ""
        return "$headStr :- $bodyStr"
    }

    /** Plain text atom — no ANSI, for file export */
    private fun formatAtomPlain(el: JsonElement): String {
        if (el !is JsonObject) return el.toString()
        val pred = el["predicate"]?.jsonPrimitive?.contentOrNull ?: "?"
        val args = el["args"]?.jsonArray?.joinToString(", ") { it.jsonPrimitive.content } ?: ""
        val neg = if (el["negated"]?.jsonPrimitive?.booleanOrNull == true) "NOT " else ""
        return if (args.isEmpty()) "$neg$pred" else "$neg$pred($args)"
    }

    /** Plain text rule — no ANSI, for file export */
    private fun formatRulePlain(el: JsonElement): String {
        if (el !is JsonObject) return el.toString()
        val head = el["head"]?.jsonObject ?: return el.toString()
        val body = el["body"]?.jsonArray ?: return formatAtomPlain(head)
        val headStr = formatAtomPlain(head)
        val bodyStr = body.joinToString(", ") { formatAtomPlain(it) }
        return "$headStr :- $bodyStr"
    }

    private fun tryParseArray(raw: String): List<JsonElement> {
        return try {
            json.parseToJsonElement(raw).jsonArray.toList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun printResult(raw: String) {
        // Try to parse as array of bindings/atoms
        try {
            val el = json.parseToJsonElement(raw)
            when (el) {
                is JsonObject -> {
                    // Check for error response (e.g. UNAUTHORIZED)
                    val errCode = el["code"]?.jsonPrimitive?.contentOrNull
                    if (errCode != null) {
                        printError(errCode, el["message"]?.jsonPrimitive?.contentOrNull ?: "Unknown error")
                    } else {
                        println(raw)
                    }
                }
                is JsonArray -> {
                    if (el.isEmpty()) {
                        println("${YELLOW}No results.${RESET}")
                        return
                    }
                    for (item in el) {
                        when (item) {
                            is JsonObject -> {
                                if (item.containsKey("predicate")) {
                                    println("  ${formatAtom(item)}")
                                } else {
                                    // Binding map
                                    val bindings = item.entries.joinToString(", ") { (k, v) ->
                                        "$CYAN$k$RESET = ${v.jsonPrimitive.content}"
                                    }
                                    println("  $bindings")
                                }
                            }
                            else -> println("  $item")
                        }
                    }
                    println()
                    println("${DIM}${el.size} result(s) — ${timestamp()}${RESET}")
                }
                is JsonPrimitive -> println(el.content)
                else -> println(raw)
            }
        } catch (_: Exception) {
            println(raw)
        }
    }

    private fun printOk(raw: String, defaultMsg: String) {
        try {
            val el = json.parseToJsonElement(raw)
            if (el is JsonObject) {
                // Check for error response (e.g. UNAUTHORIZED)
                val errCode = el["code"]?.jsonPrimitive?.contentOrNull
                if (errCode != null) {
                    printError(errCode, el["message"]?.jsonPrimitive?.contentOrNull ?: "Unknown error")
                    return
                }
                val msg = el["message"]?.jsonPrimitive?.contentOrNull
                    ?: el["status"]?.jsonPrimitive?.contentOrNull
                println("${GREEN}OK$RESET ${msg ?: defaultMsg}")
                return
            }
        } catch (_: Exception) {}
        println("${GREEN}OK$RESET $defaultMsg")
    }

    /** Print error with actionable guidance for UNAUTHORIZED. */
    private fun printError(code: String, message: String) {
        println("${RED}$code: $message${RESET}")
        if (code == "UNAUTHORIZED") {
            if (!client.hasApiKey) {
                println("${DIM}No API key configured. Fix with one of:${RESET}")
                println("${DIM}  nocturnusai setup            # re-run setup wizard${RESET}")
                println("${DIM}  nocturnusai --api-key <key>  # pass key on command line${RESET}")
            } else {
                println("${DIM}API key was sent but rejected. Check your key is correct.${RESET}")
            }
        }
    }

    // ── help ──

    private fun printBanner() {
        println()
        println("${BOLD}${CYAN}NocturnusAI${RESET} ${DIM}v${BuildInfo.version}${RESET}  ${DIM}logic server for agentic AI${RESET}")
        println(separator())
        println("  ${DIM}Server  ${RESET}  ${client.server}")
        println("  ${DIM}Database${RESET}  ${CYAN}${BOLD}${client.database}${RESET}  ${DIM}(use <name> to switch)${RESET}")
        println("  ${DIM}Tenant  ${RESET}  ${MAGENTA}${BOLD}${client.tenantId}${RESET}  ${DIM}(tenant <name> to switch)${RESET}")
        if (client.hasApiKey) {
            println("  ${DIM}Auth    ${RESET}  ${GREEN}API key configured${RESET}")
        }
        print("  ${DIM}Status  ${RESET}  ")

        // Quick connectivity check
        try {
            val resp = runBlocking { client.health() }
            val obj = json.parseToJsonElement(resp).jsonObject
            val status = obj["status"]?.jsonPrimitive?.contentOrNull ?: "unknown"
            when (status) {
                "healthy"  -> println("${GREEN}connected${RESET}")
                "degraded" -> println("${YELLOW}connected (degraded)${RESET}")
                else       -> println("${RED}$status${RESET}")
            }

            // Check if server requires auth but we have no key
            if (!client.hasApiKey) {
                try {
                    val authResp = runBlocking { client.authStatus() }
                    val authObj = json.parseToJsonElement(authResp).jsonObject
                    val authEnabled = authObj["authEnabled"]?.jsonPrimitive?.booleanOrNull ?: false
                    val legacyKey = authObj["legacyApiKeySet"]?.jsonPrimitive?.booleanOrNull ?: false
                    if (authEnabled || legacyKey) {
                        println()
                        println("  ${YELLOW}Warning:${RESET} server requires auth but no API key is set.")
                        println("  ${DIM}Run 'setup' or use --api-key flag.${RESET}")
                    }
                } catch (_: Exception) {}
            }
        } catch (e: Exception) {
            println("${RED}unreachable${RESET}  ${DIM}(${e.message})${RESET}")
            println()
            println("  ${DIM}Is the server running?  nocturnusai setup${RESET}")
        }

        println(separator())
        println("  ${DIM}Tab for completion  •  Arrow keys for history  •  type 'help'${RESET}")
        println()
    }

    private fun printHelp() {
        println("""
${BOLD}Quick start:${RESET}
  tell human(socrates)              Store a fact
  teach mortal(?x) :- human(?x)    Define a rule
  ask mortal(?who)                  Query  →  ?who = socrates
  inspect                           Browse all stored knowledge

${BOLD}Core commands:${RESET}
  tell  <pred(args)>                Store a fact              ${DIM}shortcut: +${RESET}
  teach <head :- body>              Define a rule             ${DIM}shortcut: ++${RESET}
  ask   <pred(?var)>                Query with unification    ${DIM}shortcut: ?${RESET}
  forget <pred(args)>               Retract a fact            ${DIM}shortcut: -${RESET}

${BOLD}Explore knowledge:${RESET}
  inspect [filter]                  List facts & rules        ${DIM}shortcut: ls${RESET}
  context [max]                     Salience-ranked context   ${DIM}shortcut: ctx${RESET}

${BOLD}Natural language  ${DIM}(requires LLM — run 'status' to check)${RESET}${BOLD}:${RESET}
  ingest <text>                     Extract facts from text via LLM
  ingest -f <file>                  Extract from file
  ask <plain English question>      LLM-powered Q&A from the knowledge base
  tell <plain English statement>    LLM-powered extraction + assert

${BOLD}Memory management:${RESET}
  compress                          Consolidate episodic facts → semantic
  cleanup [threshold]               Evict expired / low-salience facts

${BOLD}Import / Export:${RESET}
  import <file.ab>                  Load facts & rules from file
  export [file.ab]                  Dump knowledge (to file or stdout)
  dsl <statement>                   Execute raw Logiql DSL

${BOLD}Admin:${RESET}
  use <db>                            Switch database
  use <db>/<tenant>                   Switch database and tenant at once
  tenant <name>                       Switch tenant
  dbs                                 List databases
  health    status    setup           Server info & setup wizard
  clear       Clear screen
  history     Show recent command history

${BOLD}Auth:${RESET}
  login    whoami    keys list    keys create <name> [role]    keys revoke <id>

${BOLD}Navigation:${RESET}
  Tab           Complete command or show hints
  Up / Down     Browse history
  Ctrl+A/E      Move to start/end of line
  Ctrl+U        Clear line
  Ctrl+W        Delete word
  Ctrl+R        (coming soon) reverse search
  q / exit      Quit

  ${DIM}exec / dsl = raw DSL    h / help = this message${RESET}
        """.trimIndent())
    }

    /**
     * Heuristic: does this look like predicate syntax or natural language?
     *   "mortal(?who)"         → true  (has parens with args)
     *   "likes(alice, bob)"    → true
     *   "who is mortal?"       → false (spaces, no predicate parens)
     *   "what did Trump say"   → false
     */
    private fun looksLikePredicate(text: String): Boolean {
        val trimmed = text.trim()
        // Contains predicate-style parens: word( ... )
        return Regex("""^(NOT\s+)?[A-Za-z_]\w*\(.*\)$""", RegexOption.IGNORE_CASE).matches(trimmed)
    }

    private fun splitFirst(line: String): Pair<String, String> {
        val idx = line.indexOfFirst { it.isWhitespace() }
        return if (idx == -1) line to "" else line.substring(0, idx) to line.substring(idx + 1).trim()
    }
}
