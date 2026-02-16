# ─────────────────────────────────────────────────────────────────────────────
# AxiomBase — Logic Server for Agentic AI
# ─────────────────────────────────────────────────────────────────────────────

.PHONY: help setup up up-ollama down restart logs health status \
        build test cli clean env-check

# ── Default ──────────────────────────────────────────────────────────────────
help: ## Show this help
	@grep -E '^[a-zA-Z_-]+:.*?## .*$$' $(MAKEFILE_LIST) | \
		awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[36m%-16s\033[0m %s\n", $$1, $$2}'

# ── First-time setup ────────────────────────────────────────────────────────
setup: ## First-time setup: copy .env, create data dir
	@if [ ! -f .env ]; then \
		cp .env.example .env; \
		echo "\033[32m.env created from .env.example — edit it with your API keys.\033[0m"; \
	else \
		echo ".env already exists, skipping."; \
	fi
	@mkdir -p data
	@echo "\033[32mReady. Run 'make up' or 'make up-ollama'.\033[0m"

# ── Docker Compose ───────────────────────────────────────────────────────────
up: env-check ## Start server (cloud LLM — needs API key in .env)
	docker compose up -d --build
	@echo ""
	@echo "\033[32mAxiomBase running at http://localhost:$${PORT:-9300}\033[0m"
	@echo "  Health:  curl http://localhost:$${PORT:-9300}/health"
	@echo "  Docs:    curl http://localhost:$${PORT:-9300}/llm.txt"
	@echo "  CLI:     make cli"

up-ollama: env-check ## Start server + local Ollama (no API key needed)
	docker compose --profile ollama up -d --build
	@echo ""
	@echo "\033[32mAxiomBase + Ollama running.\033[0m"
	@echo "  Server:  http://localhost:$${PORT:-9300}"
	@echo "  Ollama:  http://localhost:11434"
	@echo "  Model pull may take a few minutes on first start."
	@echo "  CLI:     make cli"

down: ## Stop everything
	docker compose --profile ollama down

restart: down up ## Restart server

logs: ## Tail server logs
	docker compose logs -f axiombase

logs-ollama: ## Tail Ollama logs
	docker compose logs -f ollama

health: ## Check server health
	@curl -sf http://localhost:$${PORT:-9300}/health | python3 -m json.tool 2>/dev/null || \
		curl -sf http://localhost:$${PORT:-9300}/health || \
		echo "\033[31mServer not responding.\033[0m"

status: ## Show running containers
	docker compose --profile ollama ps

# ── Local development (no Docker) ───────────────────────────────────────────
build: ## Build all modules with Gradle
	./gradlew build

test: ## Run all tests
	./gradlew test

dev: ## Run server locally (Gradle, port 9300)
	./run_local_dev.sh

cli: ## Connect CLI to running server
	@echo "Connecting to http://localhost:$${PORT:-9300}..."
	./gradlew :axiombase-cli:run --args='--server http://localhost:$${PORT:-9300} --db default' --console=plain

# ── Maintenance ──────────────────────────────────────────────────────────────
clean: ## Remove build artifacts and data
	./gradlew clean
	@echo "Note: Docker volumes not removed. Run 'make clean-all' for full cleanup."

clean-all: ## Remove everything (build, Docker volumes, data)
	./gradlew clean
	docker compose --profile ollama down -v
	rm -rf data

# ── Utilities ────────────────────────────────────────────────────────────────
env-check:
	@if [ ! -f .env ]; then \
		echo "\033[33mNo .env file found. Creating from .env.example...\033[0m"; \
		cp .env.example .env; \
		echo "\033[32m.env created. Edit it to configure your LLM provider.\033[0m"; \
		echo "  Default: Ollama (local, no API key needed)"; \
		echo "  Run 'make up-ollama' to start with local LLM."; \
		echo ""; \
	fi
