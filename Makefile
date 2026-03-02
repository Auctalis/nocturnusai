# ─────────────────────────────────────────────────────────────────────────────
# NocturnusAI — Logic Server for Agentic AI
# ─────────────────────────────────────────────────────────────────────────────

.PHONY: help setup up docker-run up-monitoring down restart logs health status \
        build test cli clean env-check

# ── Default ──────────────────────────────────────────────────────────────────
help: ## Show this help
	@grep -E '^[a-zA-Z_-]+:.*?## .*$$' $(MAKEFILE_LIST) | \
		awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[36m%-16s\033[0m %s\n", $$1, $$2}'

# ── First-time setup ────────────────────────────────────────────────────────
setup: ## First-time setup: copy .env, create data dir
	@if [ ! -f .env ]; then \
		cp .env.example .env; \
		echo "\033[32m.env created. Default: Ollama at host.docker.internal:11434\033[0m"; \
	else \
		echo ".env already exists, skipping."; \
	fi
	@mkdir -p data
	@echo "\033[32mReady. Run 'make up'.\033[0m"

# ── Docker Compose ───────────────────────────────────────────────────────────
up: env-check ## Start server (cloud LLM — needs API key in .env)
	docker compose up -d --build
	@echo ""
	@echo "\033[32mNocturnusAI running at http://localhost:$${PORT:-9300}\033[0m"
	@echo "  Health:  curl http://localhost:$${PORT:-9300}/health"
	@echo "  Docs:    curl http://localhost:$${PORT:-9300}/llm.txt"
	@echo "  CLI:     make cli"

docker-run: ## Start with docker run (no compose — postgres style)
	docker run -d \
		--name nocturnusai \
		-p $${PORT:-9300}:$${PORT:-9300} \
		-v nocturnusai-data:/data \
		--add-host=host.docker.internal:host-gateway \
		-e LLM_PROVIDER=$${LLM_PROVIDER:-ollama} \
		-e LLM_MODEL=$${LLM_MODEL:-llama3.2} \
		-e LLM_BASE_URL=$${LLM_BASE_URL:-http://host.docker.internal:11434/v1} \
		-e EXTRACTION_ENABLED=$${EXTRACTION_ENABLED:-true} \
		-e API_KEY=$${API_KEY:-} \
		$${IMAGE:-ghcr.io/auctalis/nocturnusai:latest}
	@echo ""
	@echo "\033[32mNocturnusAI running at http://localhost:$${PORT:-9300}\033[0m"
	@echo "  Override any setting with env vars: LLM_PROVIDER=anthropic ANTHROPIC_API_KEY=sk-... make docker-run"

up-monitoring: env-check ## Start server + Prometheus + Grafana
	docker compose --profile monitoring up -d --build
	@echo ""
	@echo "\033[32mNocturnusAI + Monitoring running.\033[0m"
	@echo "  Server:     http://localhost:$${PORT:-9300}"
	@echo "  Grafana:    http://localhost:3000  (admin / nocturnusai)"
	@echo "  Prometheus: http://localhost:9090"

down: ## Stop everything
	docker compose --profile monitoring down

restart: down up ## Restart server

logs: ## Tail server logs
	docker compose logs -f nocturnusai

health: ## Check server health
	@curl -sf http://localhost:$${PORT:-9300}/health | python3 -m json.tool 2>/dev/null || \
		curl -sf http://localhost:$${PORT:-9300}/health || \
		echo "\033[31mServer not responding.\033[0m"

metrics: ## Show raw Prometheus metrics
	@curl -sf http://localhost:$${PORT:-9300}/metrics | grep nocturnusai_ || \
		echo "\033[31mServer not responding.\033[0m"

status: ## Show running containers
	docker compose --profile monitoring ps

# ── Local development (no Docker) ───────────────────────────────────────────
build: ## Build all modules with Gradle
	./gradlew build

test: ## Run all tests
	./gradlew test

dev: ## Run server locally (Gradle, port 9300)
	./run_local_dev.sh

cli: ## Connect CLI to running server
	@echo "Connecting to http://localhost:$${PORT:-9300}..."
	./gradlew :nocturnusai-cli:run --args='--server http://localhost:$${PORT:-9300} --db default' --console=plain

# ── Maintenance ──────────────────────────────────────────────────────────────
clean: ## Remove build artifacts and data
	./gradlew clean
	@echo "Note: Docker volumes not removed. Run 'make clean-all' for full cleanup."

clean-all: ## Remove everything (build, Docker volumes, data)
	./gradlew clean
	docker compose --profile monitoring down -v
	rm -rf data

# ── Utilities ────────────────────────────────────────────────────────────────
env-check:
	@if [ ! -f .env ]; then \
		echo "\033[33mNo .env file found. Creating from .env.example...\033[0m"; \
		cp .env.example .env; \
		echo "\033[32m.env created. Default: Ollama at host.docker.internal:11434\033[0m"; \
		echo "  Make sure Ollama is running locally, then: make up"; \
		echo ""; \
	fi
