# Developer Adoption Funnel — Design Document

**Goal**: Fix the conversion funnel from "I found NocturnusAI" → "I'm using it in my project" by adding missing pages, navigation, and cross-linking to the documentation site.

**Primary audience**: Python/AI developers building agent systems who are evaluating NocturnusAI.

**Approach**: Developer Conversion Funnel (Approach 1 from brainstorming) — focused, fast, maximum adoption impact.

---

## Section 1: Integrations Hub Page (`/docs/integrations`)

**Purpose**: Marketing-oriented entry point that showcases the 5 framework integrations at a glance. Becomes the destination for Hero badges, Navbar, and Quickstart links.

**Content structure**:
- Hero banner: "Drop NocturnusAI into your agent stack in one line"
- Framework card grid (5 cards): LangChain, CrewAI, AutoGen, LangGraph, OpenAI Agents SDK, Anthropic SDK
- Each card contains:
  - Framework name + brief description (1 sentence)
  - Install command: `pip install nocturnusai[framework]`
  - 5-line code snippet showing basic usage
  - "Full docs →" link to the relevant section on `/docs/sdks`
- Bottom section: "Don't see your framework? NocturnusAI works with any Python or TypeScript agent via HTTP API, MCP protocol, or direct SDK."
- Links to: Python SDK docs, TypeScript SDK docs, MCP integration, API reference

**Design notes**:
- Cards use the existing site color scheme (cyan/purple accents on dark)
- Follow the existing DocsLayout component
- Cards should be visually scannable — developer should find their framework in < 3 seconds

---

## Section 2: FAQ Page (`/docs/faq`)

**Purpose**: Answer the top questions that block developers from adopting. Catch evaluators who need reassurance before committing.

**Questions to answer**:

1. **"How is NocturnusAI different from RAG / vector search?"**
   - RAG retrieves text that *looks* related. NocturnusAI *derives* what must be true. Logical inference vs. similarity search. They're complementary — use RAG for unstructured retrieval, NocturnusAI for verified reasoning.

2. **"Do I need to learn Prolog or logic programming?"**
   - No. The simplified API (`/tell`, `/ask`, `/teach`, `/forget`) and natural language extraction handle most use cases. The logic runs under the hood. Power users can use the DSL for advanced rules.

3. **"What happens if my agent asserts contradictory facts?"**
   - Configurable conflict resolution: REJECT (default), NEWEST_WINS, CONFIDENCE (highest wins), or KEEP_BOTH. Truth Maintenance System auto-retracts derived facts when premises change.

4. **"How much memory does it use? Can it handle production load?"**
   - In-memory store with WAL + snapshots. Sub-100ms fact retrieval. ACID transactions. Designed for agent workloads, not big data analytics. Good for millions of facts per tenant.

5. **"Is it open source? What's the license?"**
   - Business Source License 1.1. Free for non-production use, evaluation, and development. Contact for production licensing.

6. **"Can I use this with OpenAI / Anthropic / local models?"**
   - Yes. NocturnusAI is model-agnostic. Use it with any LLM via the framework integrations (LangChain, AutoGen, etc.), MCP protocol, or direct HTTP API. Optional LLM integration for natural language extraction supports OpenAI, Anthropic, Google, and Ollama.

7. **"How do I get started?"**
   - Link to Quickstart. One-line install, working in 5 minutes.

**Design notes**:
- Collapsible accordion or flat list (flat list preferred for scannability)
- Each answer is 2-4 sentences max, with "Learn more →" links to deeper docs

---

## Section 3: Next-Steps Cards on Every Doc Page

**Purpose**: Prevent dead-end pages. Keep developers moving through the funnel.

**Implementation**: A reusable `NextSteps.astro` component that accepts an array of `{ title, description, href }` objects and renders 2-3 cards at the bottom of each page.

**Mapping** (which pages link where):

| Page | Next Steps |
|------|-----------|
| Quickstart | Integrations, Core Concepts, API Reference |
| Integrations (NEW) | SDKs (full docs), MCP Integration, Quickstart |
| FAQ (NEW) | Quickstart, Integrations, Core Concepts |
| SDKs | Integrations, API Reference, MCP Integration |
| MCP Integration | SDKs, API Reference, CLI Reference |
| Core Concepts | API Reference, CLI Reference, SDKs |
| API Reference | SDKs, MCP Integration, Core Concepts |
| CLI Reference | API Reference, Core Concepts, Operations |
| Security & Auth | Multi-Tenancy, Operations, API Reference |
| Multi-Tenancy | Security & Auth, Operations, API Reference |
| LLM Integration | Quickstart, API Reference, SDKs |
| Operations | Security & Auth, Multi-Tenancy, API Reference |

**Design notes**:
- 3-column card grid on desktop, stacked on mobile
- Subtle border, hover effect consistent with site style
- Placed after final `<hr />` on each page

---

## Section 4: Sidebar Navigation Reorganization

**Purpose**: Group the flat 10-page list into logical categories so developers can orient themselves.

**New structure** (in DocsLayout.astro `navItems`):

```
Getting Started
  - Quickstart
  - Integrations (NEW)
  - FAQ (NEW)

SDKs & Protocols
  - Python SDK
  - TypeScript SDK
  - MCP Integration
  - CLI Reference

Reference
  - API Reference
  - Core Concepts
  - LLM Integration

Operations
  - Security & Auth
  - Multi-Tenancy
  - Operations
```

**Design notes**:
- Keep existing category title styling (uppercase, small, slate-500)
- Reuses the exact same navItems structure, just reorganized
- Python/TypeScript SDK links now go to `/docs/sdks#python` and `/docs/sdks#typescript`
- Framework-specific links (CrewAI, AutoGen, etc.) move from SDKs sidebar group to the Integrations page

---

## Section 5: Hero "Works With" Badges → Link to Integrations

**Purpose**: The Hero component already shows framework badges (LangChain, CrewAI, AutoGen, Claude, etc.) but they don't link anywhere. Make them click through to the Integrations page.

**Implementation**: In `Hero.astro`, wrap each badge in an `<a>` tag linking to `/docs/integrations`. Alternatively, link each badge to its specific anchor (`/docs/integrations#crewai`, etc.).

---

## Summary of Deliverables

| Deliverable | Type | Effort |
|------------|------|--------|
| `/docs/integrations` page | New page | Medium |
| `/docs/faq` page | New page | Small |
| `NextSteps.astro` component | New component | Small |
| Next-steps cards on all 10 existing pages | Edit existing | Medium |
| Sidebar reorganization | Edit DocsLayout.astro | Small |
| Hero badge links | Edit Hero.astro | Tiny |

**Total estimated scope**: 5 tasks, ~1 week of focused execution.
