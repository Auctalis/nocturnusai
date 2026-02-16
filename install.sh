#!/bin/bash
set -e

# ─────────────────────────────────────────────────────────────────────────────
# AxiomBase Installer
# Logic server for Agentic AI — deterministic reasoning & agent memory
#
# Usage:
#   curl -fsSL https://get.axiombase.dev/install.sh | bash
#   curl -fsSL https://get.axiombase.dev/install.sh | bash -s -- --ollama
#
# Options:
#   --ollama    Include local Ollama (no API key needed)
#   --dir DIR   Install directory (default: ./axiombase)
#   --port PORT Server port (default: 9300)
#   --key KEY   LLM API key (Anthropic/OpenAI/Google — auto-detected)
# ─────────────────────────────────────────────────────────────────────────────

VERSION="latest"
INSTALL_DIR="./axiombase"
PORT=9300
USE_OLLAMA=false
LLM_KEY=""

# Colors
GREEN='\033[0;32m'
YELLOW='\033[0;33m'
RED='\033[0;31m'
CYAN='\033[0;36m'
BOLD='\033[1m'
DIM='\033[2m'
NC='\033[0m'

# ── Parse args ───────────────────────────────────────────────────────────────
while [[ $# -gt 0 ]]; do
    case $1 in
        --ollama)  USE_OLLAMA=true; shift ;;
        --dir)     INSTALL_DIR="$2"; shift 2 ;;
        --port)    PORT="$2"; shift 2 ;;
        --key)     LLM_KEY="$2"; shift 2 ;;
        *)         echo -e "${RED}Unknown option: $1${NC}"; exit 1 ;;
    esac
done

# ── Banner ───────────────────────────────────────────────────────────────────
echo ""
echo -e "${CYAN}${BOLD}"
cat << 'BANNER'
     _          _                 ____
    / \   __  _(_) ___  _ __ ___ | __ )  __ _ ___  ___
   / _ \  \ \/ / |/ _ \| '_ ` _ \|  _ \ / _` / __|/ _ \
  / ___ \  >  <| | (_) | | | | | | |_) | (_| \__ \  __/
 /_/   \_\/_/\_\_|\___/|_| |_| |_|____/ \__,_|___/\___|
BANNER
echo -e "${NC}"
echo -e "${DIM}Logic server for Agentic AI${NC}"
echo ""

# ── Check prerequisites ─────────────────────────────────────────────────────
check_cmd() {
    if ! command -v "$1" &>/dev/null; then
        return 1
    fi
    return 0
}

HAS_DOCKER=false
HAS_COMPOSE=false
COMPOSE_CMD=""

if check_cmd docker; then
    HAS_DOCKER=true
    if docker compose version &>/dev/null 2>&1; then
        HAS_COMPOSE=true
        COMPOSE_CMD="docker compose"
    fi
fi

if ! $HAS_COMPOSE && check_cmd docker-compose; then
    HAS_COMPOSE=true
    COMPOSE_CMD="docker-compose"
fi

if ! $HAS_COMPOSE && check_cmd podman-compose; then
    HAS_COMPOSE=true
    COMPOSE_CMD="podman-compose"
fi

if ! $HAS_COMPOSE; then
    echo -e "${RED}Docker (with compose) is required.${NC}"
    echo ""
    echo "Install Docker:"
    echo "  macOS:   brew install --cask docker"
    echo "  Ubuntu:  curl -fsSL https://get.docker.com | sh"
    echo "  Windows: https://docs.docker.com/desktop/install/windows-install/"
    exit 1
fi

echo -e "${GREEN}Found:${NC} $COMPOSE_CMD"

# ── Create install directory ─────────────────────────────────────────────────
mkdir -p "$INSTALL_DIR"
cd "$INSTALL_DIR"
echo -e "${GREEN}Installing to:${NC} $(pwd)"

# ── Download compose file and .env ───────────────────────────────────────────
REPO_RAW="https://raw.githubusercontent.com/essaouirallc/logic-server/main"

echo -e "${DIM}Downloading configuration...${NC}"

# docker-compose.yml
curl -fsSL "$REPO_RAW/docker-compose.yml" -o docker-compose.yml

# .env.example → .env
curl -fsSL "$REPO_RAW/.env.example" -o .env.example
cp .env.example .env

# ── Configure .env ───────────────────────────────────────────────────────────
# Set port
sed -i.bak "s/^PORT=.*/PORT=$PORT/" .env && rm -f .env.bak

# Configure LLM provider
if [ -n "$LLM_KEY" ]; then
    # Auto-detect provider from key format
    if [[ "$LLM_KEY" == sk-ant-* ]]; then
        echo -e "${GREEN}Detected:${NC} Anthropic Claude"
        sed -i.bak "s/^LLM_PROVIDER=.*/# LLM_PROVIDER=ollama/" .env && rm -f .env.bak
        sed -i.bak "s/^LLM_MODEL=.*/# LLM_MODEL=llama3.2/" .env && rm -f .env.bak
        sed -i.bak "s/^LLM_BASE_URL=.*/# LLM_BASE_URL=http:\/\/ollama:11434\/v1/" .env && rm -f .env.bak
        sed -i.bak "s/^# ANTHROPIC_API_KEY=.*/ANTHROPIC_API_KEY=$LLM_KEY/" .env && rm -f .env.bak
    elif [[ "$LLM_KEY" == sk-* ]]; then
        echo -e "${GREEN}Detected:${NC} OpenAI"
        sed -i.bak "s/^LLM_PROVIDER=.*/# LLM_PROVIDER=ollama/" .env && rm -f .env.bak
        sed -i.bak "s/^LLM_MODEL=.*/# LLM_MODEL=llama3.2/" .env && rm -f .env.bak
        sed -i.bak "s/^LLM_BASE_URL=.*/# LLM_BASE_URL=http:\/\/ollama:11434\/v1/" .env && rm -f .env.bak
        sed -i.bak "s/^# OPENAI_API_KEY=.*/OPENAI_API_KEY=$LLM_KEY/" .env && rm -f .env.bak
    elif [[ "$LLM_KEY" == AIza* ]]; then
        echo -e "${GREEN}Detected:${NC} Google Gemini"
        sed -i.bak "s/^LLM_PROVIDER=.*/# LLM_PROVIDER=ollama/" .env && rm -f .env.bak
        sed -i.bak "s/^LLM_MODEL=.*/# LLM_MODEL=llama3.2/" .env && rm -f .env.bak
        sed -i.bak "s/^LLM_BASE_URL=.*/# LLM_BASE_URL=http:\/\/ollama:11434\/v1/" .env && rm -f .env.bak
        sed -i.bak "s/^# GOOGLE_API_KEY=.*/GOOGLE_API_KEY=$LLM_KEY/" .env && rm -f .env.bak
    else
        echo -e "${YELLOW}Unknown key format — setting as generic LLM_API_KEY.${NC}"
        echo "LLM_API_KEY=$LLM_KEY" >> .env
    fi
    USE_OLLAMA=false  # cloud provider, no need for Ollama
elif $USE_OLLAMA; then
    echo -e "${GREEN}Using:${NC} Ollama (local LLM — no API key needed)"
else
    # No key, no --ollama → interactive prompt
    echo ""
    echo -e "${BOLD}Choose your LLM provider:${NC}"
    echo ""
    echo "  1) Ollama (local, free, private — recommended to start)"
    echo "  2) Anthropic Claude"
    echo "  3) OpenAI GPT"
    echo "  4) Google Gemini"
    echo "  5) Skip (configure later in .env)"
    echo ""
    read -rp "Choice [1]: " CHOICE
    CHOICE="${CHOICE:-1}"

    case $CHOICE in
        1)
            USE_OLLAMA=true
            echo -e "${GREEN}Using Ollama.${NC} Model will download on first start (~2GB)."
            ;;
        2)
            read -rp "Anthropic API key (sk-ant-...): " KEY
            if [ -n "$KEY" ]; then
                sed -i.bak "s/^LLM_PROVIDER=.*/# LLM_PROVIDER=ollama/" .env && rm -f .env.bak
                sed -i.bak "s/^LLM_MODEL=.*/# LLM_MODEL=llama3.2/" .env && rm -f .env.bak
                sed -i.bak "s/^LLM_BASE_URL=.*/# LLM_BASE_URL=http:\/\/ollama:11434\/v1/" .env && rm -f .env.bak
                sed -i.bak "s/^# ANTHROPIC_API_KEY=.*/ANTHROPIC_API_KEY=$KEY/" .env && rm -f .env.bak
            fi
            ;;
        3)
            read -rp "OpenAI API key (sk-...): " KEY
            if [ -n "$KEY" ]; then
                sed -i.bak "s/^LLM_PROVIDER=.*/# LLM_PROVIDER=ollama/" .env && rm -f .env.bak
                sed -i.bak "s/^LLM_MODEL=.*/# LLM_MODEL=llama3.2/" .env && rm -f .env.bak
                sed -i.bak "s/^LLM_BASE_URL=.*/# LLM_BASE_URL=http:\/\/ollama:11434\/v1/" .env && rm -f .env.bak
                sed -i.bak "s/^# OPENAI_API_KEY=.*/OPENAI_API_KEY=$KEY/" .env && rm -f .env.bak
            fi
            ;;
        4)
            read -rp "Google API key (AIza...): " KEY
            if [ -n "$KEY" ]; then
                sed -i.bak "s/^LLM_PROVIDER=.*/# LLM_PROVIDER=ollama/" .env && rm -f .env.bak
                sed -i.bak "s/^LLM_MODEL=.*/# LLM_MODEL=llama3.2/" .env && rm -f .env.bak
                sed -i.bak "s/^LLM_BASE_URL=.*/# LLM_BASE_URL=http:\/\/ollama:11434\/v1/" .env && rm -f .env.bak
                sed -i.bak "s/^# GOOGLE_API_KEY=.*/GOOGLE_API_KEY=$KEY/" .env && rm -f .env.bak
            fi
            ;;
        5)
            echo -e "${YELLOW}Skipped. Edit .env later to configure LLM provider.${NC}"
            ;;
    esac
fi

# ── Optional: set API key for auth ───────────────────────────────────────────
echo ""
read -rp "Set an API key for AxiomBase auth? (leave blank for dev mode): " AUTH_KEY
if [ -n "$AUTH_KEY" ]; then
    sed -i.bak "s/^API_KEY=.*/API_KEY=$AUTH_KEY/" .env && rm -f .env.bak
    echo -e "${GREEN}Auth enabled.${NC} Use header: X-API-Key: $AUTH_KEY"
fi

# ── Launch ───────────────────────────────────────────────────────────────────
echo ""
echo -e "${BOLD}Starting AxiomBase...${NC}"

PROFILE_FLAG=""
if $USE_OLLAMA; then
    PROFILE_FLAG="--profile ollama"
fi

$COMPOSE_CMD $PROFILE_FLAG up -d

# ── Wait for healthy ─────────────────────────────────────────────────────────
echo ""
echo -n "Waiting for server"
for i in $(seq 1 30); do
    if curl -sf "http://localhost:$PORT/health" &>/dev/null; then
        echo ""
        echo -e "${GREEN}Ready!${NC}"
        break
    fi
    echo -n "."
    sleep 2
done

# ── Success banner ───────────────────────────────────────────────────────────
echo ""
echo -e "${GREEN}${BOLD}=== AxiomBase is running ===${NC}"
echo ""
echo -e "  ${BOLD}API:${NC}     http://localhost:$PORT"
echo -e "  ${BOLD}Health:${NC}  http://localhost:$PORT/health"
echo -e "  ${BOLD}Docs:${NC}   http://localhost:$PORT/llm.txt"
echo -e "  ${BOLD}MCP:${NC}    http://localhost:$PORT/mcp"
echo -e "  ${BOLD}Agent:${NC}  http://localhost:$PORT/.well-known/agent.json"
if $USE_OLLAMA; then
echo -e "  ${BOLD}Ollama:${NC} http://localhost:11434"
fi
echo ""
echo -e "${BOLD}Quick test:${NC}"
echo ""
echo -e "  ${CYAN}# Store a fact${NC}"
echo -e "  curl -X POST http://localhost:$PORT/tell \\"
echo -e "    -H 'Content-Type: application/json' \\"
echo -e "    -H 'X-Tenant-ID: default' \\"
echo -e "    -d '{\"predicate\":\"human\",\"args\":[\"socrates\"]}'"
echo ""
echo -e "  ${CYAN}# Define a rule${NC}"
echo -e "  curl -X POST http://localhost:$PORT/teach \\"
echo -e "    -H 'Content-Type: application/json' \\"
echo -e "    -H 'X-Tenant-ID: default' \\"
echo -e "    -d '{\"head\":{\"predicate\":\"mortal\",\"args\":[\"?x\"]},\"body\":[{\"predicate\":\"human\",\"args\":[\"?x\"]}]}'"
echo ""
echo -e "  ${CYAN}# Query with reasoning${NC}"
echo -e "  curl -X POST http://localhost:$PORT/ask \\"
echo -e "    -H 'Content-Type: application/json' \\"
echo -e "    -H 'X-Tenant-ID: default' \\"
echo -e "    -d '{\"predicate\":\"mortal\",\"args\":[\"?who\"]}'"
echo ""
echo -e "  ${CYAN}# Ingest text (requires LLM)${NC}"
echo -e "  curl -X POST http://localhost:$PORT/extract \\"
echo -e "    -H 'Content-Type: application/json' \\"
echo -e "    -H 'X-Tenant-ID: default' \\"
echo -e "    -d '{\"text\":\"Alice is a software engineer at Acme Corp.\",\"assert\":true}'"
echo ""
echo -e "${BOLD}MCP config${NC} (for Claude Desktop, Cursor, etc.):"
echo ""
echo -e "  ${DIM}Add to your MCP settings:${NC}"
echo "  {"
echo "    \"mcpServers\": {"
echo "      \"axiombase\": {"
echo "        \"url\": \"http://localhost:$PORT/mcp/sse\","
echo "        \"transport\": \"sse\""
echo "      }"
echo "    }"
echo "  }"
echo ""
echo -e "${DIM}Config: $(pwd)/.env  |  Data: docker volume 'axiombase-data'${NC}"
echo -e "${DIM}Logs:   $COMPOSE_CMD logs -f axiombase${NC}"
echo -e "${DIM}Stop:   $COMPOSE_CMD $PROFILE_FLAG down${NC}"
echo ""
