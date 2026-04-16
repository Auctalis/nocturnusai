#!/usr/bin/env node
/**
 * NocturnusAI MCP stdio shim.
 *
 * Speaks MCP over stdio to an LLM client (Claude, Cursor, etc.) and forwards
 * every tools/list and tools/call request to a NocturnusAI server's JSON-RPC
 * /mcp endpoint over HTTP.
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

const server = new Server(
  {
    name: "nocturnusai-mcp",
    version: "0.3.0",
  },
  {
    capabilities: {
      tools: {},
    },
  }
);

server.setRequestHandler(ListToolsRequestSchema, async () => {
  const result = (await rpc("tools/list")) as { tools: unknown[] };
  return { tools: result.tools ?? [] };
});

server.setRequestHandler(CallToolRequestSchema, async (req) => {
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
  // Quick connectivity check. Not fatal — a startup failure would hide behind
  // the first tool call otherwise, which is confusing to debug.
  try {
    const res = await fetch(`${URL_BASE}/health`, { headers: headers() });
    if (!res.ok) {
      process.stderr.write(
        `[nocturnusai-mcp] warning: ${URL_BASE}/health returned ${res.status}\n`
      );
    }
  } catch (err) {
    process.stderr.write(
      `[nocturnusai-mcp] warning: cannot reach ${URL_BASE}: ${(err as Error).message}\n` +
        `[nocturnusai-mcp] start the server or set NOCTURNUSAI_URL\n`
    );
  }

  const transport = new StdioServerTransport();
  await server.connect(transport);
}

main().catch((err) => {
  process.stderr.write(`[nocturnusai-mcp] fatal: ${(err as Error).stack ?? err}\n`);
  process.exit(1);
});
