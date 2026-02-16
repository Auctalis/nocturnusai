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
                    "ingest"          -> doIngest(rest)
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
                val errMsg = obj["message"]?.jsonPrimitive?.contentOrNull ?: "Unknown error"
                println("${RED}$errCode: $errMsg${RESET}")
                return@runBlocking
            }

            val answer = obj["answer"]?.jsonPrimitive?.contentOrNull ?: resp
            val confidence = obj["confidence"]?.jsonPrimitive?.floatOrNull ?: 0f
            val derivation = obj["derivation"]?.jsonArray ?: JsonArray(emptyList())
            val missing = obj["missingContext"]?.jsonPrimitive?.contentOrNull ?: ""
            val queries = obj["queriesExecuted"]?.jsonArray
            val provider = obj["provider"]?.jsonPrimitive?.contentOrNull
            val model = obj["model"]?.jsonPrimitive?.contentOrNull

            println()
            println("  $answer")
            println()

            if (derivation.isNotEmpty()) {
                println("${DIM}Derivation:${RESET}")
                for (step in derivation) {
                    val fact = step.jsonObject["fact"]?.jsonPrimitive?.contentOrNull ?: ""
                    val type = step.jsonObject["type"]?.jsonPrimitive?.contentOrNull ?: ""
                    val rule = step.jsonObject["rule"]?.jsonPrimitive?.contentOrNull
                    val typeTag = when (type) {
                        "fact_match" -> "${GREEN}fact${RESET}"
                        "rule_application" -> "${CYAN}rule${RESET}"
                        else -> "${DIM}$type${RESET}"
                    }
                    print("  $typeTag  $fact")
                    if (rule != null) print("  ${DIM}via $rule${RESET}")
                    println()
                }
            }

            if (missing.isNotBlank()) {
                println("${DIM}Gaps: $missing${RESET}")
            }

            val confStr = String.format("%.0f%%", confidence * 100)
            val providerStr = if (model != null) "$provider/$model" else ""
            println("${DIM}Confidence: $confStr  $providerStr${RESET}")

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
                val errMsg = obj["message"]?.jsonPrimitive?.contentOrNull ?: "Unknown error"
                println("${RED}$errCode: $errMsg${RESET}")
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
  ask   <question or pred(?var)>    Query (NL or predicate syntax)
  tell  <text or pred(arg, arg)>    Store a fact (NL or structured)
  teach <head(?x) :- body(?x)>      Define a rule
  forget <pred(arg, arg)>           Remove a fact
  ingest <text or -f file>          Extract facts from plain text via LLM

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

${BOLD}Examples — structured:$RESET
  tell human(socrates)
  teach mortal(?x) :- human(?x)
  ask mortal(?who)

${BOLD}Examples — natural language (requires LLM configured on server):$RESET
  ingest Alice is Bob's mother. Bob works at Acme Corp.
  ingest -f article.txt
  ask who is Bob's mother?
  ask what company does Bob work at?
  tell the president met with the prime minister yesterday
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
