// Copyright (c) 2026 Auctalis LLC. All rights reserved.
// SPDX-License-Identifier: BUSL-1.1
// See LICENSE at the repository root for the full Business Source License 1.1.
/**
 * @module nocturnusai-sdk
 *
 * TypeScript SDK for NocturnusAI — a logic-based inference engine and knowledge database.
 *
 * This package provides two client classes:
 *
 * - {@link NocturnusAIClient} — Full-featured client for the NocturnusAI HTTP API.
 *   Supports fact/rule assertion, querying, inference, retraction, memory
 *   management (context windows, goal-driven optimization, diffs, temporal
 *   queries, consolidation, decay), DSL execution, health checks, and SSE
 *   event subscriptions.
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
  GoalSpec,
  RelevanceBucket,
  DerivationInfo,
  ContextEntry,
  ContradictionInfo,
  BucketStatsInfo,
  BucketStats,
  OptimizedContext,
  RemovedContextEntry,
  ContextDiff,
  PredicateSummary,
  ContextSummary,
  ExtractedFact,
  IngestAndOptimizeResult,
  TurnFact,
  TurnContextResult,
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
  ContextOptions,
  OptimizeContextOptions,
  DiffContextOptions,
  IngestAndOptimizeOptions,
  ProcessTurnsOptions,
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

  // Scope results
  ScopeDiffResult,
  ScopeMergeResult,

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
