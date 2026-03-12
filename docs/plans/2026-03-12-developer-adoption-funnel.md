# Developer Adoption Funnel Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Improve the docs site conversion funnel so Python/AI developers go from "I found this" to "I'm using it" by adding an Integrations hub page, FAQ page, NextSteps component, sidebar reorganization, and Hero badge links.

**Architecture:** All changes are to the Astro static site in `site/`. New pages use the existing `DocsLayout` component. A new `NextSteps.astro` component is added to all doc pages. The sidebar `navItems` array in `DocsLayout.astro` is reorganized into logical groups. Hero badges become clickable links.

**Tech Stack:** Astro, Tailwind CSS, HTML. No new dependencies. Site builds with `cd site && npm run build`.

---

### Task 1: Create the NextSteps Reusable Component

**Files:**
- Create: `site/src/components/NextSteps.astro`

**Context:** The Quickstart page already has a "What's Next?" section using `next-card` CSS class (defined in `site/src/styles/global.css:299`). We'll extract this pattern into a reusable component that every doc page can import.

**Step 1: Create the component**

Create `site/src/components/NextSteps.astro`:

```astro
---
interface Link {
    title: string;
    description: string;
    href: string;
}

interface Props {
    links: Link[];
}

const { links } = Astro.props;
---

<hr />
<h2>What's Next?</h2>
<div style="display: grid; grid-template-columns: repeat(2, 1fr); gap: 1rem; margin-top: 1.5rem;">
    {links.map(link => (
        <a href={link.href} class="next-card">
            <h4>{link.title} &rarr;</h4>
            <p>{link.description}</p>
        </a>
    ))}
</div>
```

**Step 2: Verify the site builds**

Run: `cd site && npm run build`
Expected: `BUILD SUCCESSFUL`, `12 page(s) built`

**Step 3: Commit**

```bash
git add site/src/components/NextSteps.astro
git commit -m "feat(site): add reusable NextSteps component for doc page cross-linking"
```

---

### Task 2: Create the Integrations Hub Page

**Files:**
- Create: `site/src/pages/docs/integrations.astro`

**Context:** This is a marketing-oriented page showcasing the 6 framework integrations (LangChain, CrewAI, AutoGen, LangGraph, OpenAI Agents, Anthropic SDK). Each gets a card with install command, code snippet, and link to full docs on `/docs/sdks`. Uses the `DocsLayout` wrapper (imported from `../../layouts/DocsLayout.astro`) and the `NextSteps` component.

**Step 1: Create the page**

Create `site/src/pages/docs/integrations.astro`:

```astro
---
import DocsLayout from '../../layouts/DocsLayout.astro';
import NextSteps from '../../components/NextSteps.astro';
const base = import.meta.env.BASE_URL.replace(/\/$/, '');
---

<DocsLayout title="Integrations">
    <h1>Framework Integrations</h1>
    <p class="lead text-xl text-slate-400">
        Drop NocturnusAI into your agent stack in one line. Native integrations for the most popular AI frameworks.
    </p>

    <h3>Installation</h3>
    <pre><code class="language-bash">pip install nocturnusai                    # core SDK
pip install nocturnusai[langchain]         # + LangChain tools
pip install nocturnusai[crewai]            # + CrewAI BaseTool subclasses
pip install nocturnusai[autogen]           # + AutoGen tool functions + Memory
pip install nocturnusai[langgraph]         # + LangGraph checkpoint saver
pip install nocturnusai[openai-agents]     # + OpenAI Agents SDK tools
pip install nocturnusai[all]               # everything</code></pre>

    <hr />

    <!-- LangChain -->
    <div class="integration-card" id="langchain">
        <h2>LangChain</h2>
        <p class="text-slate-400">Four pre-built tools that plug directly into any LangChain agent. Assert facts, query, infer, and get salience-ranked context.</p>
        <pre><code class="language-python">from nocturnusai import SyncNocturnusAIClient
from nocturnusai.langchain import get_nocturnusai_tools

client = SyncNocturnusAIClient("http://localhost:9300")
tools = get_nocturnusai_tools(client)
# Pass tools to any LangChain agent</code></pre>
        <p><a href={`${base}/docs/sdks#langchain`}>Full LangChain docs &rarr;</a></p>
    </div>

    <hr />

    <!-- CrewAI -->
    <div class="integration-card" id="crewai">
        <h2>CrewAI</h2>
        <p class="text-slate-400">Five <code>BaseTool</code> subclasses with Pydantic input schemas, plus a <code>Storage</code> backend for crew-level knowledge persistence.</p>
        <pre><code class="language-python">from nocturnusai import SyncNocturnusAIClient
from nocturnusai.crewai import get_nocturnusai_tools, NocturnusAIStorage

client = SyncNocturnusAIClient("http://localhost:9300")
tools = get_nocturnusai_tools(client)
storage = NocturnusAIStorage(client=client)</code></pre>
        <p><a href={`${base}/docs/sdks#crewai`}>Full CrewAI docs &rarr;</a></p>
    </div>

    <hr />

    <!-- AutoGen -->
    <div class="integration-card" id="autogen">
        <h2>AutoGen</h2>
        <p class="text-slate-400">Five plain Python tool functions and an async <code>Memory</code> protocol implementation. Works with or without <code>autogen-agentchat</code>.</p>
        <pre><code class="language-python">from nocturnusai import SyncNocturnusAIClient
from nocturnusai.autogen import get_nocturnusai_tools, NocturnusAIMemory

client = SyncNocturnusAIClient("http://localhost:9300")
tools = get_nocturnusai_tools(client)
memory = NocturnusAIMemory(client=client)</code></pre>
        <p><a href={`${base}/docs/sdks#autogen`}>Full AutoGen docs &rarr;</a></p>
    </div>

    <hr />

    <!-- LangGraph -->
    <div class="integration-card" id="langgraph">
        <h2>LangGraph</h2>
        <p class="text-slate-400">Checkpoint saver that persists graph state as NocturnusAI facts. Maps threads to scopes for isolation.</p>
        <pre><code class="language-python">from nocturnusai import SyncNocturnusAIClient
from nocturnusai.langgraph import NocturnusAICheckpointSaver

client = SyncNocturnusAIClient("http://localhost:9300")
saver = NocturnusAICheckpointSaver(client=client)
app = graph.compile(checkpointer=saver)</code></pre>
        <p><a href={`${base}/docs/sdks#langgraph`}>Full LangGraph docs &rarr;</a></p>
    </div>

    <hr />

    <!-- OpenAI Agents SDK -->
    <div class="integration-card" id="openai-agents">
        <h2>OpenAI Agents SDK</h2>
        <p class="text-slate-400">Five tool functions, auto-decorated with <code>@function_tool</code> when the package is installed. Falls back to plain functions without it.</p>
        <pre><code class="language-python">from nocturnusai import SyncNocturnusAIClient
from nocturnusai.openai_agents import get_nocturnusai_tools

client = SyncNocturnusAIClient("http://localhost:9300")
tools = get_nocturnusai_tools(client)
# Agent(name="reasoner", tools=tools)</code></pre>
        <p><a href={`${base}/docs/sdks#openai-agents`}>Full OpenAI Agents docs &rarr;</a></p>
    </div>

    <hr />

    <!-- Anthropic SDK -->
    <div class="integration-card" id="anthropic">
        <h2>Anthropic SDK</h2>
        <p class="text-slate-400">JSON schema tool definitions and a dispatcher for the Anthropic Messages API. Zero framework dependencies.</p>
        <pre><code class="language-python">from nocturnusai import SyncNocturnusAIClient
from nocturnusai.anthropic_tools import get_nocturnusai_tool_definitions, handle_tool_call

client = SyncNocturnusAIClient("http://localhost:9300")
tools = get_nocturnusai_tool_definitions()
# response = anthropic.messages.create(tools=tools, ...)</code></pre>
        <p><a href={`${base}/docs/sdks#anthropic`}>Full Anthropic docs &rarr;</a></p>
    </div>

    <hr />

    <h2>Any Framework</h2>
    <p class="text-slate-400">
        Don't see your framework? NocturnusAI works with any Python or TypeScript agent via
        <a href={`${base}/docs/api`}>HTTP API</a>,
        <a href={`${base}/docs/mcp`}>MCP protocol</a>, or the
        <a href={`${base}/docs/sdks`}>direct SDK</a>.
    </p>

    <NextSteps links={[
        { title: "SDKs", description: "Full Python and TypeScript SDK documentation", href: `${base}/docs/sdks` },
        { title: "MCP Integration", description: "Connect via Model Context Protocol in 30 seconds", href: `${base}/docs/mcp` },
        { title: "Quickstart", description: "Install and run your first query in 5 minutes", href: `${base}/docs` },
    ]} />
</DocsLayout>
```

**Step 2: Verify the site builds**

Run: `cd site && npm run build`
Expected: `BUILD SUCCESSFUL`, `13 page(s) built` (was 12, now 13)

**Step 3: Commit**

```bash
git add site/src/pages/docs/integrations.astro
git commit -m "feat(site): add Integrations hub page showcasing 6 framework integrations"
```

---

### Task 3: Create the FAQ Page

**Files:**
- Create: `site/src/pages/docs/faq.astro`

**Context:** Answers the top developer questions that block adoption. Uses `DocsLayout` and `NextSteps`. Each question is an `<h3>` with a short answer and "Learn more" links.

**Step 1: Create the page**

Create `site/src/pages/docs/faq.astro`:

```astro
---
import DocsLayout from '../../layouts/DocsLayout.astro';
import NextSteps from '../../components/NextSteps.astro';
const base = import.meta.env.BASE_URL.replace(/\/$/, '');
---

<DocsLayout title="FAQ">
    <h1>Frequently Asked Questions</h1>
    <p class="lead text-xl text-slate-400">
        Common questions from developers evaluating NocturnusAI.
    </p>

    <hr />

    <h3 id="vs-rag">How is NocturnusAI different from RAG / vector search?</h3>
    <p>
        RAG retrieves text that <em>looks</em> related based on embedding similarity. NocturnusAI <em>derives</em> what must be true using logical inference. They're complementary: use RAG for unstructured retrieval, NocturnusAI for verified reasoning over structured facts. NocturnusAI can prove <em>why</em> an answer is correct and trace the inference path.
    </p>
    <p><a href={`${base}/docs/concepts#inference`}>Learn about inference &rarr;</a></p>

    <hr />

    <h3 id="logic-programming">Do I need to learn Prolog or logic programming?</h3>
    <p>
        No. The simplified API (<code>/tell</code>, <code>/ask</code>, <code>/teach</code>, <code>/forget</code>) and natural language extraction handle most use cases. You feed NocturnusAI plain text and ask questions in natural language — the logic runs under the hood. Power users can use the DSL for advanced rules, but it's entirely optional.
    </p>
    <p><a href={`${base}/docs#natural-language`}>See natural language mode &rarr;</a></p>

    <hr />

    <h3 id="contradictions">What happens if my agent asserts contradictory facts?</h3>
    <p>
        Configurable conflict resolution handles this. The default is <code>REJECT</code> (error on contradiction), but you can also use <code>NEWEST_WINS</code>, <code>CONFIDENCE</code> (highest confidence score wins), or <code>KEEP_BOTH</code>. The Truth Maintenance System automatically retracts derived facts when their premises are removed.
    </p>
    <p><a href={`${base}/docs/concepts#truth-maintenance`}>Learn about truth maintenance &rarr;</a></p>

    <hr />

    <h3 id="production">Can I run this in production?</h3>
    <p>
        Yes. NocturnusAI is built for production agent workloads: in-memory store with sub-100ms retrieval, ACID transactions, Write-Ahead Log for crash recovery, periodic snapshots, multi-tenant isolation, Prometheus metrics, and health check endpoints. It runs as a single Docker container or native binary.
    </p>
    <p><a href={`${base}/docs/operations`}>See operations guide &rarr;</a></p>

    <hr />

    <h3 id="license">Is it open source? What's the license?</h3>
    <p>
        NocturnusAI uses the <strong>Business Source License 1.1</strong>. It's free for non-production use, evaluation, and development. Contact <a href="https://github.com/Auctalis/nocturnusai">Auctalis</a> for production licensing.
    </p>

    <hr />

    <h3 id="models">Can I use this with OpenAI / Anthropic / local models?</h3>
    <p>
        Yes — NocturnusAI is model-agnostic. Use it with any LLM via the <a href={`${base}/docs/integrations`}>framework integrations</a> (LangChain, CrewAI, AutoGen, etc.), <a href={`${base}/docs/mcp`}>MCP protocol</a>, or <a href={`${base}/docs/api`}>direct HTTP API</a>. Optional LLM integration for natural language extraction supports OpenAI, Anthropic, Google, and Ollama.
    </p>
    <p><a href={`${base}/docs/llm`}>See LLM integration &rarr;</a></p>

    <hr />

    <h3 id="get-started">How do I get started?</h3>
    <p>
        One command to install, five minutes to your first query:
    </p>
    <pre><code class="language-bash">curl -fsSL https://raw.githubusercontent.com/Auctalis/nocturnusai/main/install.sh | bash</code></pre>
    <p><a href={`${base}/docs`}>Full quickstart guide &rarr;</a></p>

    <NextSteps links={[
        { title: "Quickstart", description: "Install and run your first query in 5 minutes", href: `${base}/docs` },
        { title: "Integrations", description: "Connect to LangChain, CrewAI, AutoGen, and more", href: `${base}/docs/integrations` },
        { title: "Core Concepts", description: "Understand facts, rules, inference, and salience", href: `${base}/docs/concepts` },
    ]} />
</DocsLayout>
```

**Step 2: Verify the site builds**

Run: `cd site && npm run build`
Expected: `BUILD SUCCESSFUL`, `14 page(s) built` (was 13, now 14)

**Step 3: Commit**

```bash
git add site/src/pages/docs/faq.astro
git commit -m "feat(site): add FAQ page answering top developer adoption questions"
```

---

### Task 4: Reorganize Sidebar Navigation

**Files:**
- Modify: `site/src/layouts/DocsLayout.astro:13-95` (the `navItems` array)

**Context:** The sidebar currently has 10 flat sections. Reorganize into 4 logical groups: Getting Started, SDKs & Protocols, Reference, Operations. Add links to the new Integrations and FAQ pages.

**Step 1: Replace the `navItems` array**

In `site/src/layouts/DocsLayout.astro`, replace lines 13-95 (the entire `const navItems = [...]` block) with:

```javascript
const navItems = [
    { title: "Getting Started", links: [
        { text: "Quickstart", href: `${base}/docs` },
        { text: "Integrations", href: `${base}/docs/integrations` },
        { text: "FAQ", href: `${base}/docs/faq` },
    ]},
    { title: "SDKs & Protocols", links: [
        { text: "Python SDK", href: `${base}/docs/sdks` },
        { text: "TypeScript SDK", href: `${base}/docs/sdks#typescript` },
        { text: "MCP Integration", href: `${base}/docs/mcp` },
        { text: "CLI Reference", href: `${base}/docs/cli` },
    ]},
    { title: "Reference", links: [
        { text: "API Reference", href: `${base}/docs/api` },
        { text: "Core Concepts", href: `${base}/docs/concepts` },
        { text: "LLM Integration", href: `${base}/docs/llm` },
    ]},
    { title: "Operations", links: [
        { text: "Security & Auth", href: `${base}/docs/security` },
        { text: "Multi-Tenancy", href: `${base}/docs/multi-tenancy` },
        { text: "Operations", href: `${base}/docs/operations` },
    ]},
];
```

**Step 2: Verify the site builds**

Run: `cd site && npm run build`
Expected: `BUILD SUCCESSFUL`, `14 page(s) built`

**Step 3: Verify sidebar renders correctly**

Run: `cd site && npm run dev` and check `http://localhost:4321/docs` in a browser. Confirm:
- 4 sidebar sections appear: "Getting Started", "SDKs & Protocols", "Reference", "Operations"
- "Integrations" and "FAQ" links appear under "Getting Started"
- All links navigate to correct pages

**Step 4: Commit**

```bash
git add site/src/layouts/DocsLayout.astro
git commit -m "feat(site): reorganize sidebar navigation into 4 logical groups"
```

---

### Task 5: Add NextSteps Cards to All Existing Doc Pages

**Files:**
- Modify: `site/src/pages/docs/index.astro` (Quickstart — update existing "What's Next?")
- Modify: `site/src/pages/docs/sdks.astro`
- Modify: `site/src/pages/docs/mcp.astro`
- Modify: `site/src/pages/docs/concepts.astro`
- Modify: `site/src/pages/docs/api.astro`
- Modify: `site/src/pages/docs/cli.astro`
- Modify: `site/src/pages/docs/security.astro`
- Modify: `site/src/pages/docs/multi-tenancy.astro`
- Modify: `site/src/pages/docs/llm.astro`
- Modify: `site/src/pages/docs/operations.astro`

**Context:** Each doc page needs a `NextSteps` component added before the closing `</DocsLayout>` tag. The Quickstart page already has a "What's Next?" section — replace it with the `NextSteps` component. For all other pages, add the import and the component.

**Step 1: Update the Quickstart page**

In `site/src/pages/docs/index.astro`:
- Add import: `import NextSteps from '../../components/NextSteps.astro';` after the DocsLayout import (line 2)
- Replace the existing "What's Next?" section (lines 423-460) with:

```astro
    <NextSteps links={[
        { title: "Integrations", description: "Connect to LangChain, CrewAI, AutoGen, and more", href: `${base}/docs/integrations` },
        { title: "Core Concepts", description: "Facts, rules, inference, scopes, and salience scoring", href: `${base}/docs/concepts` },
        { title: "API Reference", description: "Every endpoint with full request/response shapes", href: `${base}/docs/api` },
        { title: "FAQ", description: "Common questions about NocturnusAI", href: `${base}/docs/faq` },
    ]} />
```

**Step 2: Add NextSteps to each remaining doc page**

For each page below, add the `NextSteps` import and component. The pattern is the same:

1. Add `import NextSteps from '../../components/NextSteps.astro';` in the frontmatter `---` block
2. Add the `<NextSteps links={[...]} />` component just before `</DocsLayout>`

**SDKs page** (`sdks.astro`): Also add `const base = import.meta.env.BASE_URL.replace(/\/$/, '');` to frontmatter (it doesn't have it).
```astro
    <NextSteps links={[
        { title: "Integrations", description: "Quick-start cards for all 6 framework integrations", href: `${base}/docs/integrations` },
        { title: "API Reference", description: "Every endpoint with full request/response shapes", href: `${base}/docs/api` },
        { title: "MCP Integration", description: "Connect via Model Context Protocol in 30 seconds", href: `${base}/docs/mcp` },
    ]} />
```

**MCP Integration** (`mcp.astro`):
```astro
    <NextSteps links={[
        { title: "Integrations", description: "LangChain, CrewAI, AutoGen, and more framework integrations", href: `${base}/docs/integrations` },
        { title: "API Reference", description: "Full HTTP endpoint documentation", href: `${base}/docs/api` },
        { title: "CLI Reference", description: "Interactive REPL and command reference", href: `${base}/docs/cli` },
    ]} />
```

**Core Concepts** (`concepts.astro`):
```astro
    <NextSteps links={[
        { title: "API Reference", description: "Every endpoint with full request/response shapes", href: `${base}/docs/api` },
        { title: "CLI Reference", description: "Interactive REPL for exploring facts and rules", href: `${base}/docs/cli` },
        { title: "Integrations", description: "Connect NocturnusAI to your agent framework", href: `${base}/docs/integrations` },
    ]} />
```

**API Reference** (`api.astro`):
```astro
    <NextSteps links={[
        { title: "SDKs", description: "Python and TypeScript client libraries", href: `${base}/docs/sdks` },
        { title: "MCP Integration", description: "JSON-RPC 2.0 protocol for agent tools", href: `${base}/docs/mcp` },
        { title: "Core Concepts", description: "Understand the logic model behind the API", href: `${base}/docs/concepts` },
    ]} />
```

**CLI Reference** (`cli.astro`):
```astro
    <NextSteps links={[
        { title: "API Reference", description: "Full HTTP endpoint documentation", href: `${base}/docs/api` },
        { title: "Core Concepts", description: "Facts, rules, inference, and salience scoring", href: `${base}/docs/concepts` },
        { title: "Operations", description: "Monitoring, persistence, and deployment", href: `${base}/docs/operations` },
    ]} />
```

**Security & Auth** (`security.astro`):
```astro
    <NextSteps links={[
        { title: "Multi-Tenancy", description: "Isolate data across organizations and environments", href: `${base}/docs/multi-tenancy` },
        { title: "Operations", description: "Monitoring, logging, and persistence", href: `${base}/docs/operations` },
        { title: "API Reference", description: "Full endpoint documentation including auth headers", href: `${base}/docs/api` },
    ]} />
```

**Multi-Tenancy** (`multi-tenancy.astro`):
```astro
    <NextSteps links={[
        { title: "Security & Auth", description: "API keys, roles, and encryption", href: `${base}/docs/security` },
        { title: "Operations", description: "Monitoring, persistence, and deployment", href: `${base}/docs/operations` },
        { title: "API Reference", description: "Headers and endpoint documentation", href: `${base}/docs/api` },
    ]} />
```

**LLM Integration** (`llm.astro`):
```astro
    <NextSteps links={[
        { title: "Quickstart", description: "Use natural language mode with LLM extraction", href: `${base}/docs` },
        { title: "API Reference", description: "Extract and synthesize endpoint details", href: `${base}/docs/api` },
        { title: "Integrations", description: "Connect to LangChain, CrewAI, and more", href: `${base}/docs/integrations` },
    ]} />
```

**Operations** (`operations.astro`):
```astro
    <NextSteps links={[
        { title: "Security & Auth", description: "API keys, roles, and encryption", href: `${base}/docs/security` },
        { title: "Multi-Tenancy", description: "Tenant isolation and scoped data", href: `${base}/docs/multi-tenancy` },
        { title: "API Reference", description: "Health check and metrics endpoints", href: `${base}/docs/api` },
    ]} />
```

**Step 3: Verify the site builds**

Run: `cd site && npm run build`
Expected: `BUILD SUCCESSFUL`, `14 page(s) built`

**Step 4: Commit**

```bash
git add site/src/pages/docs/
git commit -m "feat(site): add NextSteps cross-linking cards to all 10 existing doc pages"
```

---

### Task 6: Link Hero "Works With" Badges to Integrations Page

**Files:**
- Modify: `site/src/components/Hero.astro:132-138`

**Context:** The Hero component has "Works with" badges (LangChain, CrewAI, Claude, Cursor, Windsurf, AutoGen, Any MCP client) that are currently plain `<span>` elements. Change them to `<a>` tags linking to the Integrations page. Keep framework-specific ones linking to their anchors; generic ones (Claude, Cursor, Windsurf, Any MCP client) link to the Integrations hub.

**Step 1: Update the badges**

In `site/src/components/Hero.astro`, replace lines 132-138:

```astro
<div class="mt-8 flex flex-wrap items-center justify-center gap-3 animate-fade-in-up delay-400">
  <span class="text-xs text-slate-600 uppercase tracking-wider font-medium">Works with</span>
  {["LangChain", "CrewAI", "Claude", "Cursor", "Windsurf", "AutoGen", "Any MCP client"].map(f => (
    <span class="px-3 py-1 rounded-full bg-white/5 border border-white/10 text-xs font-medium text-slate-400">{f}</span>
  ))}
</div>
```

With:

```astro
<div class="mt-8 flex flex-wrap items-center justify-center gap-3 animate-fade-in-up delay-400">
  <span class="text-xs text-slate-600 uppercase tracking-wider font-medium">Works with</span>
  {[
    { name: "LangChain", href: `${base}/docs/integrations#langchain` },
    { name: "CrewAI", href: `${base}/docs/integrations#crewai` },
    { name: "AutoGen", href: `${base}/docs/integrations#autogen` },
    { name: "OpenAI Agents", href: `${base}/docs/integrations#openai-agents` },
    { name: "Anthropic", href: `${base}/docs/integrations#anthropic` },
    { name: "Claude", href: `${base}/docs/mcp` },
    { name: "Cursor", href: `${base}/docs/mcp` },
    { name: "Any MCP client", href: `${base}/docs/mcp` },
  ].map(f => (
    <a href={f.href} class="px-3 py-1 rounded-full bg-white/5 border border-white/10 text-xs font-medium text-slate-400 hover:text-cyan-400 hover:border-cyan-400/30 transition-colors">{f.name}</a>
  ))}
</div>
```

Note: Added `hover:text-cyan-400 hover:border-cyan-400/30 transition-colors` for hover feedback. Removed "Windsurf" (not a framework integration — no corresponding page). Added "OpenAI Agents" and "Anthropic" since they now have integrations.

**Step 2: Verify the site builds**

Run: `cd site && npm run build`
Expected: `BUILD SUCCESSFUL`, `14 page(s) built`

**Step 3: Commit**

```bash
git add site/src/components/Hero.astro
git commit -m "feat(site): link Hero 'Works with' badges to Integrations and MCP pages"
```

---

### Task 7: Final Validation and Push

**Files:** None (validation only)

**Step 1: Full site build**

Run: `cd site && npm run build`
Expected: `BUILD SUCCESSFUL`, `14 page(s) built`

**Step 2: Verify all new pages exist in build output**

Run: `ls site/dist/docs/integrations/ site/dist/docs/faq/`
Expected: Both contain `index.html`

**Step 3: Push all commits**

```bash
git push
```

**Step 4: Verify docs workflow triggers**

Run: `gh run list --limit 3`
Expected: "Deploy Docs" workflow shows as queued or in_progress
