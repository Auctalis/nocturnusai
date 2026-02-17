"""MCP (Model Context Protocol) client helper for NocturnusAI.

Wraps the NocturnusAI server's ``POST /mcp`` endpoint, which speaks the
JSON-RPC 2.0 based MCP protocol.  This allows using NocturnusAI as an
MCP tool provider in any MCP-compatible AI agent framework.

Usage::

    from nocturnusai.mcp import NocturnusAIMCPClient

    async with NocturnusAIMCPClient("http://localhost:9300") as mcp:
        await mcp.initialize()
        tools = await mcp.list_tools()
        result = await mcp.call_tool("assert_fact", {
            "predicate": "parent",
            "args": ["alice", "bob"],
        })
"""

from __future__ import annotations

import logging
from typing import Any

import httpx

from nocturnusai.exceptions import (
    NocturnusAIAPIError,
    NocturnusAIConnectionError,
    NocturnusAITimeoutError,
)

logger = logging.getLogger("nocturnusai.mcp")

# JSON-RPC 2.0 error codes.
_JSONRPC_PARSE_ERROR = -32700
_JSONRPC_INVALID_REQUEST = -32600
_JSONRPC_METHOD_NOT_FOUND = -32601
_JSONRPC_INVALID_PARAMS = -32602
_JSONRPC_INTERNAL_ERROR = -32603


class MCPError(NocturnusAIAPIError):
    """Raised when the MCP endpoint returns a JSON-RPC error.

    Attributes:
        jsonrpc_code: The JSON-RPC 2.0 error code.
        jsonrpc_message: The error message from the server.
        jsonrpc_data: Optional additional error data.
    """

    def __init__(
        self,
        jsonrpc_code: int,
        jsonrpc_message: str,
        jsonrpc_data: Any = None,
    ) -> None:
        self.jsonrpc_code = jsonrpc_code
        self.jsonrpc_message = jsonrpc_message
        self.jsonrpc_data = jsonrpc_data
        super().__init__(
            message=f"MCP error [{jsonrpc_code}]: {jsonrpc_message}",
            status_code=200,  # JSON-RPC errors arrive in 200 responses.
            error_code=str(jsonrpc_code),
        )


class MCPToolDefinition:
    """A tool definition returned by the MCP ``tools/list`` method.

    Attributes:
        name: The tool name (e.g., ``"assert_fact"``).
        description: Human-readable description of the tool.
        input_schema: JSON Schema describing the tool's input parameters.
    """

    def __init__(self, name: str, description: str, input_schema: dict[str, Any]) -> None:
        self.name = name
        self.description = description
        self.input_schema = input_schema

    def __repr__(self) -> str:
        return f"MCPToolDefinition(name={self.name!r})"


class MCPToolResult:
    """The result of calling an MCP tool.

    Attributes:
        content: List of content blocks returned by the tool. Each block
            is a dict with ``"type"`` and ``"text"`` keys.
        is_error: Whether the tool execution resulted in an error.
    """

    def __init__(self, content: list[dict[str, Any]], is_error: bool = False) -> None:
        self.content = content
        self.is_error = is_error

    @property
    def text(self) -> str:
        """Extract and concatenate all text content blocks."""
        parts: list[str] = []
        for block in self.content:
            if block.get("type") == "text":
                parts.append(block.get("text", ""))
        return "\n".join(parts)

    def __repr__(self) -> str:
        truncated = self.text[:80] + ("..." if len(self.text) > 80 else "")
        return f"MCPToolResult(is_error={self.is_error}, text={truncated!r})"


class NocturnusAIMCPClient:
    """MCP client for the NocturnusAI server.

    Communicates with the NocturnusAI server's ``POST /mcp`` endpoint using
    the JSON-RPC 2.0 based Model Context Protocol. This client can be used
    to discover available tools and invoke them.

    Args:
        base_url: The base URL of the NocturnusAI server (e.g., ``http://localhost:9300``).
        api_key: Optional API key for authentication.
        database: The database name (sent via ``X-Database`` header).
        tenant_id: The tenant ID (sent via ``X-Tenant-ID`` header).
        timeout: Request timeout in seconds.

    Example::

        async with NocturnusAIMCPClient("http://localhost:9300") as mcp:
            await mcp.initialize()
            tools = await mcp.list_tools()
            for tool in tools:
                print(f"  {tool.name}: {tool.description}")
            result = await mcp.call_tool("query", {
                "predicate": "parent",
                "args": ["?x", "bob"],
            })
            print(result.text)
    """

    def __init__(
        self,
        base_url: str,
        api_key: str | None = None,
        database: str = "default",
        tenant_id: str = "default",
        timeout: float = 30.0,
    ) -> None:
        self._base_url = base_url.rstrip("/")
        self._api_key = api_key
        self._database = database
        self._tenant_id = tenant_id
        self._timeout = timeout
        self._client: httpx.AsyncClient | None = None
        self._request_id: int = 0
        self._server_info: dict[str, Any] | None = None
        self._protocol_version: str | None = None

    def _build_headers(self) -> dict[str, str]:
        """Build HTTP headers for MCP requests."""
        headers: dict[str, str] = {
            "X-Database": self._database,
            "X-Tenant-ID": self._tenant_id,
            "Content-Type": "application/json",
            "Accept": "application/json",
        }
        if self._api_key:
            headers["X-API-Key"] = self._api_key
        return headers

    async def _ensure_client(self) -> httpx.AsyncClient:
        """Lazily create or return the underlying httpx.AsyncClient."""
        if self._client is None or self._client.is_closed:
            self._client = httpx.AsyncClient(
                base_url=self._base_url,
                headers=self._build_headers(),
                timeout=httpx.Timeout(self._timeout),
            )
        return self._client

    def _next_id(self) -> int:
        """Generate the next JSON-RPC request ID."""
        self._request_id += 1
        return self._request_id

    async def _jsonrpc(
        self,
        method: str,
        params: dict[str, Any] | None = None,
    ) -> Any:
        """Send a JSON-RPC 2.0 request to the MCP endpoint.

        Args:
            method: The JSON-RPC method name.
            params: Optional parameters for the method.

        Returns:
            The ``result`` field from the JSON-RPC response.

        Raises:
            MCPError: If the server returns a JSON-RPC error.
            NocturnusAIConnectionError: If the server is unreachable.
            NocturnusAITimeoutError: If the request times out.
        """
        client = await self._ensure_client()
        request_id = self._next_id()

        payload: dict[str, Any] = {
            "jsonrpc": "2.0",
            "id": request_id,
            "method": method,
        }
        if params is not None:
            payload["params"] = params

        try:
            response = await client.post("/mcp", json=payload)
        except httpx.ConnectError as exc:
            raise NocturnusAIConnectionError(
                f"Failed to connect to NocturnusAI MCP endpoint at {self._base_url}/mcp: {exc}"
            ) from exc
        except httpx.TimeoutException as exc:
            raise NocturnusAITimeoutError(
                f"MCP request timed out after {self._timeout}s: {exc}"
            ) from exc

        if response.status_code >= 400:
            raise NocturnusAIAPIError(
                message=f"MCP endpoint returned HTTP {response.status_code}: {response.text}",
                status_code=response.status_code,
            )

        body = response.json()

        # Check for JSON-RPC error.
        if "error" in body and body["error"] is not None:
            error = body["error"]
            raise MCPError(
                jsonrpc_code=error.get("code", _JSONRPC_INTERNAL_ERROR),
                jsonrpc_message=error.get("message", "Unknown error"),
                jsonrpc_data=error.get("data"),
            )

        return body.get("result")

    async def initialize(self) -> dict[str, Any]:
        """Initialize the MCP session.

        Sends the ``initialize`` method to the server and stores the
        server info and protocol version for later reference.

        Returns:
            The initialization result containing ``protocolVersion``,
            ``capabilities``, and ``serverInfo``.

        Example::

            info = await mcp.initialize()
            print(f"Server: {info['serverInfo']['name']}")
            print(f"Protocol: {info['protocolVersion']}")
        """
        result = await self._jsonrpc("initialize")
        if isinstance(result, dict):
            self._server_info = result.get("serverInfo")
            self._protocol_version = result.get("protocolVersion")
        return result

    async def list_tools(self) -> list[MCPToolDefinition]:
        """List all tools available on the NocturnusAI MCP server.

        Returns:
            A list of :class:`MCPToolDefinition` objects describing each
            available tool.

        Example::

            tools = await mcp.list_tools()
            for tool in tools:
                print(f"{tool.name}: {tool.description}")
        """
        result = await self._jsonrpc("tools/list")
        tools: list[MCPToolDefinition] = []

        if isinstance(result, dict):
            raw_tools = result.get("tools", [])
        elif isinstance(result, list):
            raw_tools = result
        else:
            return tools

        for tool_data in raw_tools:
            if isinstance(tool_data, dict):
                tools.append(MCPToolDefinition(
                    name=tool_data.get("name", ""),
                    description=tool_data.get("description", ""),
                    input_schema=tool_data.get("inputSchema", {}),
                ))

        return tools

    async def call_tool(
        self,
        name: str,
        arguments: dict[str, Any] | None = None,
    ) -> MCPToolResult:
        """Call a tool on the NocturnusAI MCP server.

        Args:
            name: The tool name (e.g., ``"assert_fact"``, ``"query"``,
                ``"infer"``).
            arguments: Tool-specific arguments as a dict.

        Returns:
            An :class:`MCPToolResult` containing the tool's output.

        Raises:
            MCPError: If the tool execution fails on the server side.

        Example::

            result = await mcp.call_tool("assert_fact", {
                "predicate": "parent",
                "args": ["alice", "bob"],
            })
            print(result.text)

            result = await mcp.call_tool("infer", {
                "predicate": "grandparent",
                "args": ["?who", "charlie"],
            })
            print(result.text)
        """
        params: dict[str, Any] = {"name": name}
        if arguments is not None:
            params["arguments"] = arguments

        result = await self._jsonrpc("tools/call", params)

        if isinstance(result, dict):
            content = result.get("content", [])
            is_error = result.get("isError", False)
            return MCPToolResult(content=content, is_error=is_error)

        # Unexpected format — wrap in a text block.
        return MCPToolResult(
            content=[{"type": "text", "text": str(result)}],
            is_error=False,
        )

    async def ping(self) -> bool:
        """Ping the MCP server to check connectivity.

        Returns:
            ``True`` if the server responds successfully.

        Raises:
            NocturnusAIConnectionError: If the server is unreachable.
        """
        await self._jsonrpc("ping")
        return True

    @property
    def server_info(self) -> dict[str, Any] | None:
        """Server information from the last ``initialize`` call."""
        return self._server_info

    @property
    def protocol_version(self) -> str | None:
        """MCP protocol version from the last ``initialize`` call."""
        return self._protocol_version

    async def close(self) -> None:
        """Close the underlying HTTP client and release resources."""
        if self._client is not None and not self._client.is_closed:
            await self._client.aclose()
            self._client = None

    async def __aenter__(self) -> NocturnusAIMCPClient:
        """Enter the async context manager."""
        await self._ensure_client()
        return self

    async def __aexit__(self, *args: Any) -> None:
        """Exit the async context manager and close the client."""
        await self.close()
