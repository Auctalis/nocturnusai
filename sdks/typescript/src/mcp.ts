/**
 * @module mcp
 * MCP (Model Context Protocol) client helper for NocturnusAI.
 *
 * Communicates with the NocturnusAI MCP endpoint using JSON-RPC 2.0 over HTTP.
 * This allows MCP-compatible AI agents to discover and invoke NocturnusAI tools
 * programmatically.
 *
 * The MCP endpoint is available at POST /mcp on the NocturnusAI server.
 *
 * @see https://modelcontextprotocol.io/specification/2025-11-25
 *
 * @example
 * ```ts
 * import { NocturnusAIMCPClient } from 'nocturnusai-sdk';
 *
 * const mcp = new NocturnusAIMCPClient({
 *   baseUrl: 'http://localhost:9300',
 *   database: 'mydb',
 *   tenantId: 'default',
 * });
 *
 * const serverInfo = await mcp.initialize();
 * const tools = await mcp.listTools();
 * const result = await mcp.callTool('assert_fact', {
 *   predicate: 'parent',
 *   args: ['alice', 'bob'],
 * });
 * ```
 */

import type {
  NocturnusAIConfig,
  JsonRpcRequest,
  JsonRpcResponse,
  JsonRpcError,
  McpTool,
  McpToolResult,
  McpServerInfo,
} from './types.js';

// ---------------------------------------------------------------------------
// Error class
// ---------------------------------------------------------------------------

/**
 * Error thrown when an MCP JSON-RPC request fails.
 * Contains the JSON-RPC error code and message.
 */
export class McpError extends Error {
  /** JSON-RPC error code. */
  public readonly code: number;

  /** Additional error data, if provided by the server. */
  public readonly data?: unknown;

  constructor(rpcError: JsonRpcError) {
    super(rpcError.message);
    this.name = 'McpError';
    this.code = rpcError.code;
    this.data = rpcError.data;
  }
}

// ---------------------------------------------------------------------------
// MCP Client
// ---------------------------------------------------------------------------

/**
 * Client for communicating with the NocturnusAI MCP (Model Context Protocol) endpoint.
 *
 * Provides methods matching the MCP lifecycle:
 * 1. {@link initialize} - Perform MCP handshake and receive server capabilities.
 * 2. {@link listTools} - Discover available tools and their schemas.
 * 3. {@link callTool} - Invoke a tool by name with arguments.
 *
 * All communication uses JSON-RPC 2.0 over HTTP POST to the `/mcp` endpoint.
 */
export class NocturnusAIMCPClient {
  private readonly baseUrl: string;
  private readonly apiKey?: string;
  private readonly database: string;
  private readonly tenantId: string;
  private nextId: number = 1;
  private initialized: boolean = false;

  /**
   * Create a new NocturnusAIMCPClient.
   *
   * @param config - Connection configuration.
   */
  constructor(config: NocturnusAIConfig) {
    this.baseUrl = config.baseUrl.replace(/\/+$/, '');
    this.apiKey = config.apiKey;
    this.database = config.database ?? 'default';
    this.tenantId = config.tenantId ?? 'default';
  }

  /**
   * Initialize the MCP connection.
   *
   * Performs the MCP handshake with the server, receiving the protocol version,
   * server capabilities, and server identity information.
   *
   * This must be called before {@link listTools} or {@link callTool}, matching
   * the MCP specification lifecycle.
   *
   * @returns MCP server information including protocol version and capabilities.
   * @throws {McpError} If the server returns a JSON-RPC error.
   *
   * @example
   * ```ts
   * const info = await mcp.initialize();
   * console.log(info.protocolVersion); // "2025-11-25"
   * console.log(info.serverInfo.name); // "nocturnusai"
   * ```
   */
  async initialize(): Promise<McpServerInfo> {
    const result = await this.sendRequest<McpServerInfo>('initialize', {
      protocolVersion: '2025-11-25',
      capabilities: {},
      clientInfo: {
        name: 'nocturnusai-sdk',
        version: '0.2.3',
      },
    });
    this.initialized = true;
    return result;
  }

  /**
   * List all available MCP tools.
   *
   * Returns the set of tools exposed by the NocturnusAI MCP server, each with
   * a name, description, and JSON Schema for its input parameters.
   *
   * @returns Array of tool descriptors.
   * @throws {McpError} If the server returns a JSON-RPC error.
   * @throws {Error} If {@link initialize} has not been called first.
   *
   * @example
   * ```ts
   * const tools = await mcp.listTools();
   * for (const tool of tools) {
   *   console.log(`${tool.name}: ${tool.description}`);
   * }
   * ```
   */
  async listTools(): Promise<McpTool[]> {
    this.ensureInitialized();
    const result = await this.sendRequest<{ tools: McpTool[] }>('tools/list');
    return result.tools;
  }

  /**
   * Call an MCP tool by name.
   *
   * Invokes a specific tool on the NocturnusAI MCP server with the given arguments.
   * The arguments object should match the tool's input schema (as returned by
   * {@link listTools}).
   *
   * @param name - The tool name (e.g. "assert_fact", "infer", "context_window").
   * @param args - Tool arguments matching the tool's input schema.
   * @returns The tool result containing content items and an optional error flag.
   * @throws {McpError} If the server returns a JSON-RPC error.
   * @throws {Error} If {@link initialize} has not been called first.
   *
   * @example
   * ```ts
   * // Assert a fact via MCP
   * const result = await mcp.callTool('assert_fact', {
   *   predicate: 'parent',
   *   args: ['alice', 'bob'],
   * });
   * console.log(result.content[0].text);
   *
   * // Run inference via MCP
   * const inferResult = await mcp.callTool('infer', {
   *   predicate: 'grandparent',
   *   args: ['?who', 'charlie'],
   *   withProof: true,
   * });
   * ```
   */
  async callTool(name: string, args: Record<string, unknown>): Promise<McpToolResult> {
    this.ensureInitialized();
    return this.sendRequest<McpToolResult>('tools/call', {
      name,
      arguments: args,
    });
  }

  /**
   * Send a ping to verify the MCP connection is alive.
   *
   * @returns An empty object on success.
   * @throws {McpError} If the server returns a JSON-RPC error.
   */
  async ping(): Promise<Record<string, never>> {
    return this.sendRequest<Record<string, never>>('ping');
  }

  // -----------------------------------------------------------------------
  // Private helpers
  // -----------------------------------------------------------------------

  /**
   * Ensure that {@link initialize} has been called.
   */
  private ensureInitialized(): void {
    if (!this.initialized) {
      throw new Error(
        'NocturnusAIMCPClient has not been initialized. Call initialize() before using other methods.',
      );
    }
  }

  /**
   * Build the standard HTTP headers for MCP requests.
   */
  private buildHeaders(): Record<string, string> {
    const headers: Record<string, string> = {
      'Content-Type': 'application/json',
      'X-Database': this.database,
      'X-Tenant-ID': this.tenantId,
    };
    if (this.apiKey) {
      headers['X-API-Key'] = this.apiKey;
    }
    return headers;
  }

  /**
   * Send a JSON-RPC 2.0 request to the MCP endpoint and return the result.
   *
   * @param method - The JSON-RPC method name.
   * @param params - Optional parameters for the method.
   * @returns The parsed result from the JSON-RPC response.
   * @throws {McpError} If the response contains a JSON-RPC error.
   */
  private async sendRequest<T>(method: string, params?: Record<string, unknown>): Promise<T> {
    const id = this.nextId++;

    const request: JsonRpcRequest = {
      jsonrpc: '2.0',
      id,
      method,
      params,
    };

    const url = `${this.baseUrl}/mcp`;

    const response = await fetch(url, {
      method: 'POST',
      headers: this.buildHeaders(),
      body: JSON.stringify(request),
    });

    if (!response.ok) {
      throw new Error(
        `MCP HTTP request failed: ${response.status} ${response.statusText}`,
      );
    }

    const rpcResponse = (await response.json()) as JsonRpcResponse;

    if (rpcResponse.error) {
      throw new McpError(rpcResponse.error);
    }

    return rpcResponse.result as T;
  }
}
