#!/usr/bin/env node
/**
 * NocturnusAI MCP stdio shim.
 *
 * Speaks MCP over stdio to an LLM client (Claude, Cursor, etc.) and forwards
 * every tools/list and tools/call request to a NocturnusAI server's JSON-RPC
 * /mcp endpoint over HTTP.
 *
 * When the upstream server is unreachable, tools/list returns a static copy of
 * the tool schemas so that registry inspectors (Glama, Smithery, etc.) can
 * discover capabilities without a running backend.
 *
 * Configuration via environment variables:
 *   NOCTURNUSAI_URL       Base URL of NocturnusAI server (default: http://localhost:9300)
 *   NOCTURNUSAI_API_KEY   Optional API key sent as X-API-Key
 *   NOCTURNUSAI_DATABASE  Database name, sent as X-Database (default: default)
 *   NOCTURNUSAI_TENANT    Tenant id,     sent as X-Tenant-ID (default: default)
 */
import { Server } from "@modelcontextprotocol/sdk/server/index.js";
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";
import {
  CallToolRequestSchema,
  ListToolsRequestSchema,
} from "@modelcontextprotocol/sdk/types.js";

const URL_BASE = (process.env.NOCTURNUSAI_URL ?? "http://localhost:9300").replace(/\/$/, "");
const API_KEY = process.env.NOCTURNUSAI_API_KEY;
const DATABASE = process.env.NOCTURNUSAI_DATABASE ?? "default";
const TENANT = process.env.NOCTURNUSAI_TENANT ?? "default";

let rpcId = 0;
let serverReachable = true;

function headers(): Record<string, string> {
  const h: Record<string, string> = {
    "Content-Type": "application/json",
    "X-Database": DATABASE,
    "X-Tenant-ID": TENANT,
  };
  if (API_KEY) h["X-API-Key"] = API_KEY;
  return h;
}

async function rpc(method: string, params?: unknown): Promise<unknown> {
  const body = {
    jsonrpc: "2.0",
    id: ++rpcId,
    method,
    params: params ?? {},
  };
  const res = await fetch(`${URL_BASE}/mcp`, {
    method: "POST",
    headers: headers(),
    body: JSON.stringify(body),
  });
  if (!res.ok) {
    const text = await res.text().catch(() => "");
    throw new Error(`NocturnusAI /mcp ${res.status} ${res.statusText}: ${text}`);
  }
  const json = (await res.json()) as {
    result?: unknown;
    error?: { code: number; message: string; data?: unknown };
  };
  if (json.error) {
    const msg = json.error.message || "upstream error";
    throw new Error(`NocturnusAI RPC error ${json.error.code}: ${msg}`);
  }
  return json.result;
}

// ---------------------------------------------------------------------------
// Static tool schemas — returned when the upstream server is unreachable.
// Mirrors the definitions in McpRoutes.kt so that registry inspectors (Glama,
// Smithery, etc.) can discover capabilities without a running backend.
// ---------------------------------------------------------------------------

const STATIC_TOOLS = [
  {
    name: "tell",
    description:
      "Assert a fact into the knowledge base. Stores knowledge that can be queried and used in logical reasoning. Supports auto-expiration via ttl (milliseconds) or validUntil (epoch ms), confidence scoring, and configurable conflict resolution.",
    inputSchema: {
      type: "object",
      properties: {
        predicate: { type: "string", description: "The relationship or property name (e.g., 'parent', 'likes', 'located_in')" },
        args: { type: "array", items: { type: "string" }, description: "The entities involved (e.g., ['alice', 'bob'] for 'alice is parent of bob')" },
        scope: { type: "string", description: "Optional isolation scope for partitioned reasoning (e.g., 'session_123', 'hypothesis_a')" },
        negated: { type: "boolean", description: "Set true to store the explicit negation of this fact (distinct from NAF)" },
        ttl: { type: "number", description: "Auto-expire after this many milliseconds" },
        validUntil: { type: "number", description: "Epoch ms when this fact stops being valid" },
        confidence: { type: "number", description: "Confidence score 0.0–1.0 (e.g., 0.9 = high confidence from LLM extraction)" },
        conflictStrategy: { type: "string", description: "How to handle contradictions: REJECT (default — error on duplicate), NEWEST_WINS, CONFIDENCE (highest wins), KEEP_BOTH" },
      },
      required: ["predicate", "args"],
    },
  },
  {
    name: "teach",
    description:
      "Define a logical rule for automatic reasoning. When all conditions (body) are true, the conclusion (head) is automatically derivable via backward chaining. Use ?-prefixed variables (e.g., ?x, ?who). Supports Negation-as-Failure in body atoms. Example: 'If ?x is human AND NOT god(?x), THEN ?x is mortal'.",
    inputSchema: {
      type: "object",
      properties: {
        head: {
          type: "object",
          description: "The conclusion — what becomes true when all body conditions hold",
          properties: {
            predicate: { type: "string", description: "Conclusion relationship name" },
            args: { type: "array", items: { type: "string" }, description: "Arguments (use ?x, ?y for variables that unify across head and body)" },
          },
        },
        body: {
          type: "array",
          description: "Conditions that must all hold. Each object has 'predicate', 'args', optional 'negated' (explicit negation) and 'naf' (closed-world negation-as-failure)",
          items: {
            type: "object",
            properties: {
              predicate: { type: "string" },
              args: { type: "array", items: { type: "string" } },
              negated: { type: "boolean" },
              naf: { type: "boolean" },
            },
          },
        },
        scope: { type: "string", description: "Optional scope" },
      },
      required: ["head", "body"],
    },
  },
  {
    name: "ask",
    description:
      "Query the knowledge base using multi-step logical reasoning (backward chaining with unification). Finds all provable answers by applying rules and matching facts. Use ?-prefixed variables for unknowns you want to discover. Optionally returns full proof chains showing the reasoning steps.",
    inputSchema: {
      type: "object",
      properties: {
        predicate: { type: "string", description: "What you're asking about (e.g., 'grandparent', 'can_access')" },
        args: { type: "array", items: { type: "string" }, description: "Use ?x, ?who for unknowns, concrete values to constrain (e.g., ['?who', 'charlie'])" },
        scope: { type: "string", description: "Optional scope filter — omit to query all scopes" },
        withProof: { type: "boolean", description: "If true, include the full reasoning chain showing how each answer was derived (fact matches and rule applications)" },
        minConfidence: { type: "number", description: "Minimum confidence threshold 0.0–1.0. Filters out facts and derivations below this confidence." },
      },
      required: ["predicate", "args"],
    },
  },
  {
    name: "forget",
    description:
      "Retract a fact from the knowledge base. Any knowledge that was derived from this fact is also automatically forgotten via the Truth Maintenance System (cascading retraction). This is the inverse of 'tell'.",
    inputSchema: {
      type: "object",
      properties: {
        predicate: { type: "string", description: "The relationship to forget" },
        args: { type: "array", items: { type: "string" }, description: "The specific entities to forget about" },
        scope: { type: "string", description: "Optional scope" },
      },
      required: ["predicate", "args"],
    },
  },
  {
    name: "recall",
    description:
      "Time-travel query: recall what was known at a specific point in time. Returns facts that were valid at the given timestamp, respecting temporal bounds (validFrom, validUntil, ttl). Useful for debugging agent behavior or reconstructing past state.",
    inputSchema: {
      type: "object",
      properties: {
        predicate: { type: "string", description: "What to recall" },
        args: { type: "array", items: { type: "string" }, description: "Arguments (use ?-prefix for unknowns)" },
        timestamp: { type: "number", description: "Epoch milliseconds — the moment in time to recall (e.g., Date.now() - 3600000 for one hour ago)" },
        scope: { type: "string", description: "Optional scope filter" },
      },
      required: ["predicate", "args", "timestamp"],
    },
  },
  {
    name: "context",
    description:
      "Get the most relevant knowledge for your current reasoning step, ranked by composite salience (recency × frequency × priority). Returns a token-optimized context window. Supports three output formats: 'predicate' (machine-readable), 'natural' (LLM-optimized prose), 'structured' (grouped with metadata). Pass goals for goal-driven selection, sessionId for incremental diffing across turns.",
    inputSchema: {
      type: "object",
      properties: {
        maxFacts: { type: "number", description: "Maximum facts to return (default: 100)" },
        minSalience: { type: "number", description: "Minimum salience score 0.0–1.0 (default: 0.0)" },
        predicates: { type: "array", items: { type: "string" }, description: "Only include these relationship types" },
        scope: { type: "string", description: "Optional scope filter" },
        format: { type: "string", description: "Output format: 'predicate' (default, machine-readable), 'natural' (LLM-optimized natural language), or 'structured' (grouped with metadata)" },
        includeRules: { type: "boolean", description: "Include reasoning rules in the context (default: true)" },
        goals: {
          type: "array",
          description: "Goal atoms for goal-driven context selection, e.g. [{\"predicate\":\"recommend\",\"args\":[\"?x\"]}]",
          items: {
            type: "object",
            properties: {
              predicate: { type: "string" },
              args: { type: "array", items: { type: "string" } },
              negated: { type: "boolean" },
            },
          },
        },
        sessionId: { type: "string", description: "Session ID for incremental diffing — only returns facts changed since last call with this sessionId" },
        autoResolveContradictions: { type: "boolean", description: "Auto-resolve contradictions by salience (default: true)" },
        maxFactsPerPredicate: { type: "number", description: "Diversity cap — maximum facts per predicate type" },
      },
      required: [],
    },
  },
  {
    name: "compress",
    description:
      "Run memory consolidation: detects repeated episodic patterns (e.g., 'user asked about X five times') and creates semantic summaries. Reduces memory footprint in long-running agent sessions while preserving essential knowledge.",
    inputSchema: { type: "object", properties: {}, required: [] },
  },
  {
    name: "cleanup",
    description:
      "Run memory decay and eviction. Expires facts past their TTL and evicts low-salience facts when memory exceeds capacity. Call periodically in long-running agent sessions to prevent unbounded memory growth.",
    inputSchema: {
      type: "object",
      properties: {
        threshold: { type: "number", description: "Salience threshold below which facts are evicted (default: 0.05). Higher values are more aggressive." },
      },
      required: [],
    },
  },
  {
    name: "predicates",
    description:
      "Discover the knowledge base schema. Lists all predicates (relationship types) currently stored, with their arity (argument count), fact count, and whether they have associated rules. Use this to understand what knowledge is available before querying.",
    inputSchema: {
      type: "object",
      properties: {
        scope: { type: "string", description: "Optional scope filter" },
      },
      required: [],
    },
  },
  {
    name: "aggregate",
    description:
      "Compute aggregations over matching facts. Supports COUNT (number of matches), SUM, MIN, MAX, and AVG over a numeric argument at a specified position. Example: COUNT all score(player, ?) facts, or AVG scores at argIndex=1.",
    inputSchema: {
      type: "object",
      properties: {
        predicate: { type: "string", description: "The predicate to aggregate over" },
        args: { type: "array", items: { type: "string" }, description: "Pattern arguments — use ?x as wildcards, concrete values to constrain" },
        operation: { type: "string", description: "Aggregation operation: COUNT, SUM, MIN, MAX, or AVG" },
        argIndex: { type: "number", description: "0-based argument position to aggregate for SUM/MIN/MAX/AVG (ignored for COUNT)" },
        scope: { type: "string", description: "Optional scope filter" },
      },
      required: ["predicate", "args", "operation"],
    },
  },
  {
    name: "bulk_assert",
    description:
      "Assert multiple facts in a single call for efficiency. Non-transactional: each fact is attempted independently — contradictions are reported as errors without aborting the batch. Returns counts of successful and failed assertions.",
    inputSchema: {
      type: "object",
      properties: {
        facts: {
          type: "array",
          description: "Array of fact objects to assert",
          items: {
            type: "object",
            properties: {
              predicate: { type: "string", description: "The relationship or property name" },
              args: { type: "array", items: { type: "string" }, description: "The entities involved" },
              negated: { type: "boolean", description: "Set true to store the negation" },
              scope: { type: "string", description: "Optional isolation scope" },
              ttl: { type: "number", description: "Auto-expire after this many milliseconds" },
              validUntil: { type: "number", description: "Epoch ms when this fact expires" },
            },
            required: ["predicate", "args"],
          },
        },
      },
      required: ["facts"],
    },
  },
  {
    name: "retract_pattern",
    description:
      "Retract all facts matching a pattern in a single call. Use ?-prefixed variables as wildcards to retract multiple facts at once. Returns the count and list of retracted facts. Cascading retraction applies to each removed fact.",
    inputSchema: {
      type: "object",
      properties: {
        predicate: { type: "string", description: "The predicate pattern to match for retraction" },
        args: { type: "array", items: { type: "string" }, description: "Arguments — use ?x as wildcards to match multiple facts" },
        scope: { type: "string", description: "Optional scope filter" },
      },
      required: ["predicate", "args"],
    },
  },
  {
    name: "fork_scope",
    description:
      "Fork a knowledge base scope — creates an independent copy of all facts in the source scope under a new target scope name. Use this for hypothetical reasoning ('What if Alice moves to London?') without modifying the main knowledge base. Similar to git branch for knowledge.",
    inputSchema: {
      type: "object",
      properties: {
        sourceScope: { type: "string", description: "Scope to copy from. Omit or pass null for the global (unscoped) partition." },
        targetScope: { type: "string", description: "New scope name to create with copied facts" },
      },
      required: ["targetScope"],
    },
  },
  {
    name: "merge_scope",
    description:
      "Merge facts from one scope back into another (default: global). Use this to commit hypothetical reasoning results back into the main knowledge base. Choose a conflict strategy: SOURCE_WINS overwrites, TARGET_WINS keeps existing, KEEP_BOTH retains both versions, REJECT aborts if any conflicts.",
    inputSchema: {
      type: "object",
      properties: {
        sourceScope: { type: "string", description: "Scope to merge facts from" },
        targetScope: { type: "string", description: "Destination scope. Omit or pass null for the global partition." },
        strategy: { type: "string", description: "Conflict resolution: SOURCE_WINS (default) | TARGET_WINS | KEEP_BOTH | REJECT" },
      },
      required: ["sourceScope"],
    },
  },
  {
    name: "list_scopes",
    description:
      "List all named scopes in the knowledge base. Shows what hypothetical contexts or reasoning branches currently exist. The global (unscoped) partition is always present but not listed.",
    inputSchema: { type: "object", properties: {}, required: [] },
  },
  {
    name: "delete_scope",
    description:
      "Delete a knowledge base scope and all facts within it. Use this to clean up completed or abandoned hypothetical reasoning branches. This is irreversible.",
    inputSchema: {
      type: "object",
      properties: {
        scope: { type: "string", description: "The scope name to delete" },
      },
      required: ["scope"],
    },
  },
];

const server = new Server(
  {
    name: "nocturnusai-mcp",
    version: "0.3.11",
  },
  {
    capabilities: {
      tools: {},
    },
  }
);

server.setRequestHandler(ListToolsRequestSchema, async () => {
  // Try the live server first; fall back to static schemas for offline inspection
  if (serverReachable) {
    try {
      const result = (await rpc("tools/list")) as { tools: unknown[] };
      return { tools: result.tools ?? [] };
    } catch {
      serverReachable = false;
      process.stderr.write(
        `[nocturnusai-mcp] upstream unreachable — returning static tool schemas\n`
      );
    }
  }
  return { tools: STATIC_TOOLS };
});

server.setRequestHandler(CallToolRequestSchema, async (req) => {
  if (!serverReachable) {
    // Retry connectivity on each tool call in case the server came back up
    try {
      const probe = await fetch(`${URL_BASE}/health`, {
        headers: headers(),
        signal: AbortSignal.timeout(3000),
      });
      if (probe.ok) serverReachable = true;
    } catch {
      // still unreachable
    }
  }

  if (!serverReachable) {
    return {
      content: [
        {
          type: "text",
          text: `Error: NocturnusAI server is not reachable at ${URL_BASE}. Start the server or set NOCTURNUSAI_URL.`,
        },
      ],
      isError: true,
    };
  }

  const result = (await rpc("tools/call", {
    name: req.params.name,
    arguments: req.params.arguments ?? {},
  })) as { content: unknown[]; isError?: boolean };
  return {
    content: result.content ?? [],
    isError: result.isError,
  };
});

async function main(): Promise<void> {
  // Quick connectivity check — not fatal. When the server is unreachable,
  // tools/list still works (static schemas) so registry inspectors succeed.
  try {
    const res = await fetch(`${URL_BASE}/health`, {
      headers: headers(),
      signal: AbortSignal.timeout(5000),
    });
    if (!res.ok) {
      serverReachable = false;
      process.stderr.write(
        `[nocturnusai-mcp] warning: ${URL_BASE}/health returned ${res.status}\n`
      );
    }
  } catch (err) {
    serverReachable = false;
    process.stderr.write(
      `[nocturnusai-mcp] warning: cannot reach ${URL_BASE}: ${(err as Error).message}\n` +
        `[nocturnusai-mcp] tools/list will return static schemas; tools/call requires a running server\n`
    );
  }

  const transport = new StdioServerTransport();
  await server.connect(transport);
}

main().catch((err) => {
  process.stderr.write(`[nocturnusai-mcp] fatal: ${(err as Error).stack ?? err}\n`);
  process.exit(1);
});
