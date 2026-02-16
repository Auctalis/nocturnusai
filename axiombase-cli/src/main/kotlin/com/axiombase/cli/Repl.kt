package com.axiombase.cli

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.io.File

// ANSI colors
private const val RESET  = "\u001B[0m"
private const val BOLD   = "\u001B[1m"
private const val DIM    = "\u001B[2m"
private const val GREEN  = "\u001B[32m"
private const val CYAN   = "\u001B[36m"
private const val YELLOW = "\u001B[33m"
private const val RED    = "\u001B[31m"

class Repl(private val client: Client) {

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    /** Execute a single command and exit (for -e flag / scripting) */
    fun execSingle(command: String) {
        val (cmd, rest) = splitFirst(command.trim())
        try {
            when (cmd.lowercase()) {
                "ask", "?"        -> doAsk(rest)
                "tell", "+"       -> doTell(rest)
                "teach", "++"     -> doTeach(rest)
                "forget", "-"     -> doForget(rest)
                "inspect", "ls"   -> doInspect(rest)
                "context", "ctx"  -> doContext(rest)
                "compress"        -> doCompress()
                "cleanup"         -> doCleanup(rest)
                "dsl", "exec"     -> doDsl(rest)
                "import", "load"  -> doImport(rest)
                "export", "dump"  -> doExport(rest)
                "dbs"             -> doDbs()
                "health"          -> doHealth()
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
            print("$BOLD${client.database}$RESET$DIM>$RESET ")
            val line = readlnOrNull()?.trim() ?: break
            if (line.isBlank()) continue

            val (cmd, rest) = splitFirst(line)
            try {
                when (cmd.lowercase()) {
                    "ask", "?"        -> doAsk(rest)
                    "tell", "+"       -> doTell(rest)
                    "teach", "++"     -> doTeach(rest)
                    "forget", "-"     -> doForget(rest)
                    "inspect", "ls"   -> doInspect(rest)
                    "context", "ctx"  -> doContext(rest)
                    "compress"        -> doCompress()
                    "cleanup"         -> doCleanup(rest)
                    "dsl", "exec"     -> doDsl(rest)
                    "import", "load"  -> doImport(rest)
                    "export", "dump"  -> doExport(rest)
                    "use"             -> doUse(rest)
                    "dbs"             -> doDbs()
                    "health"          -> doHealth()
                    "help", "h"       -> printHelp()
                    "exit", "quit", "q" -> { client.close(); return }
                    else              -> {
                        println("${RED}Unknown command: $cmd${RESET}")
                        println("${DIM}Type 'help' for available commands.${RESET}")
                    }
                }
            } catch (e: Exception) {
                println("${RED}Error: ${e.message}${RESET}")
            }
        }

        client.close()
    }

    // ── commands ──

    private fun doAsk(text: String) = runBlocking {
        require(text.isNotBlank()) { "Usage: ask predicate(?var)" }
        val resp = client.ask(Parser.atomToJson(text))
        printResult(resp)
    }

    private fun doTell(text: String) = runBlocking {
        require(text.isNotBlank()) { "Usage: tell predicate(arg1, arg2)" }
        val resp = client.tell(Parser.factToJson(text))
        printOk(resp, "Fact stored")
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

    private fun doInspect(filter: String) = runBlocking {
        val factsRaw = client.listFacts()
        val rulesRaw = client.listRules()
        val facts = tryParseArray(factsRaw)
        val rules = tryParseArray(rulesRaw)

        val filterLower = filter.lowercase()

        var count = 0
        for (f in facts) {
            val line = formatAtom(f)
            if (filterLower.isBlank() || line.lowercase().contains(filterLower)) {
                println("  ${DIM}fact${RESET}  $line")
                count++
            }
        }
        for (r in rules) {
            val line = formatRule(r)
            if (filterLower.isBlank() || line.lowercase().contains(filterLower)) {
                println("  ${DIM}rule${RESET}  $line")
                count++
            }
        }
        println("${DIM}$count item(s)${RESET}")
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

        println("${BOLD}Context Window$RESET — ${scored.size} of $total facts (by salience)")
        for (s in scored) {
            val salience = s.jsonObject["salience"]?.jsonPrimitive?.doubleOrNull ?: 0.0
            val atom = s.jsonObject["atom"]?.jsonObject
            if (atom != null) {
                val line = formatAtom(atom)
                println("  ${DIM}[${String.format("%.3f", salience)}]${RESET}  $line")
            }
        }
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
        sb.appendLine("# AxiomBase knowledge dump — ${client.database}")
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
        require(name.isNotBlank()) { "Usage: use <database>" }
        client.database = name
        println("${GREEN}Switched to $BOLD$name$RESET")
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
        println(resp)
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
                    println("${DIM}${el.size} result(s)${RESET}")
                }
                is JsonPrimitive -> println(el.content)
                else -> println(raw)
            }
        } catch (_: Exception) {
            println(raw)
        }
    }

    private fun printOk(raw: String, defaultMsg: String) {
        // Check if the response indicates success
        try {
            val el = json.parseToJsonElement(raw)
            if (el is JsonObject) {
                val msg = el["message"]?.jsonPrimitive?.contentOrNull
                    ?: el["status"]?.jsonPrimitive?.contentOrNull
                println("${GREEN}OK$RESET ${msg ?: defaultMsg}")
                return
            }
        } catch (_: Exception) {}
        println("${GREEN}OK$RESET $defaultMsg")
    }

    // ── help ──

    private fun printBanner() {
        println()
        println("${BOLD}AxiomBase CLI$RESET — logic server for agentic AI")
        println("${DIM}Connected to ${client.database} @ server${RESET}")
        println("${DIM}Type 'help' for commands, 'exit' to quit.${RESET}")
        println()
    }

    private fun printHelp() {
        println("""
${BOLD}Agent commands:$RESET
  ask   <pred(?var)>                Query with reasoning
  tell  <pred(arg, arg)>            Store a fact
  teach <head(?x) :- body(?x)>      Define a rule
  forget <pred(arg, arg)>           Remove a fact

${BOLD}Explore:$RESET
  inspect [filter]                  Browse all knowledge
  context [max]                     Salience-ranked context window

${BOLD}Operations:$RESET
  compress                          Consolidate episodic patterns
  cleanup [threshold]               Evict expired/low-salience facts
  dsl <command>                     Raw Logiql DSL

${BOLD}Import / Export:$RESET
  import <file.ab>                  Load facts & rules from file
  export [file.ab]                  Dump knowledge (to file or stdout)

${BOLD}Admin:$RESET
  use <database>                    Switch database
  dbs                               List databases
  health                            Server health check

${BOLD}Shortcuts:$RESET
  ?  = ask    +  = tell    ++ = teach    -  = forget    ls = inspect
  ctx = context    exec = dsl    load = import    dump = export    q = exit

${BOLD}Examples:$RESET
  tell human(socrates)
  teach mortal(?x) :- human(?x)
  ask mortal(?who)
  inspect
  export kb.ab
  import kb.ab

${BOLD}File format (.ab):$RESET
  # Comments start with #
  human(socrates)
  human(plato)
  mortal(?x) :- human(?x)
        """.trimIndent())
    }

    private fun splitFirst(line: String): Pair<String, String> {
        val idx = line.indexOfFirst { it.isWhitespace() }
        return if (idx == -1) line to "" else line.substring(0, idx) to line.substring(idx + 1).trim()
    }
}
