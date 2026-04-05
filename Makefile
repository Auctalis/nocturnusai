# ─────────────────────────────────────────────────────────────────────────────
# NocturnusAI — Logic Server for Agentic AI
# ─────────────────────────────────────────────────────────────────────────────

.PHONY: help setup up up-ollama docker-run up-monitoring down restart logs health \
        status smoke build test cli clean env-check wait-for-health

ENV_FILE ?= $(if $(wildcard .env),.env,.env.example)
COMPOSE = docker compose --env-file $(ENV_FILE)

# ── Default ──────────────────────────────────────────────────────────────────
help: ## Show this help
	@grep -E '^[a-zA-Z_-]+:.*?## .*$$' $(MAKEFILE_LIST) | \
		awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[36m%-16s\033[0m %s\n", $$1, $$2}'

# ── First-time setup ────────────────────────────────────────────────────────
setup: ## First-time setup: use defaults, create data dir
	@mkdir -p data
	@echo "\033[32mReady.\033[0m"
	@echo "  Defaults:   .env.example"
	@echo "  Override:   create .env only if you want local changes"
	@echo "  First run:  make up-ollama"

# ── Docker Compose ───────────────────────────────────────────────────────────
up: env-check ## Start server using .env overrides or .env.example defaults
	$(COMPOSE) up -d
	@$(MAKE) wait-for-health
	@echo ""
	@echo "\033[32mNocturnusAI running at http://localhost:$${PORT:-9300}\033[0m"
	@echo "  Health:  curl http://localhost:$${PORT:-9300}/health"
	@echo "  Docs:    curl http://localhost:$${PORT:-9300}/llm.txt"
	@echo "  CLI:     make cli"
	@echo "  Verify:  make smoke"

up-ollama: env-check ## Start server with Ollama (reuse host if present, else bundled)
	@MODEL="$${OLLAMA_MODEL:-granite3.3:8b}"; \
	if curl -sf http://localhost:11434/api/tags >/dev/null 2>&1; then \
		echo "\033[36mUsing existing Ollama on localhost:11434 (\033[1m$$MODEL\033[0m\033[36m).\033[0m"; \
		if command -v ollama >/dev/null 2>&1; then \
			echo "Ensuring model $$MODEL is available on the host..."; \
			ollama pull "$$MODEL"; \
		else \
			echo "\033[33mNo 'ollama' CLI found; assuming model $$MODEL is already available on the host.\033[0m"; \
		fi; \
		echo "Starting NocturnusAI..."; \
		LLM_PROVIDER=ollama LLM_MODEL="$$MODEL" LLM_BASE_URL=http://host.docker.internal:11434/v1 EXTRACTION_ENABLED=true $(COMPOSE) up -d nocturnusai; \
	else \
		echo "\033[36mStarting bundled Ollama (\033[1m$$MODEL\033[0m\033[36m)...\033[0m"; \
		LLM_PROVIDER=ollama LLM_MODEL="$$MODEL" LLM_BASE_URL=http://ollama:11434/v1 EXTRACTION_ENABLED=true $(COMPOSE) --profile ollama up -d ollama; \
		printf "Waiting for Ollama"; \
		for i in $$(seq 1 45); do \
			if curl -sf http://localhost:11434/api/tags >/dev/null 2>&1; then echo ""; break; fi; \
			printf "."; sleep 2; \
		done; \
		if ! curl -sf http://localhost:11434/api/tags >/dev/null 2>&1; then \
			echo "\033[31mOllama did not become ready.\033[0m"; exit 1; \
		fi; \
		echo "Pulling model $$MODEL (reused after first download)..."; \
		$(COMPOSE) --profile ollama exec -T ollama ollama pull "$$MODEL"; \
		echo "Starting NocturnusAI..."; \
		LLM_PROVIDER=ollama LLM_MODEL="$$MODEL" LLM_BASE_URL=http://ollama:11434/v1 EXTRACTION_ENABLED=true $(COMPOSE) --profile ollama up -d nocturnusai; \
	fi; \
	$(MAKE) ENV_FILE=$(ENV_FILE) wait-for-health; \
	echo ""; \
	echo "\033[32mPersistent local stack is ready.\033[0m"; \
	echo "  Server:  http://localhost:$${PORT:-9300}"; \
	echo "  Ollama:  http://localhost:11434"; \
	echo "  Verify:  make smoke"

docker-run: ## Start with docker run (no compose — postgres style)
	docker run -d \
		--name nocturnusai \
		-p $${PORT:-9300}:$${PORT:-9300} \
		-v nocturnusai-data:/data \
		--add-host=host.docker.internal:host-gateway \
		-e LLM_PROVIDER=$${LLM_PROVIDER:-ollama} \
		-e LLM_MODEL=$${LLM_MODEL:-granite3.3:8b} \
		-e LLM_BASE_URL=$${LLM_BASE_URL:-http://host.docker.internal:11434/v1} \
		-e EXTRACTION_ENABLED=$${EXTRACTION_ENABLED:-true} \
		-e API_KEY=$${API_KEY:-} \
		$${IMAGE:-ghcr.io/auctalis/nocturnusai:latest}
	@echo ""
	@echo "\033[32mNocturnusAI running at http://localhost:$${PORT:-9300}\033[0m"
	@echo "  Override any setting with env vars: LLM_PROVIDER=anthropic ANTHROPIC_API_KEY=sk-... make docker-run"

up-monitoring: env-check ## Start server + Prometheus + Grafana
	$(COMPOSE) --profile monitoring up -d
	@$(MAKE) wait-for-health
	@echo ""
	@echo "\033[32mNocturnusAI + Monitoring running.\033[0m"
	@echo "  Server:     http://localhost:$${PORT:-9300}"
	@echo "  Grafana:    http://localhost:3000  (admin / $${GRAFANA_PASSWORD:-change-me-before-use})"
	@echo "  Prometheus: http://localhost:9090"

down: ## Stop everything
	$(COMPOSE) --profile monitoring --profile ollama down

restart: down up ## Restart server

upgrade: ## Pull latest image and restart
	@echo "\033[36mPulling latest NocturnusAI image...\033[0m"
	docker pull ghcr.io/auctalis/nocturnusai:latest
	@CURRENT=$$(curl -sf http://localhost:$${PORT:-9300}/health 2>/dev/null | python3 -c "import sys,json; print(json.load(sys.stdin).get('version','unknown'))" 2>/dev/null || echo "not running"); \
	echo "  Current version: $$CURRENT"
	$(COMPOSE) --profile monitoring --profile ollama down
	$(COMPOSE) up -d
	@$(MAKE) wait-for-health
	@NEW=$$(curl -sf http://localhost:$${PORT:-9300}/health | python3 -c "import sys,json; print(json.load(sys.stdin).get('version','unknown'))" 2>/dev/null); \
	echo "\033[32mUpgraded to $$NEW\033[0m"

logs: ## Tail server logs
	$(COMPOSE) --profile ollama logs -f nocturnusai

health: ## Check server health
	@curl -sf http://localhost:$${PORT:-9300}/health | python3 -m json.tool 2>/dev/null || \
		curl -sf http://localhost:$${PORT:-9300}/health || \
		echo "\033[31mServer not responding.\033[0m"

metrics: ## Show raw Prometheus metrics
	@curl -sf http://localhost:$${PORT:-9300}/metrics | grep nocturnusai_ || \
		echo "\033[31mServer not responding.\033[0m"

status: ## Show running containers
	$(COMPOSE) --profile monitoring --profile ollama ps

smoke: ## Verify /health, assert a fact, and confirm /context returns it
	@curl -sf http://localhost:$${PORT:-9300}/health >/dev/null || { \
		echo "\033[31mServer not responding on http://localhost:$${PORT:-9300}\033[0m"; exit 1; \
	}
	@echo "\033[36mHealth OK.\033[0m"
	@curl -sS -X POST http://localhost:$${PORT:-9300}/assert/fact \
		-H 'Content-Type: application/json' \
		-H 'X-Tenant-ID: default' \
		-d '{"predicate":"smoke_test","args":["passed"]}' >/dev/null; \
	RESP=$$(curl -sS -X POST http://localhost:$${PORT:-9300}/context \
		-H 'Content-Type: application/json' \
		-H 'X-Tenant-ID: default' \
		-d '{"turns":["smoke_test(passed)"],"maxFacts":5}'); \
	COMPACT=$$(printf "%s" "$$RESP" | tr -d '[:space:]'); \
	printf "%s" "$$COMPACT" | grep -Eq '"factsReturned":[1-9]' || { \
		echo "\033[31mSmoke test failed — /context returned no facts.\033[0m"; \
		echo "$$RESP"; \
		exit 1; \
	}; \
	curl -sS -X POST http://localhost:$${PORT:-9300}/retract \
		-H 'Content-Type: application/json' \
		-H 'X-Tenant-ID: default' \
		-d '{"predicate":"smoke_test","args":["passed"]}' >/dev/null 2>&1 || true; \
	echo "\033[32mSmoke test passed.\033[0m"; \
	echo "$$RESP"

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
	$(COMPOSE) --profile monitoring --profile ollama down -v
	rm -rf data

# ── Utilities ────────────────────────────────────────────────────────────────
env-check:
	@if [ ! -f "$(ENV_FILE)" ]; then \
		echo "\033[31mMissing config file: $(ENV_FILE)\033[0m"; \
		exit 1; \
	fi
	@echo "\033[36mUsing config: $(ENV_FILE)\033[0m"
	@if [ "$(ENV_FILE)" = ".env.example" ]; then \
		echo "  Create .env only if you want persistent local overrides."; \
	fi

wait-for-health:
	@printf "Waiting for server"; \
	for i in $$(seq 1 45); do \
		if curl -sf http://localhost:$${PORT:-9300}/health >/dev/null 2>&1; then \
			echo ""; \
			exit 0; \
		fi; \
		printf "."; \
		sleep 2; \
	done; \
	echo ""; \
	echo "\033[31mServer did not become ready on http://localhost:$${PORT:-9300}\033[0m"; \
	exit 1
