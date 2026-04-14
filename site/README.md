# NocturnusAI Docs Site

This directory contains the public documentation site for NocturnusAI.

The site is built with Astro and deployed to GitHub Pages at [https://nocturnus.ai/](https://nocturnus.ai/).

## What This Site Is For

The docs now lead with the actual integration problem:

- you have a large array of turns
- you need a smaller context window for the next model call
- you may need a goal-driven pass, an incremental diff, or a session reset

Backend mechanics like predicates, rules, scopes, and inference still exist, but they belong behind that workflow instead of in front of it.

## Local Development

```bash
cd site
npm install
npm run dev
```

Local preview: `http://localhost:4321`

## Build

```bash
cd site
npm run build
```

The production output is written to `site/dist/`.

## Structure

```text
site/
├── public/                  Static assets
├── src/components/          Reusable UI sections
├── src/layouts/             Shared layouts and docs navigation
├── src/pages/               Route files for the marketing site and docs
└── package.json             Astro scripts and dependencies
```

Important docs files:

- `src/layouts/DocsLayout.astro` — shared docs shell and sidebar
- `src/pages/docs/index.astro` — docs landing page
- `src/pages/docs/context.astro` — context optimization workflow
- `src/pages/docs/api.astro` — REST reference
- `src/pages/docs/sdks.astro` — Python and TypeScript SDK reference
- `src/pages/docs/mcp.astro` — MCP reference
- `src/pages/docs/cli.astro` — CLI reference

## Deployment

GitHub Pages deploys from the workflow at `.github/workflows/docs.yml`.

Pushes to `main` that change `site/**` trigger a rebuild and publish.
