# Changelog

All notable changes to this project will be documented in this file.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.0.0/).
This project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

---

## [0.3.7] — 2026-04-14

### Security
- **SSRF** — `HTTP_GET_JSON` built-in predicate is now disabled by default and hardened when enabled. Set `ENABLE_HTTP_BUILTIN=true` to opt in. When enabled: https-only (override with `HTTP_BUILTIN_ALLOW_HTTP=true`), redirects disabled, loopback/link-local/site-local/multicast/CGNAT/IPv6-ULA and the cloud metadata IP (169.254.169.254) blocked after DNS resolution. Optional host allowlist via `HTTP_BUILTIN_ALLOWED_HOSTS`.
- **Default admin credentials** — server refuses to start in `AUTH_ENABLED=true` mode if `NOCTURNUSAI_ADMIN_PASS` is unset or still uses the documented default. Export `NOCTURNUSAI_ALLOW_DEFAULT_ADMIN_PASS=true` to bypass for test/CI.
- **MCP tenant header** — `POST /mcp` and `GET /mcp/sse` now require `X-Tenant-ID` (previously silently defaulted to `default`, causing cross-tenant data collision for clients that forgot the header).
- **Constant-time credential compare** — `/auth/bootstrap` now uses `MessageDigest.isEqual` for username and password comparison.
- **Logback** — bumped to 1.5.13 (fixes CVE-2023-6378 and CVE-2024-12798).

---

## [0.2.2] — 2026-04-04

### Changed
- Backend now auto-selects a reachable local Ollama endpoint as the default LLM provider when no explicit provider or API key is configured

### Fixed
- Python SDK release artifacts now include `nocturnusai.client` even when built from the monorepo
- Python SDK `ingest_and_optimize()` now uses the server’s `/context/ingest` endpoint instead of failing on `/extract` when no LLM extractor is configured

---

## [0.2.1] — 2026-04-04

### Added
- Context-management REST smoke tests for `/context`, `/context/optimize`, `/context/diff`, `/context/summary`, `/context/session/clear`, and `/context/ingest`
- Explicit `/userguide` route coverage in server observability tests
- A curl-based value proof demo for goal-driven context reduction

### Changed
- Repositioned the docs site and GitHub-facing Markdown around context optimization as the primary workflow
- Added first-class TypeScript SDK methods for goal-driven context optimization, diffs, summaries, session clearing, and one-shot ingestion
- Expanded API, CLI, SDK, MCP, integration, and multi-tenancy docs to map real methods and endpoints for context workflows

### Fixed
- TypeScript SDK docs and examples now match the shipped client methods
- MCP context docs and server implementation now accept the legacy `minRelevance` alias alongside `minSalience`
- CLI docs now reflect the actual Docker-compose/server workflow instead of a non-existent CLI container
- Public install and monitoring docs now point to the current repository installer and compose commands

---

## [0.1.19] — 2026-02-26

### Added
- CLI REPL now automatically restarts Docker after setup changes take effect

---

## [0.1.18] — 2026-02-26

### Fixed
- Generous timeouts for Ollama requests to avoid premature failures
- Graceful error handling when LLM provider is unavailable or returns errors

---

## [0.1.17] — 2026-02-26

### Fixed
- Ollama `LLM_BASE_URL` was missing the required `/v1` suffix
- CLI REPL now supports in-session tenant and database switching

---

## [0.1.16] — 2026-02-26

### Fixed
- Removed `host-gateway` from Docker Compose (causes compatibility issues); host address is now detected at setup time

---

## [0.1.15] — 2026-02-26

### Fixed
- `sed -i` compatibility on macOS CI runners for version injection during release builds

---

## [0.1.14] — 2026-02-26

### Added
- CLI tab completion, rich prompt styling, and persistent command history
- Ollama model selection: query locally installed models or choose from a curated list
- Ollama is now a top-level provider option in the interactive setup wizard

---

## [0.1.13] — 2026-02-26

### Added
- Version numbers displayed in server startup logs, CLI banner, and `/health` endpoint response
- Ollama version surfaced in `install.sh` output

---

## [0.1.12] — 2026-02-26

### Fixed
- Empty `LLM_BASE_URL` passed from Docker Compose caused a `Connection refused` error at startup

---

## [0.1.11] — 2026-02-26

### Fixed
- LLM extraction was broken: stale Ollama base URL and missing `EXTRACTION_ENABLED` environment variable

---

## [0.1.10] — 2026-02-26

### Fixed
- CLI always sends the `X-Tenant-ID` header on every request (defaults to `"default"` when not explicitly set)

---

## [0.1.9] — 2026-02-26

### Added
- Clean install option with explicit data-deletion consent prompt
- Post-install developer experience optimised for zero friction to first working command

### Fixed
- Health check now correctly reports healthy for valid local development configurations
- Uninstall command requires explicit consent before every destructive action
- Data persistence uses bind mounts; uninstall path is safe and predictable
- Empty `API_KEY` is treated as unset — no accidental authentication enforcement in dev mode
- Default configuration uses no auth for local development; auth is clearly documented as optional
- Auth status shown in REPL banner with actionable guidance on `UNAUTHORIZED` errors

---

## [0.1.7] — 2026-02-26

### Added
- `nocturnusai uninstall` command
- Interactive model selection during setup
- No-`sudo` install path
- CLI auto-configuration after setup completes

---

## [0.1.4] — [0.1.6] — 2026-02-26

### Added
- `nocturnusai setup` subcommand — install wizard moved into the CLI binary for a first-class experience
- Support for an existing host Ollama instance in the setup wizard
- Improved install banner with connection defaults, LLM provider examples, and clearer CLI retry instructions
- Zero-friction developer experience restructure for the install flow

### Fixed
- GitHub Release workflow now waits for CLI binaries to be available before creating the release
- Download progress bar displayed during CLI binary download
- Fall back to Docker image pull when a pre-built CLI binary is unavailable
- `--help` flag no longer hangs the install script
- LLM provider API keys handled correctly for multi-provider setups
- `darwin` correctly mapped to `macos` in CLI binary filename during install
- `X-Tenant-ID` header added to install script `curl` examples
- Minimal Docker Compose file generated by the installer rather than downloaded
- Removed optional `ollama` `depends_on` stanza that breaks `podman-compose`
- Proper Podman support with corrected post-install setup dialogue
- Environment variables passed explicitly in Compose (avoids `env_file` pitfalls)
- PATH-based install working correctly

---

## [0.1.1] — [0.1.3] — 2026-02-25

### Added
- Proper 16×32 px owl favicon replacing the placeholder `favicon.ico`

### Fixed
- SDK versions synced with git tag; bumped to 0.1.1
- Install script: added error trap and pull-or-build fallback logic to prevent silent failures
- Install script no longer defaults to Ollama on non-interactive (piped) runs
- Hero install command updated to the real GitHub raw URL and rendered full-width
- All internal links on the GitHub Pages site use `BASE_URL` for correct relative routing
- GitHub Release unblocked from cancelled CLI runner jobs

### Changed
- Documentation: clarified Docker requirement on front page and how-it-works section
- Documentation: legal notice moved below quickstart; server banner added; internal use explicitly permitted
- Documentation: added liability disclaimers, GIGO warning, and hardened security policy

### Removed
- Committed dev artefacts (logs, scratch docs) removed from repository
- `.vscode/` directory stopped being tracked (already covered by `.gitignore`)

---

## [0.1.0] — 2026-02-21

Initial public release.

### Core Engine (`nocturnusai-core`)
- Hexastore: 6-way indexed in-memory fact store (SPO/SOP/PSO/POS/OSP/OPS)
- Backward chaining (SLD resolution, Prolog-style) with depth limit
- Forward chaining (Rete engine) triggered on fact assertion
- Unifier with full substitution propagation
- Truth Maintenance System (TMS) — auto-cascade retraction of derived facts
- Temporal facts: `validFrom`, `validUntil`, `ttl`, `createdAt`
- Salience scoring: recency × frequency × priority
- Memory lifecycle: consolidation and decay
- ACID transactions with contradiction detection
- Write-ahead log (WAL) + snapshot persistence
- EventBus for pub/sub knowledge change notifications
- Scope-based isolation for multi-tenant and hypothetical reasoning

### HTTP Server (`nocturnusai-server`)
- Simplified API: `POST /tell`, `/ask`, `/query`, `/teach`, `/forget`
- Full API: `POST /assert/fact`, `/assert/rule`, `/assert/template`, `/infer`, `/retract`, `/execute`
- Memory API: `/memory/context`, `/memory/query/temporal`, `/memory/query/salient`, `/memory/consolidate`, `/memory/decay`
- Transaction API: `/tx/begin`, `/tx/commit/:id`, `/tx/rollback/:id`
- MCP server: `POST /mcp` (JSON-RPC 2.0) + `GET /mcp/sse` (streaming transport)
- 9 MCP tools: tell, teach, ask, forget, recall, context, compress, cleanup, predicates
- A2A agent discovery: `GET /.well-known/agent.json`
- LLM integration: `POST /extract`, `/extract/batch`, `/synthesize` (Anthropic, OpenAI, Google, Ollama)
- Multi-tenancy: `X-Database` and `X-Tenant-ID` headers
- Optional auth: bearer token, role-based access, AES-256 encryption
- TLS support
- Prometheus metrics at `/metrics`
- Auto-generated LLM documentation at `/llm.txt`
- Docker image: `ghcr.io/Auctalis/nocturnusai`

### Python SDK (`nocturnusai` on PyPI)
- Async client (`NocturnusAIClient`) and sync wrapper (`SyncNocturnusAIClient`)
- LangChain tool wrappers: assert, query, infer, context

### TypeScript SDK (`nocturnusai-sdk` on npm)
- `NocturnusAIClient` — zero runtime dependencies
- `NocturnusAIMCPClient` — MCP JSON-RPC 2.0 client
- SSE event subscription

### CLI (`nocturnusai-cli`)
- Interactive REPL connecting to a running server
- Commands: ask, tell, teach, forget, inspect, context, compress, cleanup, dsl, use, dbs, health
