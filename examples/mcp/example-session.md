# Example MCP session — Claude Desktop with NocturnusAI

A 10-turn session resolving a customer support ticket. Shows the `context` tool returning a compressed working set each turn instead of re-reading the full transcript.

## Setup

- Claude Desktop 0.7+
- NocturnusAI running at `localhost:9300`
- `config.json` from this directory copied to `~/Library/Application Support/Claude/mcp_servers/nocturnus.json`

## Session

**Turn 1 — User:** I have a customer, Acme Corp, enterprise tier, $2M ARR. They're reporting login failures since this morning. Help me triage.

> _Claude calls `nocturnus:tell` with `customer(acme_corp)`, `tier(acme_corp, enterprise)`, `arr(acme_corp, 2000000)`, `issue(acme_corp, login_failure)`._
>
> **Tokens sent to model: 412**

**Turn 2 — User:** About 40 people affected. Quarterly reviews this week. Urgency high.

> _Claude calls `nocturnus:tell` with `affected_users(acme_corp, 40)`, `deadline(acme_corp, quarterly_reviews)`, `urgency(acme_corp, high)`._
>
> **Tokens sent to model: 428** (naive replay would be ~580)

**Turn 3 — User:** Auth audit shows 287 SAML_ASSERTION_INVALID errors since 07:52 UTC.

> _Claude calls `nocturnus:tell` with `auth_error(acme_corp, saml_invalid, 287)`, `first_error_time(acme_corp, "07:52_utc")`._
>
> **Tokens sent to model: 441**

**Turn 4 — User:** They migrated from Ping to Okta last night. Could that be related?

> _Claude calls `nocturnus:tell` with `migration(acme_corp, ping_to_okta, "last_night")`._
> _Claude calls `nocturnus:context` with `{"goals": [{"predicate": "root_cause", "args": ["acme_corp"]}]}`._
>
> **Tokens sent to model: 398** (naive replay would be ~720)
>
> Claude receives a briefing: "Acme Corp (enterprise, $2M ARR) has 40 users affected by SAML_ASSERTION_INVALID (287 errors since 07:52 UTC) after migrating from Ping to Okta last night. Quarterly reviews deadline."
>
> **Claude's response:** The timing strongly suggests the Okta migration caused a SAML entity-ID or certificate mismatch. I'd check the SP entity ID configuration against what Okta sends in the SAML response.

**Turn 5 — User:** Confirmed. SP has `exk1abc`, Okta sends `exk2def`. Fix?

> _Claude calls `nocturnus:tell` with `saml_mismatch(acme_corp, "exk1abc", "exk2def")`._
>
> **Tokens sent to model: 402**
>
> **Claude's response:** Update the SP entity ID from `exk1abc` to `exk2def`, or reconfigure Okta to use `exk1abc`. ~15 minutes to fix. Requires Okta admin.

**(Turns 6–10 continue the pattern.)**

## Why the numbers stay flat

On a naive MCP client, each turn sends the full prior conversation back to the model. By turn 10, the prompt would be ~2,500 tokens.

With NocturnusAI as the MCP server, Claude calls `nocturnus:context` when it needs working memory — and receives a short, goal-filtered briefing. The session stays in the 400-token-per-turn range regardless of how long the conversation runs.

## Run this yourself

```bash
# 1. Start Nocturnus
docker run -d -p 9300:9300 -e EXTRACTION_ENABLED=true \
  -e ANTHROPIC_API_KEY=sk-ant-... ghcr.io/auctalis/nocturnusai:latest

# 2. Install the config (macOS Claude Desktop)
cp config.json ~/Library/Application\ Support/Claude/mcp_servers/nocturnus.json

# 3. Restart Claude Desktop, then start a new chat.
```

Paste the turns above into Claude and watch the context tool work.
