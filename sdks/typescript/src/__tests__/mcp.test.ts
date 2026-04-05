/**
 * Tests for NocturnusAIMCPClient.
 *
 * All HTTP requests are intercepted via a global fetch mock so no server is
 * needed. The MCP client uses JSON-RPC 2.0 over HTTP POST to /mcp.
 */

import { describe, it, expect, vi, beforeEach } from 'vitest';
import { NocturnusAIMCPClient, McpError } from '../mcp.js';

// ---------------------------------------------------------------------------
// Fetch mock helpers
// ---------------------------------------------------------------------------

const mockFetch = vi.fn();
vi.stubGlobal('fetch', mockFetch);

/** Build a minimal Response-shaped object for a successful JSON-RPC response. */
function rpcSuccess(result: unknown, id: number | string = 1): Response {
  const body = { jsonrpc: '2.0', id, result };
  return {
    ok: true,
    status: 200,
    statusText: 'OK',
    headers: new Headers(),
    text: async () => JSON.stringify(body),
    json: async () => body,
  } as Response;
}

/** Build a Response for a JSON-RPC error response. */
function rpcError(code: number, message: string, id: number | string = 1, data?: unknown): Response {
  const body = { jsonrpc: '2.0', id, error: { code, message, data } };
  return {
    ok: true, // HTTP 200 — error is in the JSON-RPC payload
    status: 200,
    statusText: 'OK',
    headers: new Headers(),
    text: async () => JSON.stringify(body),
    json: async () => body,
  } as Response;
}

/** Build a Response for an HTTP-level failure. */
function httpError(status: number): Response {
  return {
    ok: false,
    status,
    statusText: 'Error',
    headers: new Headers(),
    text: async () => 'HTTP error',
    json: async () => ({ error: 'HTTP error' }),
  } as Response;
}

/** Extract the parsed JSON body from the last fetch call. */
function lastBody(): Record<string, unknown> {
  const call = mockFetch.mock.calls[mockFetch.mock.calls.length - 1];
  const init = call[1] as RequestInit;
  return JSON.parse(init.body as string) as Record<string, unknown>;
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

// ---------------------------------------------------------------------------
// Test setup
// ---------------------------------------------------------------------------

beforeEach(() => {
  mockFetch.mockReset();
});

// ---------------------------------------------------------------------------
// Helper: build a client and initialize it
// ---------------------------------------------------------------------------

function makeClient(overrides?: Partial<ConstructorParameters<typeof NocturnusAIMCPClient>[0]>) {
  return new NocturnusAIMCPClient({
    baseUrl: 'http://localhost:9300',
    database: 'mcpdb',
    tenantId: 'tenant1',
    apiKey: 'mcp-key',
    ...overrides,
  });
}

/**
 * Build a client that has already been initialized (first RPC call handled).
 * Returns the initialized client.
 */
async function makeInitializedClient(
  overrides?: Partial<ConstructorParameters<typeof NocturnusAIMCPClient>[0]>,
): Promise<NocturnusAIMCPClient> {
  const serverInfo = {
    protocolVersion: '2025-11-25',
    capabilities: { tools: {} },
    serverInfo: { name: 'nocturnusai', version: '0.2.3' },
  };
  mockFetch.mockResolvedValueOnce(rpcSuccess(serverInfo, 1));
  const client = makeClient(overrides);
  await client.initialize();
  mockFetch.mockReset(); // clear the initialize call from history
  return client;
}

// ---------------------------------------------------------------------------
// Configuration
// ---------------------------------------------------------------------------

describe('Configuration', () => {
  it('strips trailing slash from baseUrl', async () => {
    const serverInfo = { protocolVersion: '2025-11-25', capabilities: {}, serverInfo: { name: 'n', version: '0' } };
    mockFetch.mockResolvedValueOnce(rpcSuccess(serverInfo, 1));
    const client = new NocturnusAIMCPClient({ baseUrl: 'http://localhost:9300/', database: 'db' });
    await client.initialize();
    expect(lastUrl()).toBe('http://localhost:9300/mcp');
  });

  it('defaults database to "default" when not provided', async () => {
    const serverInfo = { protocolVersion: '2025-11-25', capabilities: {}, serverInfo: { name: 'n', version: '0' } };
    mockFetch.mockResolvedValueOnce(rpcSuccess(serverInfo, 1));
    const client = new NocturnusAIMCPClient({ baseUrl: 'http://localhost:9300' });
    await client.initialize();
    expect(lastHeaders()['X-Database']).toBe('default');
  });

  it('defaults tenantId to "default" when not provided', async () => {
    const serverInfo = { protocolVersion: '2025-11-25', capabilities: {}, serverInfo: { name: 'n', version: '0' } };
    mockFetch.mockResolvedValueOnce(rpcSuccess(serverInfo, 1));
    const client = new NocturnusAIMCPClient({ baseUrl: 'http://localhost:9300' });
    await client.initialize();
    expect(lastHeaders()['X-Tenant-ID']).toBe('default');
  });

  it('uses provided database and tenantId', async () => {
    const serverInfo = { protocolVersion: '2025-11-25', capabilities: {}, serverInfo: { name: 'n', version: '0' } };
    mockFetch.mockResolvedValueOnce(rpcSuccess(serverInfo, 1));
    const client = makeClient();
    await client.initialize();
    expect(lastHeaders()['X-Database']).toBe('mcpdb');
    expect(lastHeaders()['X-Tenant-ID']).toBe('tenant1');
  });
});

// ---------------------------------------------------------------------------
// Headers
// ---------------------------------------------------------------------------

describe('Headers', () => {
  it('includes X-Database on every MCP request', async () => {
    const client = await makeInitializedClient();
    mockFetch.mockResolvedValueOnce(rpcSuccess({ tools: [] }, 2));
    await client.listTools();
    expect(lastHeaders()['X-Database']).toBe('mcpdb');
  });

  it('includes X-Tenant-ID on every MCP request', async () => {
    const client = await makeInitializedClient();
    mockFetch.mockResolvedValueOnce(rpcSuccess({ tools: [] }, 2));
    await client.listTools();
    expect(lastHeaders()['X-Tenant-ID']).toBe('tenant1');
  });

  it('includes X-API-Key when apiKey provided', async () => {
    const client = await makeInitializedClient();
    mockFetch.mockResolvedValueOnce(rpcSuccess({ tools: [] }, 2));
    await client.listTools();
    expect(lastHeaders()['X-API-Key']).toBe('mcp-key');
  });

  it('omits X-API-Key when no apiKey provided', async () => {
    const client = await makeInitializedClient({ apiKey: undefined });
    mockFetch.mockResolvedValueOnce(rpcSuccess({ tools: [] }, 2));
    await client.listTools();
    expect(lastHeaders()['X-API-Key']).toBeUndefined();
  });

  it('sets Content-Type application/json', async () => {
    const client = await makeInitializedClient();
    mockFetch.mockResolvedValueOnce(rpcSuccess({ tools: [] }, 2));
    await client.listTools();
    expect(lastHeaders()['Content-Type']).toBe('application/json');
  });
});

// ---------------------------------------------------------------------------
// initialize
// ---------------------------------------------------------------------------

describe('initialize', () => {
  it('sends a JSON-RPC "initialize" request with protocol version and clientInfo', async () => {
    const serverInfo = {
      protocolVersion: '2025-11-25',
      capabilities: { tools: {} },
      serverInfo: { name: 'nocturnusai', version: '0.2.3' },
    };
    mockFetch.mockResolvedValueOnce(rpcSuccess(serverInfo, 1));
    const client = makeClient();
    const result = await client.initialize();

    expect(lastUrl()).toBe('http://localhost:9300/mcp');
    const body = lastBody();
    expect(body.jsonrpc).toBe('2.0');
    expect(body.method).toBe('initialize');
    const params = body.params as Record<string, unknown>;
    expect(params.protocolVersion).toBe('2025-11-25');
    expect((params.clientInfo as Record<string, unknown>).name).toBe('nocturnusai-sdk');
    expect(result).toEqual(serverInfo);
  });

  it('sets initialized flag so listTools works after initialize', async () => {
    const serverInfo = { protocolVersion: '2025-11-25', capabilities: {}, serverInfo: { name: 'n', version: '0' } };
    mockFetch.mockResolvedValueOnce(rpcSuccess(serverInfo, 1));
    const client = makeClient();
    await client.initialize();

    mockFetch.mockResolvedValueOnce(rpcSuccess({ tools: [] }, 2));
    await expect(client.listTools()).resolves.toEqual([]);
  });

  it('increments the JSON-RPC id on each call', async () => {
    const serverInfo = { protocolVersion: '2025-11-25', capabilities: {}, serverInfo: { name: 'n', version: '0' } };
    mockFetch.mockResolvedValueOnce(rpcSuccess(serverInfo, 1));
    const client = makeClient();
    await client.initialize();
    const firstId = lastBody().id as number;

    mockFetch.mockResolvedValueOnce(rpcSuccess({ tools: [] }, firstId + 1));
    await client.listTools();
    const secondId = lastBody().id as number;

    expect(secondId).toBe(firstId + 1);
  });
});

// ---------------------------------------------------------------------------
// listTools
// ---------------------------------------------------------------------------

describe('listTools', () => {
  it('sends a JSON-RPC "tools/list" request', async () => {
    const client = await makeInitializedClient();
    const tools = [
      { name: 'assert_fact', description: 'Assert a fact', inputSchema: {} },
      { name: 'infer', description: 'Run inference', inputSchema: {} },
    ];
    mockFetch.mockResolvedValueOnce(rpcSuccess({ tools }, 2));
    const result = await client.listTools();

    expect(lastUrl()).toBe('http://localhost:9300/mcp');
    expect(lastBody().method).toBe('tools/list');
    expect(result).toEqual(tools);
  });

  it('returns empty array when no tools are available', async () => {
    const client = await makeInitializedClient();
    mockFetch.mockResolvedValueOnce(rpcSuccess({ tools: [] }, 2));
    const result = await client.listTools();
    expect(result).toEqual([]);
  });

  it('throws Error when called before initialize()', async () => {
    const client = makeClient();
    await expect(client.listTools()).rejects.toThrow(
      'NocturnusAIMCPClient has not been initialized',
    );
  });

  it('throws Error with descriptive message when not initialized', async () => {
    const client = makeClient();
    const err = await client.listTools().catch((e) => e);
    expect(err).toBeInstanceOf(Error);
    expect(err.message).toContain('initialize()');
  });
});

// ---------------------------------------------------------------------------
// callTool
// ---------------------------------------------------------------------------

describe('callTool', () => {
  it('sends a JSON-RPC "tools/call" with name and arguments', async () => {
    const client = await makeInitializedClient();
    const toolResult = { content: [{ type: 'text', text: 'Asserted.' }], isError: false };
    mockFetch.mockResolvedValueOnce(rpcSuccess(toolResult, 2));

    const result = await client.callTool('assert_fact', {
      predicate: 'parent',
      args: ['alice', 'bob'],
    });

    expect(lastUrl()).toBe('http://localhost:9300/mcp');
    const body = lastBody();
    expect(body.method).toBe('tools/call');
    const params = body.params as Record<string, unknown>;
    expect(params.name).toBe('assert_fact');
    expect(params.arguments).toEqual({ predicate: 'parent', args: ['alice', 'bob'] });
    expect(result).toEqual(toolResult);
  });

  it('throws Error when called before initialize()', async () => {
    const client = makeClient();
    await expect(client.callTool('assert_fact', {})).rejects.toThrow(
      'NocturnusAIMCPClient has not been initialized',
    );
  });

  it('returns tool result with isError flag when present', async () => {
    const client = await makeInitializedClient();
    const toolResult = { content: [{ type: 'text', text: 'Error: invalid' }], isError: true };
    mockFetch.mockResolvedValueOnce(rpcSuccess(toolResult, 2));

    const result = await client.callTool('infer', { predicate: '?x', args: ['?a'] });
    expect(result.isError).toBe(true);
    expect(result.content[0].text).toBe('Error: invalid');
  });

  it('passes complex args correctly', async () => {
    const client = await makeInitializedClient();
    mockFetch.mockResolvedValueOnce(rpcSuccess({ content: [{ type: 'text', text: 'ok' }] }, 2));

    await client.callTool('assert_fact', {
      predicate: 'event',
      args: ['meeting', 'alice'],
      ttl: 3600000,
      metadata: { source: 'calendar' },
    });

    const params = lastBody().params as Record<string, unknown>;
    expect(params.arguments).toEqual({
      predicate: 'event',
      args: ['meeting', 'alice'],
      ttl: 3600000,
      metadata: { source: 'calendar' },
    });
  });
});

// ---------------------------------------------------------------------------
// ping
// ---------------------------------------------------------------------------

describe('ping', () => {
  it('sends a JSON-RPC "ping" request', async () => {
    const client = await makeInitializedClient();
    mockFetch.mockResolvedValueOnce(rpcSuccess({}, 2));

    const result = await client.ping();
    expect(lastUrl()).toBe('http://localhost:9300/mcp');
    expect(lastBody().method).toBe('ping');
    expect(result).toEqual({});
  });

  it('can ping before calling initialize (ping has no initialization guard)', async () => {
    // ping does not call ensureInitialized, so it should work without init
    const client = makeClient();
    mockFetch.mockResolvedValueOnce(rpcSuccess({}, 1));
    await expect(client.ping()).resolves.toEqual({});
  });
});

// ---------------------------------------------------------------------------
// Error handling
// ---------------------------------------------------------------------------

describe('Error handling', () => {
  it('throws McpError when JSON-RPC response contains an error', async () => {
    const client = await makeInitializedClient();
    mockFetch.mockResolvedValueOnce(rpcError(-32601, 'Method not found', 2));

    const err = await client.listTools().catch((e) => e);
    expect(err).toBeInstanceOf(McpError);
    expect(err.name).toBe('McpError');
    expect(err.code).toBe(-32601);
    expect(err.message).toBe('Method not found');
  });

  it('includes data in McpError when provided', async () => {
    const client = await makeInitializedClient();
    mockFetch.mockResolvedValueOnce(rpcError(-32600, 'Invalid request', 2, { detail: 'missing id' }));

    const err = await client.listTools().catch((e) => e);
    expect(err).toBeInstanceOf(McpError);
    expect(err.data).toEqual({ detail: 'missing id' });
  });

  it('throws McpError during initialize when server returns RPC error', async () => {
    mockFetch.mockResolvedValueOnce(rpcError(-32700, 'Parse error', 1));
    const client = makeClient();
    const err = await client.initialize().catch((e) => e);
    expect(err).toBeInstanceOf(McpError);
    expect(err.code).toBe(-32700);
  });

  it('throws Error on HTTP-level failure (non-OK status)', async () => {
    const client = await makeInitializedClient();
    mockFetch.mockResolvedValueOnce(httpError(503));

    const err = await client.listTools().catch((e) => e);
    expect(err).toBeInstanceOf(Error);
    expect(err.message).toContain('503');
  });

  it('throws McpError when callTool returns RPC error', async () => {
    const client = await makeInitializedClient();
    mockFetch.mockResolvedValueOnce(rpcError(-32000, 'Tool execution failed', 2));

    const err = await client.callTool('nonexistent_tool', {}).catch((e) => e);
    expect(err).toBeInstanceOf(McpError);
    expect(err.message).toBe('Tool execution failed');
  });
});

// ---------------------------------------------------------------------------
// JSON-RPC wire format
// ---------------------------------------------------------------------------

describe('JSON-RPC wire format', () => {
  it('sends jsonrpc: "2.0" on every request', async () => {
    const client = await makeInitializedClient();
    mockFetch.mockResolvedValueOnce(rpcSuccess({ tools: [] }, 2));
    await client.listTools();
    expect(lastBody().jsonrpc).toBe('2.0');
  });

  it('includes a numeric id on every request', async () => {
    const client = await makeInitializedClient();
    mockFetch.mockResolvedValueOnce(rpcSuccess({ tools: [] }, 2));
    await client.listTools();
    expect(typeof lastBody().id).toBe('number');
  });

  it('sends method as string', async () => {
    const client = await makeInitializedClient();
    mockFetch.mockResolvedValueOnce(rpcSuccess({ tools: [] }, 2));
    await client.listTools();
    expect(lastBody().method).toBe('tools/list');
  });

  it('omits params for requests with no parameters (e.g. tools/list)', async () => {
    // tools/list is called without explicit params from sendRequest
    const client = await makeInitializedClient();
    mockFetch.mockResolvedValueOnce(rpcSuccess({ tools: [] }, 2));
    await client.listTools();
    // params should be undefined (not present or explicitly undefined)
    expect(lastBody().params).toBeUndefined();
  });

  it('includes params object for initialize', async () => {
    const serverInfo = { protocolVersion: '2025-11-25', capabilities: {}, serverInfo: { name: 'n', version: '0' } };
    mockFetch.mockResolvedValueOnce(rpcSuccess(serverInfo, 1));
    const client = makeClient();
    await client.initialize();
    const params = lastBody().params as Record<string, unknown>;
    expect(params).toBeTruthy();
    expect(params.protocolVersion).toBe('2025-11-25');
  });

  it('includes params with name and arguments for callTool', async () => {
    const client = await makeInitializedClient();
    mockFetch.mockResolvedValueOnce(rpcSuccess({ content: [] }, 2));
    await client.callTool('my_tool', { x: 1 });
    const params = lastBody().params as Record<string, unknown>;
    expect(params.name).toBe('my_tool');
    expect(params.arguments).toEqual({ x: 1 });
  });

  it('always POSTs to /mcp', async () => {
    const client = await makeInitializedClient();
    mockFetch.mockResolvedValueOnce(rpcSuccess({ tools: [] }, 2));
    await client.listTools();
    expect(lastUrl()).toBe('http://localhost:9300/mcp');
    const call = mockFetch.mock.calls[mockFetch.mock.calls.length - 1];
    const init = call[1] as RequestInit;
    expect(init.method).toBe('POST');
  });
});
