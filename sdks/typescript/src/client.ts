/**
 * @module client
 * Main NocturnusAI client for interacting with the HTTP API.
 *
 * Uses the standard Fetch API (available in Node.js 18+ and all modern browsers).
 * Includes automatic retry with exponential backoff for transient failures.
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
 * await client.assertFact('parent', ['alice', 'bob']);
 * const results = await client.infer('grandparent', ['?who', 'charlie']);
 * ```
 */

import type {
  NocturnusAIConfig,
  Atom,
  ScoredAtom,
  ContextWindow,
  OptimizedContext,
  ContextDiff,
  ContextSummary,
  IngestAndOptimizeResult,
  TurnContextResult,
  ConsolidationResult,
  DecayResult,
  RuleHead,
  RuleBody,
  ProofTree,
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
  KnowledgeEvent,
  HealthStatus,
  NocturnusAIError,
  AuthStatus,
  CreateKeyOptions,
  CreateKeyResponse,
  KeyInfo,
  WhoAmI,
  SchemaDiscovery,
} from './types.js';

// ---------------------------------------------------------------------------
// Constants
// ---------------------------------------------------------------------------

/** Default maximum number of retry attempts for transient failures. */
const DEFAULT_MAX_RETRIES = 3;

/** Base delay in milliseconds for exponential backoff. */
const BASE_RETRY_DELAY_MS = 500;

/** HTTP status codes considered retryable. */
const RETRYABLE_STATUS_CODES = new Set([429, 502, 503, 504]);

// ---------------------------------------------------------------------------
// Error class
// ---------------------------------------------------------------------------

/**
 * Error thrown when an NocturnusAI API request fails.
 * Contains the HTTP status code and, when available, the structured error
 * response from the server.
 */
export class NocturnusAIRequestError extends Error {
  /** HTTP status code of the failed request. */
  public readonly statusCode: number;

  /** Structured error response from the server, if available. */
  public readonly error?: NocturnusAIError;

  constructor(message: string, statusCode: number, error?: NocturnusAIError) {
    super(message);
    this.name = 'NocturnusAIRequestError';
    this.statusCode = statusCode;
    this.error = error;
  }
}

// ---------------------------------------------------------------------------
// Client
// ---------------------------------------------------------------------------

/**
 * Main client for interacting with the NocturnusAI HTTP API.
 *
 * Provides typed methods for all core operations: asserting facts and rules,
 * querying, inference, retraction, memory management (context windows,
 * goal-driven optimization, diffs, temporal queries, consolidation, decay),
 * executing DSL commands, health checks, and SSE event subscriptions.
 *
 * All methods that communicate with the server return Promises and include
 * automatic retry logic with exponential backoff for transient failures
 * (HTTP 429, 502, 503, 504).
 */
export class NocturnusAIClient {
  private readonly baseUrl: string;
  private readonly apiKey?: string;
  private readonly database: string;
  private readonly tenantId: string;
  private readonly maxRetries: number;

  /**
   * Create a new NocturnusAIClient.
   *
   * @param config - Connection configuration.
   * @param maxRetries - Maximum number of retries for transient failures. Defaults to 3.
   */
  constructor(config: NocturnusAIConfig, maxRetries: number = DEFAULT_MAX_RETRIES) {
    // Strip trailing slash from base URL
    this.baseUrl = config.baseUrl.replace(/\/+$/, '');
    this.apiKey = config.apiKey;
    this.database = config.database ?? 'default';
    this.tenantId = config.tenantId ?? 'default';
    this.maxRetries = maxRetries;
  }

  // -----------------------------------------------------------------------
  // Fact operations
  // -----------------------------------------------------------------------

  /**
   * Assert a fact into the knowledge base.
   *
   * @param predicate - The predicate name (e.g. "parent", "likes").
   * @param args - Ordered list of arguments.
   * @param opts - Optional parameters: negated, scope, metadata, temporal fields, transactionId.
   * @returns Server confirmation message.
   *
   * @example
   * ```ts
   * await client.assertFact('parent', ['alice', 'bob']);
   * await client.assertFact('status', ['task_1', 'active'], { ttl: 3600000 });
   * ```
   */
  async assertFact(predicate: string, args: string[], opts?: FactOptions): Promise<string> {
    const body: Record<string, unknown> = {
      predicate,
      args,
      truthVal: opts?.negated ? false : true,
      negated: opts?.negated ?? false,
    };
    if (opts?.scope !== undefined) body.scope = opts.scope;
    if (opts?.metadata !== undefined) body.metadata = opts.metadata;
    if (opts?.validFrom !== undefined) body.validFrom = opts.validFrom;
    if (opts?.validUntil !== undefined) body.validUntil = opts.validUntil;
    if (opts?.ttl !== undefined) body.ttl = opts.ttl;

    const headers: Record<string, string> = {};
    if (opts?.transactionId !== undefined) {
      headers['X-Transaction-ID'] = String(opts.transactionId);
    }

    return this.requestText('POST', '/assert/fact', body, headers);
  }

  /**
   * Assert a logical rule (Horn clause) for inference.
   *
   * Rules enable multi-step deductive reasoning. The head is the consequent
   * and the body contains antecedent conditions. Use "?prefix" for variables.
   *
   * @param head - The consequent atom of the rule.
   * @param body - List of antecedent conditions.
   * @param opts - Optional parameters: scope, transactionId.
   * @returns Server confirmation message.
   *
   * @example
   * ```ts
   * await client.assertRule(
   *   { predicate: 'grandparent', args: ['?x', '?z'] },
   *   [
   *     { predicate: 'parent', args: ['?x', '?y'] },
   *     { predicate: 'parent', args: ['?y', '?z'] },
   *   ]
   * );
   * ```
   */
  async assertRule(head: RuleHead, body: RuleBody, opts?: RuleOptions): Promise<string> {
    const payload: Record<string, unknown> = {
      head: {
        predicate: head.predicate,
        args: head.args,
        negated: head.negated ?? false,
        scope: head.scope ?? null,
        metadata: head.metadata ?? {},
      },
      body: body.map((atom) => ({
        predicate: atom.predicate,
        args: atom.args,
        negated: atom.negated ?? false,
        scope: atom.scope ?? null,
        metadata: atom.metadata ?? {},
      })),
    };
    if (opts?.scope !== undefined) payload.scope = opts.scope;

    const headers: Record<string, string> = {};
    if (opts?.transactionId !== undefined) {
      headers['X-Transaction-ID'] = String(opts.transactionId);
    }

    return this.requestText('POST', '/assert/rule', payload, headers);
  }

  /**
   * Query facts matching a pattern in the knowledge base.
   *
   * Unlike {@link infer}, this only matches directly stored facts and does not
   * apply logical rules.
   *
   * @param predicate - The predicate to match.
   * @param args - Arguments. Use "?x", "?y" etc. for wildcard positions.
   * @param opts - Optional parameters: negated, scope.
   * @returns List of matching atoms.
   *
   * @example
   * ```ts
   * const facts = await client.query('parent', ['alice', '?child']);
   * ```
   */
  async query(predicate: string, args: string[], opts?: Pick<FactOptions, 'negated' | 'scope'>): Promise<Atom[]> {
    const body: Record<string, unknown> = {
      predicate,
      args,
      truthVal: opts?.negated ? false : true,
      negated: opts?.negated ?? false,
    };
    if (opts?.scope !== undefined) body.scope = opts.scope;

    return this.requestJson<Atom[]>('POST', '/infer', body);
  }

  /**
   * Run backward-chaining logical inference.
   *
   * Applies rules to derive new conclusions through multi-step deductive
   * reasoning (SLD resolution with unification). When `withProof` is true,
   * returns full proof trees showing the derivation chain.
   *
   * @param predicate - The goal predicate.
   * @param args - Goal arguments. Use "?prefix" for variables.
   * @param opts - Optional parameters: negated, scope, withProof.
   * @returns List of inferred atoms, or proof trees when withProof is true.
   *
   * @example
   * ```ts
   * // Simple inference
   * const results = await client.infer('grandparent', ['?who', 'charlie']);
   *
   * // With proof trees
   * const proofs = await client.infer('grandparent', ['?who', 'charlie'], { withProof: true });
   * ```
   */
  async infer(predicate: string, args: string[], opts?: InferOptions): Promise<Atom[] | ProofTree[]> {
    const body: Record<string, unknown> = {
      predicate,
      args,
      truthVal: opts?.negated ? false : true,
      negated: opts?.negated ?? false,
    };
    if (opts?.scope !== undefined) body.scope = opts.scope;

    const queryParams = opts?.withProof ? '?proof=true' : '';
    return this.requestJson<Atom[] | ProofTree[]>('POST', `/infer${queryParams}`, body);
  }

  /**
   * Retract (remove) a fact from the knowledge base.
   *
   * Triggers the Truth Maintenance System: any facts that were derived from
   * this fact will be automatically cascade-retracted.
   *
   * @param predicate - The predicate of the fact to retract.
   * @param args - Arguments of the fact to retract.
   * @param opts - Optional parameters: negated, scope, transactionId.
   * @returns Server confirmation message.
   *
   * @example
   * ```ts
   * await client.retract('parent', ['alice', 'bob']);
   * ```
   */
  async retract(predicate: string, args: string[], opts?: Pick<FactOptions, 'negated' | 'scope' | 'transactionId'>): Promise<string> {
    const body: Record<string, unknown> = {
      predicate,
      args,
      truthVal: opts?.negated ? false : true,
      negated: opts?.negated ?? false,
    };
    if (opts?.scope !== undefined) body.scope = opts.scope;

    const headers: Record<string, string> = {};
    if (opts?.transactionId !== undefined) {
      headers['X-Transaction-ID'] = String(opts.transactionId);
    }

    return this.requestText('POST', '/retract', body, headers);
  }

  // -----------------------------------------------------------------------
  // Memory / context operations
  // -----------------------------------------------------------------------

  /**
   * Get a salience-ranked context window for agent reasoning.
   *
   * Returns the most relevant facts based on a composite score of recency,
   * access frequency, and priority. Use this to efficiently populate an
   * agent's context with the most important knowledge.
   *
   * @param opts - Optional parameters: maxFacts, minSalience, predicates, scope.
   * @returns The context window with ranked facts and metadata.
   *
   * @deprecated Use {@link context} instead, which supports both simple
   * and advanced (goal-driven) modes via a single unified endpoint.
   *
   * @example
   * ```ts
   * const ctx = await client.contextWindow({ maxFacts: 50, minSalience: 0.1 });
   * console.log(`${ctx.windowSize} of ${ctx.totalAvailable} facts`);
   * ```
   */
  async contextWindow(opts?: ContextWindowOptions): Promise<ContextWindow> {
    const body: Record<string, unknown> = {};
    if (opts?.maxFacts !== undefined) body.maxFacts = opts.maxFacts;
    if (opts?.minSalience !== undefined) body.minSalience = opts.minSalience;
    if (opts?.predicates !== undefined) body.predicates = opts.predicates;
    if (opts?.scope !== undefined) body.scope = opts.scope;

    return this.requestJson<ContextWindow>('POST', '/memory/context', body);
  }

  /**
   * Get the optimal context for the current reasoning step.
   *
   * Simple usage returns facts ranked by salience. When goals, sessionId,
   * or relevanceBuckets are provided, the server uses its advanced
   * optimization engine with backward chaining, contradiction detection,
   * and session-based incremental diffing.
   *
   * @param opts - Context options (simple and/or advanced).
   * @returns Context window with ranked facts and optional advanced metadata.
   *
   * @example
   * ```ts
   * // Simple: just the most relevant facts
   * const ctx = await client.context({ maxFacts: 50 });
   *
   * // With LLM-friendly formatting
   * const ctx = await client.context({ maxFacts: 50, format: 'natural' });
   * console.log(ctx.formattedText);
   *
   * // Advanced: goal-driven with session tracking
   * const ctx = await client.context({
   *   goals: [{ predicate: 'recommend', args: ['?product'] }],
   *   sessionId: 'turn-3',
   *   format: 'natural',
   * });
   * ```
   */
  async context(opts?: ContextOptions): Promise<ContextWindow> {
    const body: Record<string, unknown> = {};
    if (opts?.maxFacts !== undefined) body.maxFacts = opts.maxFacts;
    if (opts?.minSalience !== undefined) body.minSalience = opts.minSalience;
    if (opts?.predicates !== undefined) body.predicates = opts.predicates;
    if (opts?.scope !== undefined) body.scope = opts.scope;
    if (opts?.format !== undefined) body.format = opts.format;
    if (opts?.includeRules !== undefined) body.includeRules = opts.includeRules;
    if (opts?.goals !== undefined) body.goals = opts.goals;
    if (opts?.sessionId !== undefined) body.sessionId = opts.sessionId;
    if (opts?.autoResolveContradictions !== undefined) {
      body.autoResolveContradictions = opts.autoResolveContradictions;
    }
    if (opts?.maxFactsPerPredicate !== undefined) {
      body.maxFactsPerPredicate = opts.maxFactsPerPredicate;
    }
    if (opts?.relevanceBuckets !== undefined) {
      body.relevanceBuckets = opts.relevanceBuckets;
    }

    return this.requestJson<ContextWindow>('POST', '/memory/context', body);
  }

  /**
   * Build a goal-driven optimized context window.
   *
   * Unlike {@link contextWindow}, this endpoint can use goals, weighted buckets,
   * contradiction handling, and snapshot storage for later diff calls.
   *
   * @param opts - Optimization options including goals, predicates, and sessionId.
   * @returns Optimized context window with selected entries and telemetry.
   *
   * @deprecated Use {@link context} with goals/sessionId parameters instead.
   * The unified endpoint at POST /memory/context now supports all optimization features.
   *
   * @example
   * ```ts
   * const ctx = await client.optimizeContext({
   *   goals: [{ predicate: 'eligible_for_sla', args: ['acme_corp'] }],
   *   maxFacts: 25,
   *   sessionId: 'session-42',
   * });
   * console.log(ctx.totalFactsIncluded, ctx.totalCharCount);
   * ```
   */
  async optimizeContext(opts?: OptimizeContextOptions): Promise<OptimizedContext> {
    const body: Record<string, unknown> = {};
    if (opts?.maxFacts !== undefined) body.maxFacts = opts.maxFacts;
    if (opts?.scope !== undefined) body.scope = opts.scope;
    if (opts?.predicates !== undefined) body.predicates = opts.predicates;
    if (opts?.goals !== undefined) body.goals = opts.goals;
    if (opts?.relevanceBuckets !== undefined) body.relevanceBuckets = opts.relevanceBuckets;
    if (opts?.sessionId !== undefined) body.sessionId = opts.sessionId;
    if (opts?.autoResolveContradictions !== undefined) {
      body.autoResolveContradictions = opts.autoResolveContradictions;
    }
    if (opts?.maxFactsPerPredicate !== undefined) {
      body.maxFactsPerPredicate = opts.maxFactsPerPredicate;
    }

    return this.requestJson<OptimizedContext>('POST', '/context/optimize', body);
  }

  /**
   * Get incremental changes since the last optimized context snapshot.
   *
   * @param opts - Diff options. Requires a sessionId from a prior optimizeContext() call.
   * @returns Added and removed entries since the previous snapshot.
   *
   * @example
   * ```ts
   * const diff = await client.diffContext({ sessionId: 'session-42', maxFacts: 25 });
   * console.log(diff.added.length, diff.removed.length);
   * ```
   */
  async diffContext(opts: DiffContextOptions): Promise<ContextDiff> {
    const body: Record<string, unknown> = {
      sessionId: opts.sessionId,
    };
    if (opts.maxFacts !== undefined) body.maxFacts = opts.maxFacts;
    if (opts.scope !== undefined) body.scope = opts.scope;
    if (opts.predicates !== undefined) body.predicates = opts.predicates;
    if (opts.goals !== undefined) body.goals = opts.goals;
    if (opts.relevanceBuckets !== undefined) body.relevanceBuckets = opts.relevanceBuckets;
    if (opts.autoResolveContradictions !== undefined) {
      body.autoResolveContradictions = opts.autoResolveContradictions;
    }
    if (opts.maxFactsPerPredicate !== undefined) {
      body.maxFactsPerPredicate = opts.maxFactsPerPredicate;
    }

    return this.requestJson<ContextDiff>('POST', '/context/diff', body);
  }

  /**
   * Summarize the current context store.
   *
   * @param scope - Optional scope filter.
   * @returns Aggregate context metrics and top salient entries.
   *
   * @example
   * ```ts
   * const summary = await client.summarizeContext();
   * console.log(summary.totalFacts, summary.contradictions);
   * ```
   */
  async summarizeContext(scope?: string): Promise<ContextSummary> {
    const body: Record<string, unknown> = {};
    if (scope !== undefined) body.scope = scope;

    return this.requestJson<ContextSummary>('POST', '/context/summary', body);
  }

  /**
   * Clear stored snapshot state for a context session.
   *
   * @param sessionId - Session ID created by optimizeContext().
   * @returns Server confirmation message.
   *
   * @example
   * ```ts
   * await client.clearContextSession('session-42');
   * ```
   */
  async clearContextSession(sessionId: string): Promise<string> {
    return this.requestText('POST', '/context/session/clear', { sessionId });
  }

  /**
   * Extract facts from raw text, assert them, and return an optimized context window.
   *
   * @param opts - Ingest text plus optimization options.
   * @returns Extracted facts and the optimized context window.
   *
   * @example
   * ```ts
   * const result = await client.ingestAndOptimize({
   *   text: 'Acme Corp is on the enterprise plan.',
   *   maxFacts: 10,
   * });
   * console.log(result.extracted.length, result.context.totalFactsIncluded);
   * ```
   */
  async ingestAndOptimize(opts: IngestAndOptimizeOptions): Promise<IngestAndOptimizeResult> {
    const body: Record<string, unknown> = {
      text: opts.text,
    };
    if (opts.goals !== undefined) body.goals = opts.goals;
    if (opts.maxFacts !== undefined) body.maxFacts = opts.maxFacts;
    if (opts.maxFactsPerPredicate !== undefined) {
      body.maxFactsPerPredicate = opts.maxFactsPerPredicate;
    }
    if (opts.autoResolveContradictions !== undefined) {
      body.autoResolveContradictions = opts.autoResolveContradictions;
    }
    if (opts.sessionId !== undefined) body.sessionId = opts.sessionId;
    if (opts.relevanceBuckets !== undefined) body.relevanceBuckets = opts.relevanceBuckets;
    if (opts.scope !== undefined) body.scope = opts.scope;
    if (opts.contextHint !== undefined) body.contextHint = opts.contextHint;

    return this.requestJson<IngestAndOptimizeResult>('POST', '/context/ingest', body);
  }

  /**
   * Process a batch of conversation turns into facts plus a delta briefing.
   *
   * Wraps `POST /context`. Extracts facts from each turn (LLM if configured,
   * otherwise predicate-syntax fallback), stores them under `scope`, and
   * returns the most-relevant facts as a single window. When `sessionId` is
   * supplied the server also tracks recent turns for the next call (so
   * pronouns resolve) and returns a `briefingDelta` of just the facts that
   * are new this turn.
   *
   * @param opts - Turns plus optional scope/sessionId/contextHint.
   * @returns Optimized window plus briefingDelta when a snapshot exists.
   *
   * @example
   * ```ts
   * // Turn 1
   * await client.processTurns({
   *   turns: ["User: We can't log in after the Okta cutover."],
   *   scope: 'ticket-4821',
   *   sessionId: 'ticket-4821',
   * });
   *
   * // Turn 2 — server pulls turn 1 as contextHint, returns delta
   * const result = await client.processTurns({
   *   turns: ['Tool auth_audit: issuer mismatch detected.'],
   *   scope: 'ticket-4821',
   *   sessionId: 'ticket-4821',
   * });
   * console.log(result.briefingDelta);
   * ```
   */
  async processTurns(opts: ProcessTurnsOptions): Promise<TurnContextResult> {
    const body: Record<string, unknown> = { turns: opts.turns };
    if (opts.maxFacts !== undefined) body.maxFacts = opts.maxFacts;
    if (opts.scope !== undefined) body.scope = opts.scope;
    if (opts.sessionId !== undefined) body.sessionId = opts.sessionId;
    if (opts.contextHint !== undefined) body.contextHint = opts.contextHint;

    return this.requestJson<TurnContextResult>('POST', '/context', body);
  }

  /**
   * Query facts that were valid at a specific point in time.
   *
   * Useful for historical reasoning: "What was true at timestamp T?"
   * Filters by validFrom, validUntil, and TTL.
   *
   * @param predicate - The predicate to query.
   * @param args - Arguments. Use "?prefix" for variables.
   * @param timestamp - Epoch milliseconds for the point-in-time query.
   * @param opts - Optional parameters: scope.
   * @returns List of atoms that were valid at the given timestamp.
   *
   * @example
   * ```ts
   * const pastFacts = await client.temporalQuery(
   *   'location', ['?user', '?place'],
   *   Date.now() - 86400000 // 24 hours ago
   * );
   * ```
   */
  async temporalQuery(
    predicate: string,
    args: string[],
    timestamp: number,
    opts?: Pick<FactOptions, 'scope'>,
  ): Promise<Atom[]> {
    const body: Record<string, unknown> = {
      predicate,
      args,
      timestamp,
    };
    if (opts?.scope !== undefined) body.scope = opts.scope;

    return this.requestJson<Atom[]>('POST', '/memory/query/temporal', body);
  }

  /**
   * Run memory consolidation.
   *
   * Detects repeated episodic patterns and compresses them into semantic
   * summary facts. Helps manage memory growth for long-running agent sessions.
   *
   * @returns Consolidation result with counts and new summary facts.
   *
   * @example
   * ```ts
   * const result = await client.consolidate();
   * console.log(`Consolidated ${result.factsConsolidated} patterns`);
   * ```
   */
  async consolidate(): Promise<ConsolidationResult> {
    return this.requestJson<ConsolidationResult>('POST', '/memory/consolidate', {});
  }

  /**
   * Run memory decay: expire TTL'd facts and evict low-salience facts.
   *
   * Essential for long-running agent sessions to prevent unbounded memory growth.
   *
   * @param threshold - Optional salience threshold below which facts are evicted.
   *                    Defaults to the server's configured threshold (typically 0.05).
   * @returns Decay result with counts and list of removed atoms.
   *
   * @example
   * ```ts
   * const result = await client.decay(0.1);
   * console.log(`Removed ${result.expiredCount + result.evictedCount} facts`);
   * ```
   */
  async decay(threshold?: number): Promise<DecayResult> {
    const body: Record<string, unknown> = {};
    if (threshold !== undefined) body.threshold = threshold;

    return this.requestJson<DecayResult>('POST', '/memory/decay', body);
  }

  /**
   * Set the salience priority for a specific fact.
   *
   * Manually boost or suppress a fact's priority, which affects its ranking
   * in context windows and its susceptibility to decay eviction.
   *
   * @param predicate - The predicate of the fact.
   * @param args - Arguments of the fact.
   * @param priority - Priority value from 0.0 to 1.0.
   * @param opts - Optional parameters: scope.
   * @returns Server confirmation message.
   *
   * @example
   * ```ts
   * await client.setPriority('user_preference', ['alice', 'dark_mode'], 0.9);
   * ```
   */
  async setPriority(
    predicate: string,
    args: string[],
    priority: number,
    opts?: Pick<FactOptions, 'scope'>,
  ): Promise<string> {
    const body: Record<string, unknown> = {
      predicate,
      args,
      priority,
      truthVal: true,
    };
    if (opts?.scope !== undefined) body.scope = opts.scope;

    return this.requestText('POST', '/memory/priority', body);
  }

  // -----------------------------------------------------------------------
  // Transaction operations
  // -----------------------------------------------------------------------

  /**
   * Begin an ACID transaction.
   *
   * Returns a transaction ID that can be passed to {@link assertFact},
   * {@link assertRule}, and {@link retract} via the `transactionId` option
   * to buffer operations within the transaction.
   *
   * @returns The transaction ID string.
   *
   * @example
   * ```ts
   * const txId = await client.beginTransaction();
   * await client.assertFact('parent', ['alice', 'bob'], { transactionId: Number(txId) });
   * await client.commitTransaction(txId);
   * ```
   */
  async beginTransaction(): Promise<string> {
    return this.requestText('POST', '/tx/begin');
  }

  /**
   * Commit a transaction, making all buffered operations permanent.
   *
   * @param transactionId - The transaction ID from {@link beginTransaction}.
   * @returns Server confirmation message.
   *
   * @example
   * ```ts
   * await client.commitTransaction(txId);
   * ```
   */
  async commitTransaction(transactionId: string): Promise<string> {
    return this.requestText('POST', `/tx/commit/${transactionId}`);
  }

  /**
   * Rollback a transaction, discarding all buffered operations.
   *
   * @param transactionId - The transaction ID from {@link beginTransaction}.
   * @returns Server confirmation message.
   *
   * @example
   * ```ts
   * await client.rollbackTransaction(txId);
   * ```
   */
  async rollbackTransaction(transactionId: string): Promise<string> {
    return this.requestText('POST', `/tx/rollback/${transactionId}`);
  }

  // -----------------------------------------------------------------------
  // Database management
  // -----------------------------------------------------------------------

  /**
   * Create a database on the server.
   *
   * @param name - Database name. Defaults to the client's configured database.
   * @returns Server confirmation message.
   * @throws {NocturnusAIRequestError} If the database already exists (HTTP 409).
   *
   * @example
   * ```ts
   * await client.createDatabase('my-new-db');
   * ```
   */
  async createDatabase(name?: string): Promise<string> {
    const dbName = name ?? this.database;
    return this.requestText('POST', '/admin/databases', { name: dbName });
  }

  /**
   * Create the database if it does not already exist.
   *
   * This is safe to call unconditionally — it silently succeeds when the
   * database is already present.
   *
   * @param name - Database name. Defaults to the client's configured database.
   *
   * @example
   * ```ts
   * await client.ensureDatabase(); // uses the client's configured database
   * ```
   */
  async ensureDatabase(name?: string): Promise<void> {
    try {
      await this.createDatabase(name);
    } catch (err) {
      // Suppress "already exists" errors (HTTP 409 Conflict)
      if (err instanceof NocturnusAIRequestError && err.statusCode === 409) {
        return;
      }
      throw err;
    }
  }

  // -----------------------------------------------------------------------
  // DSL execution
  // -----------------------------------------------------------------------

  /**
   * Execute a Logiql DSL command.
   *
   * The Logiql DSL provides a concise text-based syntax for asserting facts,
   * rules, and running queries.
   *
   * @param command - The DSL command string.
   * @returns The execution result as a string.
   *
   * @example
   * ```ts
   * const result = await client.execute('ASSERT parent(alice, bob).');
   * ```
   */
  async execute(command: string): Promise<string> {
    const response = await this.requestJson<{ result: string }>('POST', '/execute', { command });
    return response.result;
  }

  // -----------------------------------------------------------------------
  // Observability
  // -----------------------------------------------------------------------

  /**
   * Check the health of the NocturnusAI server.
   *
   * @returns Health status object with at least a "status" field.
   *
   * @example
   * ```ts
   * const health = await client.health();
   * console.log(health.status); // "healthy" or "unhealthy"
   * ```
   */
  async health(): Promise<HealthStatus> {
    return this.requestJson<HealthStatus>('GET', '/health');
  }

  // -----------------------------------------------------------------------
  // Schema discovery
  // -----------------------------------------------------------------------

  /**
   * Discover the knowledge base schema.
   *
   * Lists all predicates (relationship types) currently stored, with
   * argument counts and fact counts.
   *
   * @param scope - Optional scope filter.
   * @returns Schema discovery response with predicates and counts.
   *
   * @example
   * ```ts
   * const schema = await client.predicates();
   * for (const p of schema.predicates) {
   *   console.log(`${p.predicate}/${p.arity} — ${p.factCount} facts`);
   * }
   * ```
   */
  async predicates(scope?: string): Promise<SchemaDiscovery> {
    const queryParams = scope ? `?scope=${encodeURIComponent(scope)}` : '';
    return this.requestJson<SchemaDiscovery>('GET', `/predicates${queryParams}`);
  }

  // -----------------------------------------------------------------------
  // Auth / key management
  // -----------------------------------------------------------------------

  /**
   * Check the authentication status and mode of the server.
   *
   * @returns Auth status including mode and whether keys exist.
   *
   * @example
   * ```ts
   * const status = await client.authStatus();
   * console.log(`Auth mode: ${status.mode}`);
   * ```
   */
  async authStatus(): Promise<AuthStatus> {
    return this.requestJson<AuthStatus>('GET', '/auth/status');
  }

  /**
   * Bootstrap the first admin API key.
   *
   * Only works when RBAC auth is enabled and no keys exist yet.
   *
   * @param name - Name for the admin key. Defaults to "admin".
   * @param description - Description for the key.
   * @returns The created key info including the raw key (shown only once).
   *
   * @example
   * ```ts
   * const result = await client.bootstrap('my-admin');
   * console.log(`Save this key: ${result.key}`);
   * ```
   */
  async bootstrap(name: string = 'admin', description: string = 'Initial admin key'): Promise<CreateKeyResponse> {
    return this.requestJson<CreateKeyResponse>('POST', '/auth/bootstrap', { name, description });
  }

  /**
   * Create a new API key. Requires ADMIN role.
   *
   * @param opts - Key creation options (name, role, optional scoping).
   * @returns The created key info including the raw key (shown only once).
   *
   * @example
   * ```ts
   * const key = await client.createKey({
   *   name: 'agent-writer',
   *   role: 'writer',
   *   databases: ['prod'],
   * });
   * console.log(`New key: ${key.key}`);
   * ```
   */
  async createKey(opts: CreateKeyOptions): Promise<CreateKeyResponse> {
    return this.requestJson<CreateKeyResponse>('POST', '/auth/keys', opts);
  }

  /**
   * List all API keys. Requires ADMIN role.
   *
   * @returns List of key info objects (without raw keys).
   *
   * @example
   * ```ts
   * const keys = await client.listKeys();
   * for (const key of keys) {
   *   console.log(`${key.name} (${key.role}) — ${key.prefix}`);
   * }
   * ```
   */
  async listKeys(): Promise<KeyInfo[]> {
    return this.requestJson<KeyInfo[]>('GET', '/auth/keys');
  }

  /**
   * Revoke (delete) an API key. Requires ADMIN role.
   *
   * @param keyId - The ID of the key to revoke.
   * @returns Server confirmation message.
   *
   * @example
   * ```ts
   * await client.revokeKey('some-uuid');
   * ```
   */
  async revokeKey(keyId: string): Promise<string> {
    return this.requestText('DELETE', `/auth/keys/${keyId}`);
  }

  /**
   * Get information about the currently authenticated key.
   *
   * @returns Key identity including name, role, and permissions.
   *
   * @example
   * ```ts
   * const me = await client.whoami();
   * console.log(`Authenticated as: ${me.name} (${me.role})`);
   * ```
   */
  async whoami(): Promise<WhoAmI> {
    return this.requestJson<WhoAmI>('GET', '/auth/whoami');
  }

  // -----------------------------------------------------------------------
  // SSE event subscription
  // -----------------------------------------------------------------------

  /**
   * Subscribe to real-time knowledge change events via Server-Sent Events (SSE).
   *
   * Returns an unsubscribe function that closes the connection when called.
   * Requires a runtime with EventSource support (Node.js 18+ with a polyfill,
   * or any modern browser).
   *
   * @param opts - Subscription options: predicate filter, event types, sinceId.
   * @param callback - Function called for each received event.
   * @returns An unsubscribe function that terminates the SSE connection.
   *
   * @example
   * ```ts
   * const unsubscribe = client.subscribeEvents(
   *   { events: ['fact_asserted', 'fact_retracted'] },
   *   (event) => console.log('Event:', event.type, event.atom)
   * );
   *
   * // Later, to stop listening:
   * unsubscribe();
   * ```
   */
  subscribeEvents(
    opts: EventSubscriptionOptions,
    callback: (event: KnowledgeEvent) => void,
  ): () => void {
    const params = new URLSearchParams();
    if (opts.predicate) params.set('predicate', opts.predicate);
    if (opts.events && opts.events.length > 0) params.set('events', opts.events.join(','));
    if (opts.sinceId !== undefined) params.set('since', String(opts.sinceId));

    const queryString = params.toString();
    const url = `${this.baseUrl}/memory/events${queryString ? '?' + queryString : ''}`;

    // Use AbortController for clean teardown
    const controller = new AbortController();

    // Start the SSE connection asynchronously
    this.connectSSE(url, controller.signal, callback).catch((err) => {
      // Silently ignore abort errors
      if (err instanceof Error && err.name === 'AbortError') return;
      console.error('[NocturnusAIClient] SSE connection error:', err);
    });

    // Return unsubscribe function
    return () => {
      controller.abort();
    };
  }

  // -----------------------------------------------------------------------
  // Private: SSE connection
  // -----------------------------------------------------------------------

  /**
   * Connect to an SSE endpoint using fetch and process the stream.
   */
  private async connectSSE(
    url: string,
    signal: AbortSignal,
    callback: (event: KnowledgeEvent) => void,
  ): Promise<void> {
    const headers = this.buildHeaders();
    // SSE requires Accept: text/event-stream
    headers['Accept'] = 'text/event-stream';
    // Remove Content-Type since this is a GET request
    delete headers['Content-Type'];

    const response = await fetch(url, {
      method: 'GET',
      headers,
      signal,
    });

    if (!response.ok) {
      throw new NocturnusAIRequestError(
        `SSE connection failed: ${response.status} ${response.statusText}`,
        response.status,
      );
    }

    const body = response.body;
    if (!body) {
      throw new Error('SSE response has no body');
    }

    const reader = body.getReader();
    const decoder = new TextDecoder();
    let buffer = '';

    try {
      while (true) {
        const { done, value } = await reader.read();
        if (done) break;

        buffer += decoder.decode(value, { stream: true });
        const lines = buffer.split('\n');
        // Keep the last potentially incomplete line in the buffer
        buffer = lines.pop() ?? '';

        for (const line of lines) {
          if (line.startsWith('data: ')) {
            const data = line.slice(6).trim();
            if (data) {
              try {
                const event = JSON.parse(data) as KnowledgeEvent;
                callback(event);
              } catch {
                // Skip malformed JSON
              }
            }
          }
          // Ignore comments (": keepalive") and empty lines
        }
      }
    } finally {
      reader.releaseLock();
    }
  }

  // -----------------------------------------------------------------------
  // Private: HTTP helpers
  // -----------------------------------------------------------------------

  /**
   * Build the standard headers for every request.
   */
  private buildHeaders(extra?: Record<string, string>): Record<string, string> {
    const headers: Record<string, string> = {
      'Content-Type': 'application/json',
      'X-Database': this.database,
      'X-Tenant-ID': this.tenantId,
    };
    if (this.apiKey) {
      headers['X-API-Key'] = this.apiKey;
    }
    if (extra) {
      Object.assign(headers, extra);
    }
    return headers;
  }

  /**
   * Perform an HTTP request expecting a JSON response.
   * Includes retry logic with exponential backoff for transient failures.
   */
  private async requestJson<T>(
    method: string,
    path: string,
    body?: unknown,
    extraHeaders?: Record<string, string>,
  ): Promise<T> {
    const response = await this.fetchWithRetry(method, path, body, extraHeaders);
    const text = await response.text();

    try {
      return JSON.parse(text) as T;
    } catch {
      // If JSON parsing fails, check if this is an error response
      if (!response.ok) {
        throw new NocturnusAIRequestError(
          `Request failed: ${response.status} ${response.statusText} - ${text}`,
          response.status,
        );
      }
      throw new Error(`Failed to parse JSON response: ${text}`);
    }
  }

  /**
   * Perform an HTTP request expecting a plain text response.
   * Includes retry logic with exponential backoff for transient failures.
   */
  private async requestText(
    method: string,
    path: string,
    body?: unknown,
    extraHeaders?: Record<string, string>,
  ): Promise<string> {
    const response = await this.fetchWithRetry(method, path, body, extraHeaders);
    const text = await response.text();

    if (!response.ok) {
      let error: NocturnusAIError | undefined;
      try {
        error = JSON.parse(text) as NocturnusAIError;
      } catch {
        // Not a JSON error response
      }
      throw new NocturnusAIRequestError(
        error?.message ?? `Request failed: ${response.status} ${response.statusText}`,
        response.status,
        error,
      );
    }

    return text;
  }

  /**
   * Execute a fetch request with automatic retry and exponential backoff
   * for transient failures (HTTP 429, 502, 503, 504).
   */
  private async fetchWithRetry(
    method: string,
    path: string,
    body?: unknown,
    extraHeaders?: Record<string, string>,
  ): Promise<Response> {
    const url = `${this.baseUrl}${path}`;
    const headers = this.buildHeaders(extraHeaders);

    const fetchOptions: RequestInit = {
      method,
      headers,
    };

    if (body !== undefined && method !== 'GET') {
      fetchOptions.body = JSON.stringify(body);
    }

    let lastError: Error | undefined;

    for (let attempt = 0; attempt <= this.maxRetries; attempt++) {
      try {
        const response = await fetch(url, fetchOptions);

        // If the response is not retryable, return immediately (even if it's an error)
        if (!RETRYABLE_STATUS_CODES.has(response.status) || attempt === this.maxRetries) {
          return response;
        }

        // Retryable status code: apply backoff and retry
        const retryAfter = response.headers.get('Retry-After');
        const delayMs = retryAfter
          ? parseInt(retryAfter, 10) * 1000
          : BASE_RETRY_DELAY_MS * Math.pow(2, attempt) + Math.random() * 100;

        await this.sleep(delayMs);
      } catch (err) {
        lastError = err instanceof Error ? err : new Error(String(err));

        // Network errors are retryable
        if (attempt === this.maxRetries) {
          throw lastError;
        }

        const delayMs = BASE_RETRY_DELAY_MS * Math.pow(2, attempt) + Math.random() * 100;
        await this.sleep(delayMs);
      }
    }

    // This should be unreachable, but TypeScript needs it
    throw lastError ?? new Error('Request failed after retries');
  }

  /**
   * Sleep for a given number of milliseconds.
   */
  private sleep(ms: number): Promise<void> {
    return new Promise((resolve) => setTimeout(resolve, ms));
  }
}
