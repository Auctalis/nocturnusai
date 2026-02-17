#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# AxiomBase Installer
# Works everywhere. Installs everything. You're welcome. 🦞
#
# Usage:
#   curl -fsSL https://openclaw.ai/install.sh | bash
#   curl -fsSL https://openclaw.ai/install.sh | bash -s -- --ollama
#   curl -fsSL https://openclaw.ai/install.sh | bash -s -- --key sk-ant-...
#   curl -fsSL https://openclaw.ai/install.sh | bash -s -- --port 8080
#
# Options:
#   --ollama    Include local Ollama (no API key needed)
#   --monitoring Include Prometheus + Grafana dashboards
#   --dir DIR   Install directory (default: ./axiombase)
#   --port PORT Server port (default: 9300)
#   --key KEY   LLM API key (Anthropic/OpenAI/Google — auto-detected)
# ─────────────────────────────────────────────────────────────────────────────
set -e

VERSION="latest"
INSTALL_DIR="./axiombase"
PORT=9300
USE_OLLAMA=false
USE_MONITORING=false
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
        --ollama)      USE_OLLAMA=true; shift ;;
        --monitoring)  USE_MONITORING=true; shift ;;
        --dir)         INSTALL_DIR="$2"; shift 2 ;;
        --port)        PORT="$2"; shift 2 ;;
        --key)         LLM_KEY="$2"; shift 2 ;;
        --help|-h)
            echo "Usage: curl -fsSL https://openclaw.ai/install.sh | bash -s -- [OPTIONS]"
            echo ""
            echo "Options:"
            echo "  --ollama       Include local Ollama LLM (no API key needed)"
            echo "  --monitoring   Include Prometheus + Grafana dashboards"
            echo "  --port PORT    Server port (default: 9300)"
            echo "  --dir PATH     Install directory (default: ./axiombase)"
            echo "  --key KEY      LLM API key (auto-detects provider)"
            echo "  --help         Show this help"
            exit 0
            ;;
        *) echo -e "${RED}Unknown option: $1${NC}"; exit 1 ;;
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

# monitoring config (if requested)
if $USE_MONITORING; then
    mkdir -p monitoring/prometheus monitoring/grafana/provisioning/datasources monitoring/grafana/dashboards
    curl -fsSL "$REPO_RAW/monitoring/prometheus/prometheus.yml" -o monitoring/prometheus/prometheus.yml 2>/dev/null || true
    curl -fsSL "$REPO_RAW/monitoring/grafana/provisioning/datasources/datasource.yml" -o monitoring/grafana/provisioning/datasources/datasource.yml 2>/dev/null || true
fi

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
elif [ -t 0 ]; then
    # Interactive terminal — ask the user
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
else
    # Non-interactive (piped) and no --key — default to Ollama
    echo -e "${GREEN}Defaulting to Ollama${NC} (local LLM, no API key needed)"
    USE_OLLAMA=true
fi

# ── Launch ───────────────────────────────────────────────────────────────────
echo ""
echo -e "${BOLD}Starting AxiomBase...${NC}"

PROFILE_FLAGS=""
if $USE_OLLAMA; then
    PROFILE_FLAGS="--profile ollama"
fi
if $USE_MONITORING; then
    PROFILE_FLAGS="$PROFILE_FLAGS --profile monitoring"
fi

$COMPOSE_CMD $PROFILE_FLAGS up -d

# ── Wait for healthy ─────────────────────────────────────────────────────────
echo ""
echo -n "Waiting for server"
HEALTHY=false
for i in $(seq 1 30); do
    if curl -sf "http://localhost:$PORT/health" &>/dev/null; then
        echo ""
        HEALTHY=true
        break
    fi
    echo -n "."
    sleep 2
done

if $HEALTHY; then
    echo -e "${GREEN}${BOLD}Ready!${NC}"
else
    echo ""
    echo -e "${YELLOW}Server still starting — check logs:${NC} $COMPOSE_CMD logs -f axiombase"
fi

# ── Success banner ───────────────────────────────────────────────────────────
echo ""
echo -e "${GREEN}${BOLD}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
echo -e "${GREEN}${BOLD}  AxiomBase is running! 🦞${NC}"
echo -e "${GREEN}${BOLD}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
echo ""
echo -e "  ${BOLD}API${NC}        http://localhost:$PORT"
echo -e "  ${BOLD}Health${NC}     http://localhost:$PORT/health"
echo -e "  ${BOLD}API Docs${NC}   http://localhost:$PORT/llm.txt"
echo -e "  ${BOLD}MCP${NC}        http://localhost:$PORT/mcp"
echo -e "  ${BOLD}Agent Card${NC} http://localhost:$PORT/.well-known/agent.json"
if $USE_OLLAMA; then
echo -e "  ${BOLD}Ollama${NC}     http://localhost:11434"
fi
if $USE_MONITORING; then
echo -e "  ${BOLD}Grafana${NC}    http://localhost:3000  (admin / axiombase)"
echo -e "  ${BOLD}Prometheus${NC} http://localhost:9090"
fi
echo ""
echo -e "  ${BOLD}Try it:${NC}"
echo ""
echo -e "    ${CYAN}# Store a fact${NC}"
echo "    curl -sX POST http://localhost:$PORT/tell \\"
echo "      -H 'Content-Type: application/json' \\"
echo "      -d '{\"predicate\":\"human\",\"args\":[\"socrates\"]}'"
echo ""
echo -e "    ${CYAN}# Teach a rule${NC}"
echo "    curl -sX POST http://localhost:$PORT/teach \\"
echo "      -H 'Content-Type: application/json' \\"
echo "      -d '{\"head\":{\"predicate\":\"mortal\",\"args\":[\"?x\"]},\"body\":[{\"predicate\":\"human\",\"args\":[\"?x\"]}]}'"
echo ""
echo -e "    ${CYAN}# Ask a question${NC}"
echo "    curl -sX POST http://localhost:$PORT/ask \\"
echo "      -H 'Content-Type: application/json' \\"
echo "      -d '{\"predicate\":\"mortal\",\"args\":[\"?who\"]}'"
echo ""
echo -e "  ${BOLD}Manage:${NC}"
echo -e "    cd $(pwd)"
echo -e "    $COMPOSE_CMD logs -f axiombase   ${DIM}# tail logs${NC}"
echo -e "    $COMPOSE_CMD $PROFILE_FLAGS down  ${DIM}# stop${NC}"
echo -e "    $COMPOSE_CMD $PROFILE_FLAGS up -d ${DIM}# restart${NC}"
echo ""
echo -e "  ${BOLD}MCP config${NC} (Claude Desktop, Cursor, Windsurf, etc.):"
echo ""
echo "    {"
echo "      \"mcpServers\": {"
echo "        \"axiombase\": {"
echo "          \"url\": \"http://localhost:$PORT/mcp/sse\","
echo "          \"transport\": \"sse\""
echo "        }"
echo "      }"
echo "    }"
echo ""
echo -e "  ${DIM}Config: $(pwd)/.env${NC}"
echo -e "  ${DIM}Docs:   https://github.com/essaouirallc/logic-server${NC}"
echo ""
