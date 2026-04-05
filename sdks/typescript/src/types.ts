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
 * A goal specification for context optimization.
 */
export interface GoalSpec {
  /** Predicate to target. */
  predicate: string;

  /** Ordered list of arguments. */
  args: string[];

  /** Whether the goal is negated. */
  negated?: boolean;
}

/**
 * Weighted predicate bucket used during goal-driven context selection.
 */
export interface RelevanceBucket {
  /** Bucket name used in response statistics. */
  name: string;

  /** Optional predicate names assigned to this bucket. */
  predicates?: string[] | null;

  /** Relative weight for allocation. Defaults to 1.0. */
  weight?: number;
}

/**
 * Provenance for a derived context entry.
 */
export interface DerivationInfo {
  /** Rule used to derive the fact. */
  rule: string;

  /** Premises used in the derivation. */
  premises: string[];
}

/**
 * A single fact selected into a goal-driven context window.
 */
export interface ContextEntry {
  /** Predicate name. */
  predicate: string;

  /** Ordered list of arguments. */
  args: string[];

  /** Whether this fact is negated. */
  negated?: boolean;

  /** Isolation scope. */
  scope?: string | null;

  /** Composite salience score. */
  salience: number;

  /** Selection category such as goal_relevant or bucket name. */
  category: string;

  /** Character count of the rendered fact. */
  charCount: number;

  /** Optional derivation chain. */
  provenance?: DerivationInfo | null;

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
 * A contradiction detected during goal-driven context assembly.
 */
export interface ContradictionInfo {
  /** Contradicted predicate. */
  predicate: string;

  /** Arguments of the contradicted fact. */
  args: string[];

  /** Salience of the positive fact. */
  positiveSalience: number;

  /** Salience of the negative fact. */
  negativeSalience: number;
}

/**
 * Allocation statistics for a relevance bucket.
 */
export interface BucketStats {
  /** Number of facts included from this bucket. */
  factsIncluded: number;

  /** Maximum allocated facts for this bucket. */
  maxAllocation: number;

  /** Minimum salience within this bucket. */
  minSalience: number;

  /** Maximum salience within this bucket. */
  maxSalience: number;
}

/**
 * Goal-driven optimized context window.
 */
export interface OptimizedContext {
  /** Unique identifier for this context window. */
  windowId: string;

  /** Selected entries in the window. */
  entries: ContextEntry[];

  /** Relevant rules surfaced during optimization. */
  relevantRules: string[];

  /** Total facts available before selection. */
  totalFactsAvailable: number;

  /** Number of facts included in the output window. */
  totalFactsIncluded: number;

  /** Count of facts removed through deduplication. */
  deduplicationSavings: number;

  /** Number of contradictions detected. */
  contradictionsFound: number;

  /** Number of contradictions auto-resolved. */
  contradictionsResolved: number;

  /** Contradiction details. */
  contradictions: ContradictionInfo[];

  /** Per-bucket allocation statistics. */
  bucketStats: Record<string, BucketStats>;

  /** Character count of the selected window. */
  totalCharCount: number;

  /** Whether goal-driven selection was used. */
  goalDriven: boolean;

  /** Monotonic KB generation counter. */
  knowledgeGeneration: number;

  /** Epoch milliseconds when generated. */
  generatedAt: number;
}

/**
 * A fact removed between two context snapshots.
 */
export interface RemovedContextEntry {
  /** Stable internal entry key. */
  key: string;

  /** Predicate name. */
  predicate: string;

  /** Ordered list of arguments. */
  args: string[];

  /** Whether this fact is negated. */
  negated?: boolean;

  /** Isolation scope. */
  scope?: string | null;
}

/**
 * Incremental changes between two optimized context windows.
 */
export interface ContextDiff {
  /** Previous window ID, if one existed. */
  previousWindowId?: string | null;

  /** Current window ID. */
  currentWindowId: string;

  /** Facts added since the previous snapshot. */
  added: ContextEntry[];

  /** Facts removed since the previous snapshot. */
  removed: RemovedContextEntry[];

  /** Number of unchanged facts. */
  unchanged: number;

  /** Whether a full refresh is recommended instead of a diff. */
  fullRefreshRecommended: boolean;

  /** Optional explanation for a full refresh recommendation. */
  reason?: string | null;
}

/**
 * Predicate summary in a context overview response.
 */
export interface PredicateSummary {
  /** Predicate name. */
  predicate: string;

  /** Count of facts using this predicate. */
  count: number;
}

/**
 * Summary of the context store for monitoring or dashboards.
 */
export interface ContextSummary {
  /** Total facts in the knowledge base. */
  totalFacts: number;

  /** Number of distinct predicates. */
  predicateCount: number;

  /** Most common predicates. */
  topPredicates: PredicateSummary[];

  /** Number of facts with TTL. */
  factsWithTtl: number;

  /** Number of facts expiring within the next hour. */
  factsExpiringWithin1h: number;

  /** Number of contradictions currently present. */
  contradictions: number;

  /** Most salient facts in the store. */
  topSalientFacts: ContextEntry[];

  /** Approximate character footprint of the KB. */
  totalCharCount: number;

  /** Monotonic KB generation counter. */
  knowledgeGeneration: number;

  /** Epoch milliseconds when generated. */
  generatedAt: number;
}

/**
 * Fact extracted during a combined ingest-and-optimize request.
 */
export interface ExtractedFact {
  /** Predicate name. */
  predicate: string;

  /** Ordered list of arguments. */
  args: string[];

  /** Extraction confidence score. */
  confidence: number;
}

/**
 * Result of POST /context/ingest.
 */
export interface IngestAndOptimizeResult {
  /** Facts extracted from raw text. */
  extracted: ExtractedFact[];

  /** Extraction provider name when an LLM extractor is configured. */
  extractionProvider?: string | null;

  /** Goal-driven optimized context generated from the ingested text. */
  context: OptimizedContext;
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
  transactionId?: number | string;
}

/**
 * Options for rule assertion.
 */
export interface RuleOptions {
  /** Isolation scope for the rule. */
  scope?: string;

  /** Transaction ID for transactional operations. */
  transactionId?: number | string;
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
 * Options for goal-driven context optimization.
 */
export interface OptimizeContextOptions {
  /** Maximum facts to include. Defaults to 100. */
  maxFacts?: number;

  /** Isolation scope. */
  scope?: string;

  /** Restrict selection to these predicates. */
  predicates?: string[];

  /** Goal atoms that drive backward-chaining selection. */
  goals?: GoalSpec[];

  /** Optional weighted predicate buckets. */
  relevanceBuckets?: RelevanceBucket[];

  /** Session ID used for future diff calls. */
  sessionId?: string;

  /** Auto-resolve contradictions when possible. Defaults to true. */
  autoResolveContradictions?: boolean;

  /** Optional diversity cap per predicate. */
  maxFactsPerPredicate?: number;
}

/**
 * Options for incremental context diffs.
 */
export interface DiffContextOptions {
  /** Session ID created by a prior optimizeContext() call. */
  sessionId: string;

  /** Maximum facts in the new window. */
  maxFacts?: number;

  /** Isolation scope. */
  scope?: string;

  /** Restrict selection to these predicates. */
  predicates?: string[];

  /** Goal atoms that drive backward-chaining selection. */
  goals?: GoalSpec[];

  /** Optional weighted predicate buckets. */
  relevanceBuckets?: RelevanceBucket[];

  /** Auto-resolve contradictions when possible. */
  autoResolveContradictions?: boolean;

  /** Optional diversity cap per predicate. */
  maxFactsPerPredicate?: number;
}

/**
 * Options for the combined ingest-and-optimize endpoint.
 */
export interface IngestAndOptimizeOptions {
  /** Raw text to extract from. */
  text: string;

  /** Goal atoms that drive optimization. */
  goals?: GoalSpec[];

  /** Maximum facts to include. Defaults to 50. */
  maxFacts?: number;

  /** Optional diversity cap per predicate. */
  maxFactsPerPredicate?: number;

  /** Auto-resolve contradictions when possible. */
  autoResolveContradictions?: boolean;

  /** Session ID used for future diff calls. */
  sessionId?: string;

  /** Optional weighted predicate buckets. */
  relevanceBuckets?: RelevanceBucket[];

  /** Isolation scope. */
  scope?: string;

  /** Optional hint for the extractor. */
  contextHint?: string;
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
