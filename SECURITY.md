# Security Policy

## Supported Versions

| Version | Supported |
|---------|-----------|
| 0.1.x   | Yes       |

---

## Reporting a Vulnerability

**Please do not open a public GitHub issue for security vulnerabilities.**

Report vulnerabilities via [GitHub Security Advisories](https://github.com/Auctalis/nocturnusai/security/advisories/new) (private, directly to maintainers).

Include:
- Description of the vulnerability and its potential impact
- Steps to reproduce
- Affected version(s)
- Any suggested mitigations you've identified

**Response timeline:**
- Acknowledgement within 48 hours
- Assessment and severity rating within 5 business days
- Patch release for critical/high severity within 14 days
- Coordinated public disclosure after the fix is released

---

## Boundary of Responsibility

NocturnusAI is a **logic inference engine**. The security boundary is the engine itself — its HTTP API surface, its storage layer, and its inference algorithms. The following are explicitly **outside** the engine's security boundary:

| Responsibility | Owner |
|---|---|
| Accuracy of asserted facts | **User / calling agent** |
| Input sanitization (NL text to `/extract`, `/tell`, `/synthesize`) | **User / calling agent** |
| Actions taken based on engine output | **User / agent layer** |
| Network isolation and firewall rules | **User / infrastructure team** |
| TLS termination and certificate management | **User / infrastructure team** |
| Container runtime and host OS security | **User / infrastructure team** |
| Monitoring, alerting, and incident response | **User / operations team** |

---

## Deployment Security Requirements

### Network Isolation (Required for Production)

NocturnusAI **must** be deployed behind a network boundary in production. Do not expose the API directly to the public internet without:

1. A reverse proxy with rate limiting (nginx, Caddy, Traefik)
2. TLS termination (engine supports native TLS, or terminate at proxy)
3. Authentication enabled (`AUTH_ENABLED=true` with RBAC, or `API_KEY` for simple deployments)

```bash
# Bind to localhost only (single-machine)
HOST=127.0.0.1

# Or restrict to internal network
HOST=10.0.0.5
```

### Authentication

NocturnusAI ships with auth **disabled by default** for the quickstart experience. This is intentional — **do not run unauthenticated in production.**

Three auth modes:

| Mode | Config | Use Case |
|---|---|---|
| Open (default) | No `API_KEY`, `AUTH_ENABLED=false` | Local dev only |
| Single key | `API_KEY=<secret>` | Simple deployments |
| Full RBAC | `AUTH_ENABLED=true` | Production |

```bash
# Production: enable RBAC and change default credentials
AUTH_ENABLED=true
NOCTURNUSAI_ADMIN_USER=admin
NOCTURNUSAI_ADMIN_PASS=<strong-random-password>
```

### Encryption at Rest

Enable AES-256 encryption for WAL and snapshot files:

```bash
ENCRYPTION_KEY=<64-hex-char key>   # Generate: openssl rand -hex 32
```

### TLS

Enable native TLS or terminate at a reverse proxy:

```bash
TLS_ENABLED=true
TLS_PORT=9443
TLS_KEYSTORE_PATH=/certs/keystore.p12
TLS_KEYSTORE_PASSWORD=<keystore-password>
```

---

## Input Validation

It is the **user's responsibility** to validate and sanitize inputs before they reach the engine. This includes:

- **Natural language text** sent to `/extract` and `/synthesize` — the engine uses an LLM to parse these; adversarial inputs could produce unexpected facts
- **Predicate and argument values** sent to `/tell` and `/teach` — the engine stores these as-is
- **DSL commands** sent to `/execute` — the parser is strict but user-provided logic should be reviewed

The engine enforces structural validation (well-formed predicates, valid variable names, type constraints) but does not validate semantic correctness or real-world accuracy of inputs.

---

## Dependency Security

NocturnusAI depends on:

| Component | Purpose |
|---|---|
| JVM (Temurin 17/21) | Runtime |
| Ktor 2.3.7 + Netty | HTTP server |
| kotlinx-serialization | JSON parsing |
| Logback + SLF4J | Logging |
| Micrometer + Prometheus | Metrics |

The authors are not liable for vulnerabilities in upstream dependencies. Users should:

- Monitor [GitHub Security Advisories](https://github.com/Auctalis/nocturnusai/security) for NocturnusAI-specific issues
- Run `trivy` or equivalent scanners on the Docker image in their CI pipeline
- Keep their deployment's base image and JVM up to date

---

## Security Considerations for Agent Integrations

When connecting AI agents to NocturnusAI via MCP, SDK, or HTTP:

1. **Principle of least privilege** — use RBAC to issue read-only keys to agents that only query, write keys to agents that assert facts
2. **Tenant isolation** — use `X-Tenant-ID` headers to scope agent knowledge, preventing cross-agent information leakage
3. **Audit logging** — enable structured JSON logging (`LOG_FORMAT=json`) and forward to your SIEM for agent action auditing
4. **Rate limiting** — implement rate limiting at the proxy layer to prevent runaway agents from overwhelming the engine
5. **Fact review** — for high-stakes deployments, consider a human-review queue before agent-asserted facts enter the production knowledge base
