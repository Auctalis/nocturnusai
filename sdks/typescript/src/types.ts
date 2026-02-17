/**
 * @module types
 * Type definitions for the NocturnusAI TypeScript SDK.
 *
 * These types mirror the server-side DTOs defined in the NocturnusAI HTTP API
 * and provide full type safety for all client operations.
 */

// ---------------------------------------------------------------------------
// Configuration
// ---------------------------------------------------------------------------

/**
 * Configuration for connecting to an NocturnusAI server instance.
 */
export interface NocturnusAIConfig {
  /** Base URL of the NocturnusAI server (e.g. "http://localhost:9300"). */
  baseUrl: string;

  /** Optional API key for authenticated access. Sent as `X-API-Key` header. */
  apiKey?: string;

  /** Database name. Sent as `X-Database` header. Defaults to "default". */
  database?: string;

  /** Tenant ID within the database. Sent as `X-Tenant-ID` header. */
  tenantId?: string;
}

// ---------------------------------------------------------------------------
// Core domain types
// ---------------------------------------------------------------------------

/**
 * An atom (fact) in the knowledge base.
 * Atoms are the fundamental unit of knowledge: a predicate applied to arguments
 * with an optional truth value, scope, and temporal metadata.
 */
export interface Atom {
  /** The predicate name (e.g. "parent", "likes", "located_in"). */
  predicate: string;

  /** Ordered list of arguments. Use "?prefix" for variables. */
  args: string[];

  /** Whether this atom is negated. When true, represents the negation of the predicate. */
  negated?: boolean;

  /** Isolation scope for multi-tenant or hypothetical reasoning. */
  scope?: string | null;

  /** Arbitrary key-value metadata attached to this atom. */
  metadata?: Record<string, unknown>;

  /** Epoch milliseconds when this atom was created. */
  createdAt?: number | null;

  /** Epoch milliseconds from which this atom is valid. */
  validFrom?: number | null;

  /** Epoch milliseconds until which this atom is valid. */
  validUntil?: number | null;

  /** Time-to-live in milliseconds. The atom auto-expires after this duration. */
  ttl?: number | null;
}

/**
 * An atom annotated with a salience (relevance) score.
 * Returned by context window and salience-ranked query endpoints.
 */
export interface ScoredAtom {
  /** The predicate name. */
  predicate: string;

  /** Ordered list of arguments. */
  args: string[];

  /** Whether this atom is negated. */
  negated?: boolean;

  /** Isolation scope. */
  scope?: string | null;

  /** Composite salience score (0.0 to 1.0) based on recency, access frequency, and priority. */
  salience: number;

  /** Epoch milliseconds when this atom was created. */
  createdAt?: number | null;

  /** Epoch milliseconds from which this atom is valid. */
  validFrom?: number | null;

  /** Epoch milliseconds until which this atom is valid. */
  validUntil?: number | null;

  /** Arbitrary key-value metadata. */
  metadata?: Record<string, unknown>;
}

/**
 * A salience-ranked context window for agent reasoning.
 * Contains the most relevant facts for the current reasoning step.
 */
export interface ContextWindow {
  /** Salience-ranked list of facts. */
  facts: ScoredAtom[];

  /** Total number of facts available in the knowledge base. */
  totalAvailable: number;

  /** Number of facts in this window. */
  windowSize: number;

  /** Distribution of predicates in the window (predicate name -> count). */
  predicateDistribution: Record<string, number>;

  /** Epoch milliseconds when this window was generated. */
  generatedAt: number;
}

/**
 * Result of a memory consolidation operation.
 * Consolidation detects repeated episodic patterns and compresses them into
 * semantic summary facts.
 */
export interface ConsolidationResult {
  /** Number of fact patterns that were consolidated. */
  factsConsolidated: number;

  /** Newly created summary facts. */
  newFacts: Atom[];

  /** Epoch milliseconds when consolidation was performed. */
  timestamp: number;
}

/**
 * Result of a memory decay operation.
 * Decay expires TTL'd facts and evicts low-salience facts when over capacity.
 */
export interface DecayResult {
  /** Number of facts that expired due to TTL or validUntil. */
  expiredCount: number;

  /** Number of facts evicted due to low salience. */
  evictedCount: number;

  /** List of atoms that were removed. */
  removedAtoms: Atom[];

  /** Epoch milliseconds when decay was performed. */
  timestamp: number;
}

// ---------------------------------------------------------------------------
// Rule types
// ---------------------------------------------------------------------------

/**
 * The head (consequent) of a logical rule.
 */
export interface RuleHead {
  /** Predicate name for the rule consequent. */
  predicate: string;

  /** Arguments. Use "?prefix" for variables (e.g. "?x", "?who"). */
  args: string[];

  /** Whether this head is negated. */
  negated?: boolean;

  /** Optional scope for the head atom. */
  scope?: string | null;

  /** Optional metadata for the head atom. */
  metadata?: Record<string, unknown>;
}

/**
 * A single condition (antecedent atom) in the body of a rule.
 */
export interface RuleBodyAtom {
  /** Predicate name for this condition. */
  predicate: string;

  /** Arguments. Use "?prefix" for variables. */
  args: string[];

  /** Whether this condition is negated. */
  negated?: boolean;

  /** Optional scope for this condition. */
  scope?: string | null;

  /** Optional metadata for this condition. */
  metadata?: Record<string, unknown>;
}

/**
 * The body of a rule: a list of antecedent conditions.
 */
export type RuleBody = RuleBodyAtom[];

// ---------------------------------------------------------------------------
// Proof tree types
// ---------------------------------------------------------------------------

/**
 * A single step in a proof derivation.
 * Either a direct fact match or a rule application with sub-proofs.
 */
export interface ProofStep {
  /** The type of proof step. */
  type: 'fact_match' | 'rule_application';

  /** The matched fact (present when type is "fact_match"). */
  fact?: Atom;

  /** String representation of the applied rule (present when type is "rule_application"). */
  rule?: string;

  /** Sub-proofs for each condition in the rule body (present when type is "rule_application"). */
  bodyProofs?: ProofNode[];
}

/**
 * A node in the proof tree, representing one goal and how it was resolved.
 */
export interface ProofNode {
  /** The goal atom that was being proved. */
  goal: Atom;

  /** The proof step that resolved this goal. */
  step: ProofStep;

  /** Variable substitutions applied at this node. */
  substitution: Record<string, string>;
}

/**
 * A complete proof tree for an inference result.
 * Contains the derived result and the full proof derivation chain.
 */
export interface ProofTree {
  /** The inferred result atom. */
  result: Atom;

  /** Root node of the proof derivation. */
  proof: ProofNode;
}

// ---------------------------------------------------------------------------
// Request option types
// ---------------------------------------------------------------------------

/**
 * Common options for fact-related operations (assert, query, infer, retract).
 */
export interface FactOptions {
  /** Whether the fact is negated. */
  negated?: boolean;

  /** Isolation scope. */
  scope?: string;

  /** Arbitrary metadata to attach to the fact. */
  metadata?: Record<string, unknown>;

  /** Epoch milliseconds from which this fact is valid. */
  validFrom?: number;

  /** Epoch milliseconds until which this fact is valid. */
  validUntil?: number;

  /** Time-to-live in milliseconds. */
  ttl?: number;

  /** Transaction ID for transactional operations. */
  transactionId?: number;
}

/**
 * Options for rule assertion.
 */
export interface RuleOptions {
  /** Isolation scope for the rule. */
  scope?: string;

  /** Transaction ID for transactional operations. */
  transactionId?: number;
}

/**
 * Options for inference queries.
 */
export interface InferOptions {
  /** Whether the query is negated. */
  negated?: boolean;

  /** Isolation scope. */
  scope?: string;

  /** When true, returns full proof trees instead of plain atoms. */
  withProof?: boolean;
}

/**
 * Options for the context window endpoint.
 */
export interface ContextWindowOptions {
  /** Maximum number of facts to return. Defaults to 100. */
  maxFacts?: number;

  /** Minimum salience score (0.0 to 1.0). Defaults to 0.0. */
  minSalience?: number;

  /** Optional list of predicate names to filter by. */
  predicates?: string[];

  /** Isolation scope. */
  scope?: string;
}

/**
 * Options for the SSE event subscription.
 */
export interface EventSubscriptionOptions {
  /** Optional predicate pattern to filter events. */
  predicate?: string;

  /**
   * Set of event types to subscribe to.
   * Defaults to all: "fact_asserted", "fact_retracted", "rule_asserted", "fact_expired", "consolidation".
   */
  events?: string[];

  /** Resume from a specific event ID (for catch-up). */
  sinceId?: number;
}

/**
 * A knowledge event received via SSE.
 */
export interface KnowledgeEvent {
  /** The type of event. */
  type: string;

  /** Monotonically increasing event ID. */
  eventId: number;

  /** Epoch milliseconds when the event occurred. */
  timestamp: number;

  /** The atom involved in the event (if applicable). */
  atom?: Atom;

  /** String representation of a rule (for rule_asserted events). */
  rule?: string;

  /** Number of source facts (for consolidation events). */
  sourceCount?: number;

  /** The consolidated fact (for consolidation events). */
  fact?: Atom;
}

// ---------------------------------------------------------------------------
// Error response type
// ---------------------------------------------------------------------------

/**
 * Error response from the NocturnusAI server.
 */
export interface NocturnusAIError {
  /** Error code (e.g. "VALIDATION_ERROR", "NOT_FOUND", "CONFLICT"). */
  code: string;

  /** Human-readable error message. */
  message: string;

  /** Additional error details. */
  details?: Record<string, string>;
}

// ---------------------------------------------------------------------------
// Auth / Key management types
// ---------------------------------------------------------------------------

/**
 * Response from the auth status endpoint.
 */
export interface AuthStatus {
  authEnabled: boolean;
  mode: string;
  hasKeys: boolean;
}

/**
 * Options for creating a new API key.
 */
export interface CreateKeyOptions {
  /** Human-readable name for the key. */
  name: string;

  /** Role: "admin", "writer", or "reader". */
  role: string;

  /** Optional database scope (empty = all databases). */
  databases?: string[];

  /** Optional tenant scope (empty = all tenants). */
  tenants?: string[];

  /** Optional expiration in days. */
  expiresInDays?: number;

  /** Optional description. */
  description?: string;
}

/**
 * Response from creating a new API key.
 * The raw key is only available in this response.
 */
export interface CreateKeyResponse {
  id: string;
  name: string;
  key: string;
  prefix: string;
  role: string;
  databases: string[];
  tenants: string[];
  expiresAt: number | null;
}

/**
 * Information about an API key (without the raw key).
 */
export interface KeyInfo {
  id: string;
  name: string;
  prefix: string;
  role: string;
  databases: string[];
  tenants: string[];
  createdAt: number;
  lastUsedAt: number | null;
  expiresAt: number | null;
  enabled: boolean;
  description: string;
}

/**
 * Response from the whoami endpoint.
 */
export interface WhoAmI {
  keyId: string;
  name: string;
  role: string;
  permissions: string[];
  databases: string[];
  tenants: string[];
}

/**
 * Schema discovery response from GET /predicates.
 */
export interface PredicateInfo {
  predicate: string;
  factCount: number;
  ruleCount: number;
  arity: number;
  hasRules: boolean;
}

export interface SchemaDiscovery {
  predicates: PredicateInfo[];
  totalPredicates: number;
  totalFacts: number;
  totalRules: number;
}

// ---------------------------------------------------------------------------
// MCP types
// ---------------------------------------------------------------------------

/**
 * JSON-RPC 2.0 request for MCP protocol communication.
 */
export interface JsonRpcRequest {
  jsonrpc: '2.0';
  id?: string | number | null;
  method: string;
  params?: Record<string, unknown>;
}

/**
 * JSON-RPC 2.0 response from MCP protocol communication.
 */
export interface JsonRpcResponse {
  jsonrpc: '2.0';
  id?: string | number | null;
  result?: unknown;
  error?: JsonRpcError | null;
}

/**
 * JSON-RPC 2.0 error object.
 */
export interface JsonRpcError {
  code: number;
  message: string;
  data?: unknown;
}

/**
 * An MCP tool descriptor as returned by tools/list.
 */
export interface McpTool {
  /** Tool name (e.g. "assert_fact", "infer", "context_window"). */
  name: string;

  /** Human-readable description of what the tool does. */
  description: string;

  /** JSON Schema describing the tool's input parameters. */
  inputSchema: Record<string, unknown>;
}

/**
 * MCP tool call result content item.
 */
export interface McpToolContent {
  /** Content type (typically "text"). */
  type: string;

  /** The text content of the result. */
  text: string;
}

/**
 * MCP tool call result.
 */
export interface McpToolResult {
  /** List of content items in the result. */
  content: McpToolContent[];

  /** Whether the tool call resulted in an error. */
  isError?: boolean;
}

/**
 * MCP server capabilities returned by initialize.
 */
export interface McpServerInfo {
  /** MCP protocol version. */
  protocolVersion: string;

  /** Server capabilities. */
  capabilities: Record<string, unknown>;

  /** Server name and version. */
  serverInfo: {
    name: string;
    version: string;
  };
}

// ---------------------------------------------------------------------------
// Health check types
// ---------------------------------------------------------------------------

/**
 * Health check response from the /health endpoint.
 */
export interface HealthStatus {
  /** Overall status: "healthy" or "unhealthy". */
  status: string;

  /** Additional health check details. */
  [key: string]: unknown;
}
