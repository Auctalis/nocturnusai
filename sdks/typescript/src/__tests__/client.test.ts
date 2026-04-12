/**
 * Tests for NocturnusAIClient.
 *
 * All HTTP requests are intercepted via a global fetch mock so no server is
 * needed. Retry delays are fast-forwarded using Vitest's fake timer support.
 */

import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { NocturnusAIClient, NocturnusAIRequestError } from '../client.js';

// ---------------------------------------------------------------------------
// Fetch mock helpers
// ---------------------------------------------------------------------------

const mockFetch = vi.fn();
vi.stubGlobal('fetch', mockFetch);

/** Build a minimal Response-shaped object accepted by the client. */
function mockResponse(body: unknown, status = 200): Response {
  return {
    ok: status >= 200 && status < 300,
    status,
    statusText: status === 200 ? 'OK' : 'Error',
    headers: new Headers(),
    text: async () => (typeof body === 'string' ? body : JSON.stringify(body)),
    json: async () => (typeof body === 'string' ? JSON.parse(body) : body),
  } as Response;
}

/** Extract the parsed JSON body from the last fetch call. */
function lastBody(): unknown {
  const call = mockFetch.mock.calls[mockFetch.mock.calls.length - 1];
  const init = call[1] as RequestInit;
  return JSON.parse(init.body as string);
}

/** Extract the headers from the last fetch call. */
function lastHeaders(): Record<string, string> {
  const call = mockFetch.mock.calls[mockFetch.mock.calls.length - 1];
  const init = call[1] as RequestInit;
  return init.headers as Record<string, string>;
}

/** Extract the URL from the last fetch call. */
function lastUrl(): string {
  const call = mockFetch.mock.calls[mockFetch.mock.calls.length - 1];
  return call[0] as string;
}

/** Extract the HTTP method from the last fetch call. */
function lastMethod(): string {
  const call = mockFetch.mock.calls[mockFetch.mock.calls.length - 1];
  const init = call[1] as RequestInit;
  return init.method as string;
}

// ---------------------------------------------------------------------------
// Test setup
// ---------------------------------------------------------------------------

beforeEach(() => {
  mockFetch.mockReset();
  // Suppress real timers by default; retry tests override this
  vi.useFakeTimers();
});

afterEach(() => {
  vi.useRealTimers();
});

// ---------------------------------------------------------------------------
// Helper: a pre-built client used by most tests
// ---------------------------------------------------------------------------

function makeClient(overrides?: Partial<ConstructorParameters<typeof NocturnusAIClient>[0]>) {
  return new NocturnusAIClient(
    {
      baseUrl: 'http://localhost:9300',
      database: 'testdb',
      tenantId: 'tenant1',
      apiKey: 'secret-key',
      ...overrides,
    },
    0, // maxRetries=0 keeps tests instant unless explicitly testing retries
  );
}

// ---------------------------------------------------------------------------
// Configuration
// ---------------------------------------------------------------------------

describe('Configuration', () => {
  it('strips a trailing slash from baseUrl', async () => {
    const client = new NocturnusAIClient({ baseUrl: 'http://localhost:9300/', database: 'db' }, 0);
    mockFetch.mockResolvedValueOnce(mockResponse({ status: 'healthy' }));
    await client.health();
    expect(lastUrl()).toBe('http://localhost:9300/health');
  });

  it('strips multiple trailing slashes from baseUrl', async () => {
    const client = new NocturnusAIClient({ baseUrl: 'http://localhost:9300///' }, 0);
    mockFetch.mockResolvedValueOnce(mockResponse({ status: 'healthy' }));
    await client.health();
    expect(lastUrl()).toBe('http://localhost:9300/health');
  });

  it('defaults database to "default" when not provided', async () => {
    const client = new NocturnusAIClient({ baseUrl: 'http://localhost:9300' }, 0);
    mockFetch.mockResolvedValueOnce(mockResponse('OK'));
    await client.assertFact('x', ['a']);
    expect(lastHeaders()['X-Database']).toBe('default');
  });

  it('defaults tenantId to "default" when not provided', async () => {
    const client = new NocturnusAIClient({ baseUrl: 'http://localhost:9300' }, 0);
    mockFetch.mockResolvedValueOnce(mockResponse('OK'));
    await client.assertFact('x', ['a']);
    expect(lastHeaders()['X-Tenant-ID']).toBe('default');
  });

  it('uses provided database and tenantId', async () => {
    mockFetch.mockResolvedValueOnce(mockResponse('OK'));
    await makeClient().assertFact('x', ['a']);
    expect(lastHeaders()['X-Database']).toBe('testdb');
    expect(lastHeaders()['X-Tenant-ID']).toBe('tenant1');
  });
});

// ---------------------------------------------------------------------------
// Headers
// ---------------------------------------------------------------------------

describe('Headers', () => {
  it('includes X-Database on every request', async () => {
    mockFetch.mockResolvedValueOnce(mockResponse({ status: 'healthy' }));
    await makeClient().health();
    expect(lastHeaders()['X-Database']).toBe('testdb');
  });

  it('includes X-Tenant-ID on every request', async () => {
    mockFetch.mockResolvedValueOnce(mockResponse({ status: 'healthy' }));
    await makeClient().health();
    expect(lastHeaders()['X-Tenant-ID']).toBe('tenant1');
  });

  it('includes X-API-Key when apiKey is provided', async () => {
    mockFetch.mockResolvedValueOnce(mockResponse({ status: 'healthy' }));
    await makeClient().health();
    expect(lastHeaders()['X-API-Key']).toBe('secret-key');
  });

  it('omits X-API-Key when no apiKey is provided', async () => {
    const client = makeClient({ apiKey: undefined });
    mockFetch.mockResolvedValueOnce(mockResponse({ status: 'healthy' }));
    await client.health();
    expect(lastHeaders()['X-API-Key']).toBeUndefined();
  });

  it('includes Content-Type application/json on POST requests', async () => {
    mockFetch.mockResolvedValueOnce(mockResponse('OK'));
    await makeClient().assertFact('x', ['a']);
    expect(lastHeaders()['Content-Type']).toBe('application/json');
  });
});

// ---------------------------------------------------------------------------
// assertFact
// ---------------------------------------------------------------------------

describe('assertFact', () => {
  it('POSTs to /assert/fact with predicate and args', async () => {
    mockFetch.mockResolvedValueOnce(mockResponse('Asserted.'));
    const result = await makeClient().assertFact('parent', ['alice', 'bob']);
    expect(lastUrl()).toBe('http://localhost:9300/assert/fact');
    expect(lastMethod()).toBe('POST');
    expect(lastBody()).toMatchObject({ predicate: 'parent', args: ['alice', 'bob'] });
    expect(result).toBe('Asserted.');
  });

  it('sets truthVal=true and negated=false by default', async () => {
    mockFetch.mockResolvedValueOnce(mockResponse('OK'));
    await makeClient().assertFact('likes', ['alice', 'bob']);
    expect(lastBody()).toMatchObject({ truthVal: true, negated: false });
  });

  it('sets truthVal=false and negated=true when opts.negated is true', async () => {
    mockFetch.mockResolvedValueOnce(mockResponse('OK'));
    await makeClient().assertFact('likes', ['alice', 'bob'], { negated: true });
    expect(lastBody()).toMatchObject({ truthVal: false, negated: true });
  });

  it('includes scope in body when provided', async () => {
    mockFetch.mockResolvedValueOnce(mockResponse('OK'));
    await makeClient().assertFact('likes', ['a'], { scope: 'hypothesis' });
    expect(lastBody()).toMatchObject({ scope: 'hypothesis' });
  });

  it('omits scope when not provided', async () => {
    mockFetch.mockResolvedValueOnce(mockResponse('OK'));
    await makeClient().assertFact('likes', ['a']);
    expect((lastBody() as Record<string, unknown>)['scope']).toBeUndefined();
  });

  it('includes ttl in body when provided', async () => {
    mockFetch.mockResolvedValueOnce(mockResponse('OK'));
    await makeClient().assertFact('status', ['x'], { ttl: 3600000 });
    expect(lastBody()).toMatchObject({ ttl: 3600000 });
  });

  it('includes metadata in body when provided', async () => {
    mockFetch.mockResolvedValueOnce(mockResponse('OK'));
    await makeClient().assertFact('x', ['a'], { metadata: { source: 'agent' } });
    expect(lastBody()).toMatchObject({ metadata: { source: 'agent' } });
  });

  it('includes validFrom and validUntil when provided', async () => {
    mockFetch.mockResolvedValueOnce(mockResponse('OK'));
    await makeClient().assertFact('x', ['a'], { validFrom: 1000, validUntil: 2000 });
    expect(lastBody()).toMatchObject({ validFrom: 1000, validUntil: 2000 });
  });

  it('adds X-Transaction-ID header when transactionId is provided', async () => {
    mockFetch.mockResolvedValueOnce(mockResponse('OK'));
    await makeClient().assertFact('x', ['a'], { transactionId: 42 });
    expect(lastHeaders()['X-Transaction-ID']).toBe('42');
  });

  it('omits X-Transaction-ID header when transactionId is not provided', async () => {
    mockFetch.mockResolvedValueOnce(mockResponse('OK'));
    await makeClient().assertFact('x', ['a']);
    expect(lastHeaders()['X-Transaction-ID']).toBeUndefined();
  });
});

// ---------------------------------------------------------------------------
// assertRule
// ---------------------------------------------------------------------------

describe('assertRule', () => {
  it('POSTs to /assert/rule with head and body', async () => {
    mockFetch.mockResolvedValueOnce(mockResponse('Rule asserted.'));
    const result = await makeClient().assertRule(
      { predicate: 'grandparent', args: ['?x', '?z'] },
      [
        { predicate: 'parent', args: ['?x', '?y'] },
        { predicate: 'parent', args: ['?y', '?z'] },
      ],
    );
    expect(lastUrl()).toBe('http://localhost:9300/assert/rule');
    expect(lastMethod()).toBe('POST');
    const body = lastBody() as Record<string, unknown>;
    expect((body.head as Record<string, unknown>).predicate).toBe('grandparent');
    expect((body.head as Record<string, unknown>).args).toEqual(['?x', '?z']);
    expect(Array.isArray(body.body)).toBe(true);
    expect((body.body as unknown[]).length).toBe(2);
    expect(result).toBe('Rule asserted.');
  });

  it('normalises head with negated=false and scope=null when omitted', async () => {
    mockFetch.mockResolvedValueOnce(mockResponse('OK'));
    await makeClient().assertRule({ predicate: 'foo', args: ['?x'] }, []);
    const head = (lastBody() as Record<string, unknown>).head as Record<string, unknown>;
    expect(head.negated).toBe(false);
    expect(head.scope).toBeNull();
    expect(head.metadata).toEqual({});
  });

  it('includes scope in payload when opts.scope is provided', async () => {
    mockFetch.mockResolvedValueOnce(mockResponse('OK'));
    await makeClient().assertRule({ predicate: 'foo', args: [] }, [], { scope: 'draft' });
    expect(lastBody()).toMatchObject({ scope: 'draft' });
  });

  it('adds X-Transaction-ID header when transactionId is provided', async () => {
    mockFetch.mockResolvedValueOnce(mockResponse('OK'));
    await makeClient().assertRule({ predicate: 'foo', args: [] }, [], { transactionId: 7 });
    expect(lastHeaders()['X-Transaction-ID']).toBe('7');
  });
});

// ---------------------------------------------------------------------------
// query
// ---------------------------------------------------------------------------

describe('query', () => {
  it('POSTs to /infer and returns Atom[]', async () => {
    const atoms = [{ predicate: 'parent', args: ['alice', 'bob'] }];
    mockFetch.mockResolvedValueOnce(mockResponse(atoms));
    const result = await makeClient().query('parent', ['alice', '?child']);
    expect(lastUrl()).toBe('http://localhost:9300/infer');
    expect(lastMethod()).toBe('POST');
    expect(result).toEqual(atoms);
  });

  it('includes predicate and args in body', async () => {
    mockFetch.mockResolvedValueOnce(mockResponse([]));
    await makeClient().query('likes', ['?x', '?y']);
    expect(lastBody()).toMatchObject({ predicate: 'likes', args: ['?x', '?y'] });
  });

  it('includes scope in body when provided', async () => {
    mockFetch.mockResolvedValueOnce(mockResponse([]));
    await makeClient().query('likes', ['?x'], { scope: 'test' });
    expect(lastBody()).toMatchObject({ scope: 'test' });
  });
});

// ---------------------------------------------------------------------------
// infer
// ---------------------------------------------------------------------------

describe('infer', () => {
  it('POSTs to /infer without query param by default', async () => {
    mockFetch.mockResolvedValueOnce(mockResponse([]));
    await makeClient().infer('grandparent', ['?who', 'charlie']);
    expect(lastUrl()).toBe('http://localhost:9300/infer');
  });

  it('appends ?proof=true when withProof option is set', async () => {
    mockFetch.mockResolvedValueOnce(mockResponse([]));
    await makeClient().infer('grandparent', ['?who', 'charlie'], { withProof: true });
    expect(lastUrl()).toBe('http://localhost:9300/infer?proof=true');
  });

  it('does NOT append ?proof=true when withProof is false', async () => {
    mockFetch.mockResolvedValueOnce(mockResponse([]));
    await makeClient().infer('grandparent', ['?who', 'charlie'], { withProof: false });
    expect(lastUrl()).toBe('http://localhost:9300/infer');
  });

  it('includes negated and scope in body when provided', async () => {
    mockFetch.mockResolvedValueOnce(mockResponse([]));
    await makeClient().infer('x', ['a'], { negated: true, scope: 'hyp' });
    expect(lastBody()).toMatchObject({ truthVal: false, negated: true, scope: 'hyp' });
  });
});

// ---------------------------------------------------------------------------
// retract
// ---------------------------------------------------------------------------

describe('retract', () => {
  it('POSTs to /retract with predicate and args', async () => {
    mockFetch.mockResolvedValueOnce(mockResponse('Retracted.'));
    const result = await makeClient().retract('parent', ['alice', 'bob']);
    expect(lastUrl()).toBe('http://localhost:9300/retract');
    expect(lastMethod()).toBe('POST');
    expect(lastBody()).toMatchObject({ predicate: 'parent', args: ['alice', 'bob'] });
    expect(result).toBe('Retracted.');
  });

  it('includes scope in body when provided', async () => {
    mockFetch.mockResolvedValueOnce(mockResponse('OK'));
    await makeClient().retract('x', ['a'], { scope: 'hyp' });
    expect(lastBody()).toMatchObject({ scope: 'hyp' });
  });

  it('adds X-Transaction-ID header when transactionId provided', async () => {
    mockFetch.mockResolvedValueOnce(mockResponse('OK'));
    await makeClient().retract('x', ['a'], { transactionId: 99 });
    expect(lastHeaders()['X-Transaction-ID']).toBe('99');
  });
});

// ---------------------------------------------------------------------------
// contextWindow
// ---------------------------------------------------------------------------

describe('contextWindow', () => {
  it('POSTs to /memory/context with empty body when no opts', async () => {
    const ctx = {
      facts: [],
      totalAvailable: 0,
      windowSize: 0,
      predicateDistribution: {},
      generatedAt: 1000,
    };
    mockFetch.mockResolvedValueOnce(mockResponse(ctx));
    const result = await makeClient().contextWindow();
    expect(lastUrl()).toBe('http://localhost:9300/memory/context');
    expect(lastMethod()).toBe('POST');
    expect(result).toEqual(ctx);
  });

  it('includes maxFacts and minSalience in body when provided', async () => {
    mockFetch.mockResolvedValueOnce(mockResponse({ facts: [], totalAvailable: 0, windowSize: 0, predicateDistribution: {}, generatedAt: 0 }));
    await makeClient().contextWindow({ maxFacts: 50, minSalience: 0.1 });
    expect(lastBody()).toMatchObject({ maxFacts: 50, minSalience: 0.1 });
  });

  it('includes predicates filter in body when provided', async () => {
    mockFetch.mockResolvedValueOnce(mockResponse({ facts: [], totalAvailable: 0, windowSize: 0, predicateDistribution: {}, generatedAt: 0 }));
    await makeClient().contextWindow({ predicates: ['parent', 'likes'] });
    expect(lastBody()).toMatchObject({ predicates: ['parent', 'likes'] });
  });

  it('includes scope in body when provided', async () => {
    mockFetch.mockResolvedValueOnce(mockResponse({ facts: [], totalAvailable: 0, windowSize: 0, predicateDistribution: {}, generatedAt: 0 }));
    await makeClient().contextWindow({ scope: 'agent1' });
    expect(lastBody()).toMatchObject({ scope: 'agent1' });
  });
});

// ---------------------------------------------------------------------------
// optimizeContext
// ---------------------------------------------------------------------------

describe('optimizeContext', () => {
  it('POSTs to /context/optimize with empty body when no opts', async () => {
    const ctx = {
      windowId: 'ctx-1',
      entries: [],
      relevantRules: [],
      totalFactsAvailable: 0,
      totalFactsIncluded: 0,
      deduplicationSavings: 0,
      contradictionsFound: 0,
      contradictionsResolved: 0,
      contradictions: [],
      bucketStats: {},
      totalCharCount: 0,
      goalDriven: false,
      knowledgeGeneration: 1,
      generatedAt: 1000,
    };
    mockFetch.mockResolvedValueOnce(mockResponse(ctx));
    const result = await makeClient().optimizeContext();
    expect(lastUrl()).toBe('http://localhost:9300/context/optimize');
    expect(lastMethod()).toBe('POST');
    expect(result).toEqual(ctx);
  });

  it('includes goals, buckets, sessionId, and predicate caps when provided', async () => {
    mockFetch.mockResolvedValueOnce(mockResponse({
      windowId: 'ctx-2',
      entries: [],
      relevantRules: [],
      totalFactsAvailable: 0,
      totalFactsIncluded: 0,
      deduplicationSavings: 0,
      contradictionsFound: 0,
      contradictionsResolved: 0,
      contradictions: [],
      bucketStats: {},
      totalCharCount: 0,
      goalDriven: true,
      knowledgeGeneration: 2,
      generatedAt: 2000,
    }));
    await makeClient().optimizeContext({
      maxFacts: 25,
      scope: 'support',
      predicates: ['customer_tier'],
      goals: [{ predicate: 'eligible_for_sla', args: ['acme_corp'] }],
      relevanceBuckets: [{ name: 'support', predicates: ['customer_tier'], weight: 2 }],
      sessionId: 'session-42',
      autoResolveContradictions: false,
      maxFactsPerPredicate: 3,
    });
    expect(lastBody()).toMatchObject({
      maxFacts: 25,
      scope: 'support',
      predicates: ['customer_tier'],
      goals: [{ predicate: 'eligible_for_sla', args: ['acme_corp'] }],
      relevanceBuckets: [{ name: 'support', predicates: ['customer_tier'], weight: 2 }],
      sessionId: 'session-42',
      autoResolveContradictions: false,
      maxFactsPerPredicate: 3,
    });
  });
});

// ---------------------------------------------------------------------------
// diffContext
// ---------------------------------------------------------------------------

describe('diffContext', () => {
  it('POSTs to /context/diff with sessionId', async () => {
    const diff = {
      previousWindowId: 'ctx-1',
      currentWindowId: 'ctx-2',
      added: [],
      removed: [],
      unchanged: 5,
      fullRefreshRecommended: false,
      reason: null,
    };
    mockFetch.mockResolvedValueOnce(mockResponse(diff));
    const result = await makeClient().diffContext({ sessionId: 'session-42' });
    expect(lastUrl()).toBe('http://localhost:9300/context/diff');
    expect(lastMethod()).toBe('POST');
    expect(lastBody()).toMatchObject({ sessionId: 'session-42' });
    expect(result).toEqual(diff);
  });

  it('includes optional diff parameters when provided', async () => {
    mockFetch.mockResolvedValueOnce(mockResponse({
      previousWindowId: 'ctx-2',
      currentWindowId: 'ctx-3',
      added: [],
      removed: [],
      unchanged: 4,
      fullRefreshRecommended: false,
      reason: null,
    }));
    await makeClient().diffContext({
      sessionId: 'session-42',
      maxFacts: 20,
      scope: 'support',
      predicates: ['customer_tier'],
      goals: [{ predicate: 'eligible_for_sla', args: ['acme_corp'] }],
      relevanceBuckets: [{ name: 'support', weight: 1.5 }],
      autoResolveContradictions: false,
      maxFactsPerPredicate: 2,
    });
    expect(lastBody()).toMatchObject({
      sessionId: 'session-42',
      maxFacts: 20,
      scope: 'support',
      predicates: ['customer_tier'],
      goals: [{ predicate: 'eligible_for_sla', args: ['acme_corp'] }],
      relevanceBuckets: [{ name: 'support', weight: 1.5 }],
      autoResolveContradictions: false,
      maxFactsPerPredicate: 2,
    });
  });
});

// ---------------------------------------------------------------------------
// summarizeContext
// ---------------------------------------------------------------------------

describe('summarizeContext', () => {
  it('POSTs to /context/summary', async () => {
    const summary = {
      totalFacts: 12,
      predicateCount: 3,
      topPredicates: [],
      factsWithTtl: 1,
      factsExpiringWithin1h: 0,
      contradictions: 0,
      topSalientFacts: [],
      totalCharCount: 250,
      knowledgeGeneration: 9,
      generatedAt: 1000,
    };
    mockFetch.mockResolvedValueOnce(mockResponse(summary));
    const result = await makeClient().summarizeContext();
    expect(lastUrl()).toBe('http://localhost:9300/context/summary');
    expect(lastMethod()).toBe('POST');
    expect(result).toEqual(summary);
  });

  it('includes scope when provided', async () => {
    mockFetch.mockResolvedValueOnce(mockResponse({
      totalFacts: 0,
      predicateCount: 0,
      topPredicates: [],
      factsWithTtl: 0,
      factsExpiringWithin1h: 0,
      contradictions: 0,
      topSalientFacts: [],
      totalCharCount: 0,
      knowledgeGeneration: 0,
      generatedAt: 0,
    }));
    await makeClient().summarizeContext('support');
    expect(lastBody()).toMatchObject({ scope: 'support' });
  });
});

// ---------------------------------------------------------------------------
// clearContextSession
// ---------------------------------------------------------------------------

describe('clearContextSession', () => {
  it('POSTs to /context/session/clear with sessionId', async () => {
    mockFetch.mockResolvedValueOnce(mockResponse("Session 'session-42' cleared"));
    const result = await makeClient().clearContextSession('session-42');
    expect(lastUrl()).toBe('http://localhost:9300/context/session/clear');
    expect(lastMethod()).toBe('POST');
    expect(lastBody()).toMatchObject({ sessionId: 'session-42' });
    expect(result).toBe("Session 'session-42' cleared");
  });
});

// ---------------------------------------------------------------------------
// ingestAndOptimize
// ---------------------------------------------------------------------------

describe('ingestAndOptimize', () => {
  it('POSTs to /context/ingest with text and optional optimization fields', async () => {
    const resultBody = {
      extracted: [{ predicate: 'customer_tier', args: ['acme_corp', 'enterprise'], confidence: 0.95 }],
      extractionProvider: 'anthropic',
      context: {
        windowId: 'ctx-3',
        entries: [],
        relevantRules: [],
        totalFactsAvailable: 1,
        totalFactsIncluded: 1,
        deduplicationSavings: 0,
        contradictionsFound: 0,
        contradictionsResolved: 0,
        contradictions: [],
        bucketStats: {},
        totalCharCount: 32,
        goalDriven: false,
        knowledgeGeneration: 3,
        generatedAt: 3000,
      },
    };
    mockFetch.mockResolvedValueOnce(mockResponse(resultBody));
    const result = await makeClient().ingestAndOptimize({
      text: 'Acme Corp is enterprise.',
      goals: [{ predicate: 'eligible_for_sla', args: ['acme_corp'] }],
      maxFacts: 10,
      maxFactsPerPredicate: 2,
      autoResolveContradictions: false,
      sessionId: 'session-42',
      relevanceBuckets: [{ name: 'support', weight: 1.2 }],
      scope: 'support',
      contextHint: 'support ticket',
    });
    expect(lastUrl()).toBe('http://localhost:9300/context/ingest');
    expect(lastMethod()).toBe('POST');
    expect(lastBody()).toMatchObject({
      text: 'Acme Corp is enterprise.',
      goals: [{ predicate: 'eligible_for_sla', args: ['acme_corp'] }],
      maxFacts: 10,
      maxFactsPerPredicate: 2,
      autoResolveContradictions: false,
      sessionId: 'session-42',
      relevanceBuckets: [{ name: 'support', weight: 1.2 }],
      scope: 'support',
      contextHint: 'support ticket',
    });
    expect(result).toEqual(resultBody);
  });
});

// ---------------------------------------------------------------------------
// temporalQuery
// ---------------------------------------------------------------------------

describe('temporalQuery', () => {
  it('POSTs to /memory/query/temporal with predicate, args, and timestamp', async () => {
    mockFetch.mockResolvedValueOnce(mockResponse([]));
    await makeClient().temporalQuery('location', ['alice', '?place'], 1700000000000);
    expect(lastUrl()).toBe('http://localhost:9300/memory/query/temporal');
    expect(lastMethod()).toBe('POST');
    expect(lastBody()).toMatchObject({
      predicate: 'location',
      args: ['alice', '?place'],
      timestamp: 1700000000000,
    });
  });

  it('includes scope in body when provided', async () => {
    mockFetch.mockResolvedValueOnce(mockResponse([]));
    await makeClient().temporalQuery('x', ['a'], 123, { scope: 'hist' });
    expect(lastBody()).toMatchObject({ scope: 'hist' });
  });
});

// ---------------------------------------------------------------------------
// consolidate
// ---------------------------------------------------------------------------

describe('consolidate', () => {
  it('POSTs to /memory/consolidate and returns ConsolidationResult', async () => {
    const cr = { factsConsolidated: 3, newFacts: [], timestamp: 999 };
    mockFetch.mockResolvedValueOnce(mockResponse(cr));
    const result = await makeClient().consolidate();
    expect(lastUrl()).toBe('http://localhost:9300/memory/consolidate');
    expect(lastMethod()).toBe('POST');
    expect(result).toEqual(cr);
  });
});

// ---------------------------------------------------------------------------
// decay
// ---------------------------------------------------------------------------

describe('decay', () => {
  it('POSTs to /memory/decay with empty body when no threshold', async () => {
    const dr = { expiredCount: 1, evictedCount: 2, removedAtoms: [], timestamp: 100 };
    mockFetch.mockResolvedValueOnce(mockResponse(dr));
    const result = await makeClient().decay();
    expect(lastUrl()).toBe('http://localhost:9300/memory/decay');
    expect(lastMethod()).toBe('POST');
    expect((lastBody() as Record<string, unknown>)['threshold']).toBeUndefined();
    expect(result).toEqual(dr);
  });

  it('includes threshold in body when provided', async () => {
    mockFetch.mockResolvedValueOnce(mockResponse({ expiredCount: 0, evictedCount: 0, removedAtoms: [], timestamp: 0 }));
    await makeClient().decay(0.1);
    expect(lastBody()).toMatchObject({ threshold: 0.1 });
  });
});

// ---------------------------------------------------------------------------
// setPriority
// ---------------------------------------------------------------------------

describe('setPriority', () => {
  it('POSTs to /memory/priority with predicate, args, and priority', async () => {
    mockFetch.mockResolvedValueOnce(mockResponse('Priority set.'));
    const result = await makeClient().setPriority('user_pref', ['alice', 'dark_mode'], 0.9);
    expect(lastUrl()).toBe('http://localhost:9300/memory/priority');
    expect(lastMethod()).toBe('POST');
    expect(lastBody()).toMatchObject({
      predicate: 'user_pref',
      args: ['alice', 'dark_mode'],
      priority: 0.9,
      truthVal: true,
    });
    expect(result).toBe('Priority set.');
  });

  it('includes scope in body when provided', async () => {
    mockFetch.mockResolvedValueOnce(mockResponse('OK'));
    await makeClient().setPriority('x', ['a'], 0.5, { scope: 'ns' });
    expect(lastBody()).toMatchObject({ scope: 'ns' });
  });
});

// ---------------------------------------------------------------------------
// execute
// ---------------------------------------------------------------------------

describe('execute', () => {
  it('POSTs to /execute and returns result string', async () => {
    mockFetch.mockResolvedValueOnce(mockResponse({ result: 'OK: asserted' }));
    const result = await makeClient().execute('ASSERT parent(alice, bob).');
    expect(lastUrl()).toBe('http://localhost:9300/execute');
    expect(lastMethod()).toBe('POST');
    expect(lastBody()).toMatchObject({ command: 'ASSERT parent(alice, bob).' });
    expect(result).toBe('OK: asserted');
  });
});

// ---------------------------------------------------------------------------
// health
// ---------------------------------------------------------------------------

describe('health', () => {
  it('GETs /health and returns HealthStatus', async () => {
    const hs = { status: 'healthy', version: '1.0.0' };
    mockFetch.mockResolvedValueOnce(mockResponse(hs));
    const result = await makeClient().health();
    expect(lastUrl()).toBe('http://localhost:9300/health');
    expect(lastMethod()).toBe('GET');
    expect(result).toEqual(hs);
  });
});

// ---------------------------------------------------------------------------
// predicates
// ---------------------------------------------------------------------------

describe('predicates', () => {
  it('GETs /predicates and returns SchemaDiscovery', async () => {
    const sd = { predicates: [], totalPredicates: 0, totalFacts: 0, totalRules: 0 };
    mockFetch.mockResolvedValueOnce(mockResponse(sd));
    const result = await makeClient().predicates();
    expect(lastUrl()).toBe('http://localhost:9300/predicates');
    expect(lastMethod()).toBe('GET');
    expect(result).toEqual(sd);
  });

  it('appends encoded scope query param when provided', async () => {
    mockFetch.mockResolvedValueOnce(mockResponse({ predicates: [], totalPredicates: 0, totalFacts: 0, totalRules: 0 }));
    await makeClient().predicates('my scope');
    expect(lastUrl()).toBe('http://localhost:9300/predicates?scope=my%20scope');
  });
});

// ---------------------------------------------------------------------------
// Auth methods
// ---------------------------------------------------------------------------

describe('authStatus', () => {
  it('GETs /auth/status and returns AuthStatus', async () => {
    const as = { authEnabled: true, mode: 'rbac', hasKeys: true };
    mockFetch.mockResolvedValueOnce(mockResponse(as));
    const result = await makeClient().authStatus();
    expect(lastUrl()).toBe('http://localhost:9300/auth/status');
    expect(lastMethod()).toBe('GET');
    expect(result).toEqual(as);
  });
});

describe('bootstrap', () => {
  it('POSTs to /auth/bootstrap with defaults', async () => {
    const resp = { id: 'k1', name: 'admin', key: 'raw', prefix: 'nai_', role: 'admin', databases: [], tenants: [], expiresAt: null };
    mockFetch.mockResolvedValueOnce(mockResponse(resp));
    const result = await makeClient().bootstrap();
    expect(lastUrl()).toBe('http://localhost:9300/auth/bootstrap');
    expect(lastMethod()).toBe('POST');
    expect(lastBody()).toMatchObject({ name: 'admin', description: 'Initial admin key' });
    expect(result).toEqual(resp);
  });

  it('uses provided name and description', async () => {
    mockFetch.mockResolvedValueOnce(mockResponse({ id: 'k1', name: 'myadmin', key: 'raw', prefix: 'nai_', role: 'admin', databases: [], tenants: [], expiresAt: null }));
    await makeClient().bootstrap('myadmin', 'My admin key');
    expect(lastBody()).toMatchObject({ name: 'myadmin', description: 'My admin key' });
  });
});

describe('createKey', () => {
  it('POSTs to /auth/keys with opts', async () => {
    const resp = { id: 'k2', name: 'writer', key: 'raw2', prefix: 'nai_', role: 'writer', databases: ['prod'], tenants: [], expiresAt: null };
    mockFetch.mockResolvedValueOnce(mockResponse(resp));
    const result = await makeClient().createKey({ name: 'writer', role: 'writer', databases: ['prod'] });
    expect(lastUrl()).toBe('http://localhost:9300/auth/keys');
    expect(lastMethod()).toBe('POST');
    expect(lastBody()).toMatchObject({ name: 'writer', role: 'writer', databases: ['prod'] });
    expect(result).toEqual(resp);
  });
});

describe('listKeys', () => {
  it('GETs /auth/keys and returns KeyInfo[]', async () => {
    const keys = [{ id: 'k1', name: 'admin', prefix: 'nai_', role: 'admin', databases: [], tenants: [], createdAt: 1, lastUsedAt: null, expiresAt: null, enabled: true, description: '' }];
    mockFetch.mockResolvedValueOnce(mockResponse(keys));
    const result = await makeClient().listKeys();
    expect(lastUrl()).toBe('http://localhost:9300/auth/keys');
    expect(lastMethod()).toBe('GET');
    expect(result).toEqual(keys);
  });
});

describe('revokeKey', () => {
  it('DELETEs /auth/keys/:id and returns confirmation', async () => {
    mockFetch.mockResolvedValueOnce(mockResponse('Key revoked.'));
    const result = await makeClient().revokeKey('some-uuid');
    expect(lastUrl()).toBe('http://localhost:9300/auth/keys/some-uuid');
    expect(lastMethod()).toBe('DELETE');
    expect(result).toBe('Key revoked.');
  });
});

describe('whoami', () => {
  it('GETs /auth/whoami and returns WhoAmI', async () => {
    const me = { keyId: 'k1', name: 'admin', role: 'admin', permissions: ['read', 'write'], databases: [], tenants: [] };
    mockFetch.mockResolvedValueOnce(mockResponse(me));
    const result = await makeClient().whoami();
    expect(lastUrl()).toBe('http://localhost:9300/auth/whoami');
    expect(lastMethod()).toBe('GET');
    expect(result).toEqual(me);
  });
});

// ---------------------------------------------------------------------------
// Error handling
// ---------------------------------------------------------------------------

describe('Error handling', () => {
  it('throws NocturnusAIRequestError with statusCode on text endpoint failure', async () => {
    mockFetch.mockResolvedValueOnce(mockResponse('Not found', 404));
    await expect(makeClient().assertFact('x', ['a'])).rejects.toMatchObject({
      name: 'NocturnusAIRequestError',
      statusCode: 404,
    });
  });

  it('throws NocturnusAIRequestError with statusCode on JSON endpoint with non-JSON error body', async () => {
    // requestJson only throws when JSON parsing fails on a non-OK response
    mockFetch.mockResolvedValueOnce(mockResponse('Bad Request', 400));
    await expect(makeClient().health()).rejects.toMatchObject({
      name: 'NocturnusAIRequestError',
      statusCode: 400,
    });
  });

  it('parses structured NocturnusAIError from JSON error body on text endpoints', async () => {
    const errorBody = { code: 'NOT_FOUND', message: 'Fact not found', details: {} };
    mockFetch.mockResolvedValueOnce(mockResponse(errorBody, 404));
    const err = await makeClient().assertFact('x', ['a']).catch((e) => e);
    expect(err).toBeInstanceOf(NocturnusAIRequestError);
    expect(err.statusCode).toBe(404);
    expect(err.error).toMatchObject({ code: 'NOT_FOUND', message: 'Fact not found' });
    expect(err.message).toBe('Fact not found');
  });

  it('uses generic message when error body is not JSON on text endpoints', async () => {
    mockFetch.mockResolvedValueOnce(mockResponse('Internal error', 500));
    const err = await makeClient().assertFact('x', ['a']).catch((e) => e);
    expect(err).toBeInstanceOf(NocturnusAIRequestError);
    expect(err.statusCode).toBe(500);
    expect(err.message).toContain('500');
  });

  it('throws NocturnusAIRequestError when JSON endpoint returns non-JSON error body', async () => {
    // requestJson throws NocturnusAIRequestError when JSON.parse fails on a non-OK response
    mockFetch.mockResolvedValueOnce(mockResponse('Internal Server Error', 503));
    const err = await makeClient().health().catch((e) => e);
    expect(err).toBeInstanceOf(NocturnusAIRequestError);
    expect(err.statusCode).toBe(503);
  });
});

// ---------------------------------------------------------------------------
// Retry logic
// ---------------------------------------------------------------------------

/**
 * Retry tests need real timers for setTimeout, but we use vi.useFakeTimers()
 * globally and advance timers manually so tests complete instantly.
 */
describe('Retry logic', () => {
  // Use a client with retries enabled
  function makeRetryClient() {
    return new NocturnusAIClient(
      { baseUrl: 'http://localhost:9300', database: 'db', tenantId: 't' },
      3,
    );
  }

  it('does not retry on 400 Bad Request', async () => {
    mockFetch.mockResolvedValue(mockResponse('bad request', 400));
    // Attach catch before advancing timers to avoid unhandled rejection
    const promise = makeRetryClient().health().catch((e: unknown) => e);
    await vi.runAllTimersAsync();
    const err = await promise;
    // Only 1 fetch call — no retries on 400
    expect(mockFetch).toHaveBeenCalledTimes(1);
    expect((err as NocturnusAIRequestError).statusCode).toBe(400);
  });

  it('does not retry on 401 Unauthorized', async () => {
    mockFetch.mockResolvedValue(mockResponse('unauthorized', 401));
    const promise = makeRetryClient().health().catch((e: unknown) => e);
    await vi.runAllTimersAsync();
    const err = await promise;
    expect(mockFetch).toHaveBeenCalledTimes(1);
    expect((err as NocturnusAIRequestError).statusCode).toBe(401);
  });

  it('does not retry on 404 Not Found', async () => {
    mockFetch.mockResolvedValue(mockResponse('not found', 404));
    const promise = makeRetryClient().health().catch(() => {});
    await vi.runAllTimersAsync();
    await promise;
    expect(mockFetch).toHaveBeenCalledTimes(1);
  });

  it('retries on 429 and eventually succeeds', async () => {
    mockFetch
      .mockResolvedValueOnce(mockResponse('rate limited', 429))
      .mockResolvedValueOnce(mockResponse('rate limited', 429))
      .mockResolvedValueOnce(mockResponse({ status: 'healthy' }));

    const promise = makeRetryClient().health();
    await vi.runAllTimersAsync();
    const result = await promise;
    expect(mockFetch).toHaveBeenCalledTimes(3);
    expect(result.status).toBe('healthy');
  });

  it('retries on 502 and eventually succeeds', async () => {
    mockFetch
      .mockResolvedValueOnce(mockResponse('bad gateway', 502))
      .mockResolvedValueOnce(mockResponse({ status: 'healthy' }));

    const promise = makeRetryClient().health();
    await vi.runAllTimersAsync();
    const result = await promise;
    expect(mockFetch).toHaveBeenCalledTimes(2);
    expect(result).toMatchObject({ status: 'healthy' });
  });

  it('retries on 503', async () => {
    mockFetch
      .mockResolvedValueOnce(mockResponse('service unavailable', 503))
      .mockResolvedValueOnce(mockResponse({ status: 'healthy' }));

    const promise = makeRetryClient().health();
    await vi.runAllTimersAsync();
    await promise;
    expect(mockFetch).toHaveBeenCalledTimes(2);
  });

  it('retries on 504', async () => {
    mockFetch
      .mockResolvedValueOnce(mockResponse('gateway timeout', 504))
      .mockResolvedValueOnce(mockResponse({ status: 'healthy' }));

    const promise = makeRetryClient().health();
    await vi.runAllTimersAsync();
    await promise;
    expect(mockFetch).toHaveBeenCalledTimes(2);
  });

  it('exhausts retries and returns the final 503 response', async () => {
    // 4 total attempts (1 + 3 retries), all 503
    mockFetch.mockResolvedValue(mockResponse('always down', 503));

    const promise = makeRetryClient().health().catch((e: unknown) => e);
    await vi.runAllTimersAsync();
    const err = await promise;
    expect(mockFetch).toHaveBeenCalledTimes(4);
    expect((err as NocturnusAIRequestError).statusCode).toBe(503);
  });

  it('retries on network error (fetch throws)', async () => {
    mockFetch
      .mockRejectedValueOnce(new Error('network error'))
      .mockRejectedValueOnce(new Error('network error'))
      .mockResolvedValueOnce(mockResponse({ status: 'healthy' }));

    const promise = makeRetryClient().health();
    await vi.runAllTimersAsync();
    const result = await promise;
    expect(mockFetch).toHaveBeenCalledTimes(3);
    expect(result).toMatchObject({ status: 'healthy' });
  });

  it('throws last network error after exhausting retries', async () => {
    mockFetch.mockRejectedValue(new Error('connection refused'));

    const promise = makeRetryClient().health().catch((e: unknown) => e);
    await vi.runAllTimersAsync();
    const err = await promise;
    expect((err as Error).message).toBe('Connection failed: connection refused');
    expect(mockFetch).toHaveBeenCalledTimes(4);
  });

  it('respects Retry-After header for delay calculation on 429', async () => {
    // With Retry-After present, the delay should be parsed from the header.
    // We just verify the retry happens and ultimately succeeds.
    const retryAfterResponse = {
      ok: false,
      status: 429,
      statusText: 'Error',
      headers: new Headers({ 'Retry-After': '1' }),
      text: async () => 'rate limited',
      json: async () => ({ error: 'rate limited' }),
    } as Response;

    mockFetch
      .mockResolvedValueOnce(retryAfterResponse)
      .mockResolvedValueOnce(mockResponse({ status: 'healthy' }));

    const promise = makeRetryClient().health();
    await vi.runAllTimersAsync();
    const result = await promise;
    expect(mockFetch).toHaveBeenCalledTimes(2);
    expect(result).toMatchObject({ status: 'healthy' });
  });
});

// ---------------------------------------------------------------------------
// Trailing-slash stripping edge cases
// ---------------------------------------------------------------------------

describe('URL construction', () => {
  it('constructs correct URL by appending path to stripped baseUrl', async () => {
    const client = new NocturnusAIClient({ baseUrl: 'https://api.example.com/v1/' }, 0);
    mockFetch.mockResolvedValueOnce(mockResponse({ status: 'healthy' }));
    await client.health();
    expect(lastUrl()).toBe('https://api.example.com/v1/health');
  });
});
