/**
 * @module nocturnusai-sdk
 *
 * TypeScript SDK for NocturnusAI — a logic-based inference engine and knowledge database.
 *
 * This package provides two client classes:
 *
 * - {@link NocturnusAIClient} — Full-featured client for the NocturnusAI HTTP API.
 *   Supports fact/rule assertion, querying, inference, retraction, memory
 *   management (context window, temporal queries, consolidation, decay),
 *   DSL execution, health checks, and SSE event subscriptions.
 *
 * - {@link NocturnusAIMCPClient} — MCP (Model Context Protocol) client for
 *   tool discovery and invocation via JSON-RPC 2.0. Useful for integrating
 *   NocturnusAI with MCP-compatible AI agents.
 *
 * Both clients use the standard Fetch API and work in Node.js 18+ and
 * modern browsers without any dependencies.
 *
 * @example
 * ```ts
 * import { NocturnusAIClient } from 'nocturnusai-sdk';
 *
 * const client = new NocturnusAIClient({
 *   baseUrl: 'http://localhost:9300',
 *   database: 'mydb',
 *   tenantId: 'default',
 * });
 *
 * // Assert facts
 * await client.assertFact('parent', ['alice', 'bob']);
 * await client.assertFact('parent', ['bob', 'charlie']);
 *
 * // Assert a rule
 * await client.assertRule(
 *   { predicate: 'grandparent', args: ['?x', '?z'] },
 *   [
 *     { predicate: 'parent', args: ['?x', '?y'] },
 *     { predicate: 'parent', args: ['?y', '?z'] },
 *   ]
 * );
 *
 * // Run inference
 * const results = await client.infer('grandparent', ['?who', 'charlie']);
 * ```
 *
 * @packageDocumentation
 */

export { NocturnusAIClient, NocturnusAIRequestError } from './client.js';

export { NocturnusAIMCPClient, McpError } from './mcp.js';

export type {
  // Configuration
  NocturnusAIConfig,

  // Core domain
  Atom,
  ScoredAtom,
  ContextWindow,
  ConsolidationResult,
  DecayResult,

  // Rule types
  RuleHead,
  RuleBodyAtom,
  RuleBody,

  // Proof tree
  ProofStep,
  ProofNode,
  ProofTree,

  // Request options
  FactOptions,
  RuleOptions,
  InferOptions,
  ContextWindowOptions,
  EventSubscriptionOptions,

  // Events
  KnowledgeEvent,

  // Error
  NocturnusAIError,

  // Auth / Key management
  AuthStatus,
  CreateKeyOptions,
  CreateKeyResponse,
  KeyInfo,
  WhoAmI,

  // Schema discovery
  PredicateInfo,
  SchemaDiscovery,

  // MCP types
  JsonRpcRequest,
  JsonRpcResponse,
  JsonRpcError,
  McpTool,
  McpToolContent,
  McpToolResult,
  McpServerInfo,

  // Health
  HealthStatus,
} from './types.js';
