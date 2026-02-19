# Contributing to NocturnusAI

Thank you for your interest in contributing. This document covers how to get the project running locally, the PR process, and coding conventions.

---

## Development Setup

**Prerequisites**: JDK 17+, Docker (optional), Python 3.10+ (for SDK work), Node 20+ (for docs)

```bash
# Clone
git clone https://github.com/Auctalis/nocturnusai.git
cd nocturnusai

# Build and test the server
./gradlew build test

# Run the server locally
./gradlew :nocturnusai-server:run

# Run the CLI (requires a running server)
./gradlew :nocturnusai-cli:run

# Build with Docker
docker compose up --build

# Python SDK
cd sdks/python
pip install -e ".[langchain,dev]"
pytest

# TypeScript SDK
cd sdks/typescript
npm install && npm run build

# Docs site
cd site
npm install && npm run dev   # http://localhost:4321
```

---

## Making Changes

1. **Fork** the repository and create a branch: `git checkout -b feat/your-feature`
2. **Write tests** — all logic changes in `nocturnusai-core` must include a test
3. **Run the full test suite** before opening a PR: `./gradlew test`
4. **Open a PR** against `main` with a clear title and description

### Commit messages

Follow [Conventional Commits](https://www.conventionalcommits.org/):

```
feat: add temporal decay to salience scorer
fix: handle null scope in backward chainer
docs: add MCP streaming example
chore: bump Ktor to 2.3.8
test: add proof-tree roundtrip test
```

The first line should complete the sentence "If applied, this commit will…"

---

## What to work on

- Issues labeled `good first issue` are good entry points
- Issues labeled `help wanted` are higher-complexity but well-scoped
- Check the roadmap in GitHub Discussions before starting large features

---

## Code Style

- **Kotlin**: follow [Kotlin coding conventions](https://kotlinlang.org/docs/coding-conventions.html). 4-space indent, 120-char line limit.
- **Python**: `ruff` for linting, `mypy` for types. Run `ruff check .` and `mypy .` before committing.
- **TypeScript**: strict mode, no `any`.

---

## Testing

```bash
# All modules
./gradlew test

# Single test class
./gradlew :nocturnusai-core:test --tests "com.nocturnusai.TransactionTest"

# Python
pytest sdks/python/

# TypeScript
npm test --prefix sdks/typescript
```

---

## Reporting Bugs

Use the [bug report template](.github/ISSUE_TEMPLATE/bug_report.yml). Include:
- Server version (`curl http://localhost:9300/health`)
- Whether you're using Docker or running from source
- Minimal reproduction steps

For security vulnerabilities, see [SECURITY.md](SECURITY.md).
