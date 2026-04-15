# Contributing a Benchmark

## What makes a good benchmark submission?

A good submission shows the token reduction (or lack thereof!) in a **specific, realistic scenario**
that someone else could independently reproduce.

## How to submit

1. **Fork** the repo and create a branch: `bench/your-scenario-name`

2. **Run** `02_live_benchmark.ipynb` (or write your own notebook)
   - Use a real conversation or generate one synthetically
   - Describe where the conversation comes from (anonymized is fine)

3. **Add your files** to `community/`:
   ```
   community/
     your-scenario-name/
       README.md          ← describe the scenario, your environment, findings
       benchmark.ipynb    ← your notebook (optional but encouraged)
       results.json       ← raw token counts in the standard format
       graphs/            ← exported PNG files
   ```

4. **Standard results.json format:**
   ```json
   {
     "timestamp": "2026-04-14T10:00:00",
     "author": "github-handle or 'anonymous'",
     "scenario": "brief description",
     "turns": 15,
     "model_naive": "claude-opus-4-6",
     "model_nocturnus": "claude-opus-4-6",
     "nocturnus_version": "0.3.10",
     "naive_tokens_per_turn": [960, 1240, 1580, ...],
     "noct_tokens_per_turn": [960, 990, 1020, ...],
     "notes": "any caveats or observations"
   }
   ```

5. **Open a PR** — title format: `bench: <scenario name>`

## What to include in your scenario README

- **Domain**: what the agent is doing (support, coding, research, etc.)
- **Conversation source**: synthetic / real (anonymized) / generated
- **Fact extraction strategy**: how you decided what to store as facts
- **NocturnusAI retrieval strategy**: top-k / goal-driven / salience-ranked
- **Result**: measured reduction ratio and what you found surprising

## What we don't require

- Perfect results — a scenario where NocturnusAI doesn't help is just as valuable
- Code quality — notebooks can be messy
- Novel scenarios — duplicating an existing scenario with a different model is useful

## Questions?

Open an issue in the main repo: https://github.com/Auctalis/nocturnusai/issues
