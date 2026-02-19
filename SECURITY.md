# Security Policy

## Supported Versions

| Version | Supported |
|---------|-----------|
| 0.1.x   | ✅ Yes    |

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

## Security Considerations

### Default configuration (insecure by default, intentional)

NocturnusAI ships with auth disabled (`AUTH_ENABLED=false`) and no TLS. This is intentional for the quickstart experience — **do not expose the default configuration to the internet**.

For production:

```bash
# Enable auth
AUTH_ENABLED=true
NOCTURNUSAI_ADMIN_PASS=<strong-random-password>

# Enable TLS
TLS_ENABLED=true
TLS_PORT=9443
TLS_KEYSTORE_PATH=/certs/keystore.p12
TLS_KEYSTORE_PASSWORD=<keystore-password>

# Encrypt data at rest
ENCRYPTION_KEY=<64-hex-char AES-256 key>   # openssl rand -hex 32
```

### API keys

Set `API_KEY` to require bearer token auth on all endpoints without the full role system:

```bash
API_KEY=your-secret-key
# Client: Authorization: Bearer your-secret-key
```

### Network exposure

Bind to `localhost` in single-machine deployments:

```bash
HOST=127.0.0.1
```
