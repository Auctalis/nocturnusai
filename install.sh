#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# NocturnusAI Installer
# Downloads the CLI binary and runs the interactive setup wizard.
#
# Usage:
#   curl -fsSL https://raw.githubusercontent.com/Auctalis/nocturnusai/main/install.sh | bash
#   curl -fsSL ... | bash -s -- --ollama
#   curl -fsSL ... | bash -s -- --key sk-ant-...
#   curl -fsSL ... | bash -s -- --port 8080
#
# All flags are forwarded to `nocturnusai setup`. See `nocturnusai setup --help`.
# ─────────────────────────────────────────────────────────────────────────────
set -eo pipefail

GREEN='\033[0;32m'
YELLOW='\033[0;33m'
RED='\033[0;31m'
CYAN='\033[0;36m'
BOLD='\033[1m'
DIM='\033[2m'
NC='\033[0m'

trap 'echo ""; echo -e "${RED}${BOLD}Install failed at line $LINENO${NC}"; exit 1' ERR

# ── Docker-only fallback ─────────────────────────────────────────────────────
# When CLI binary isn't available, pull the Docker image directly and
# generate a minimal compose file so the user gets a running server.
docker_fallback() {
    local port=9300
    local install_dir="./nocturnusai"

    # Parse --port and --dir from forwarded args
    while [ $# -gt 0 ]; do
        case "$1" in
            --port) port="$2"; shift 2 ;;
            --dir)  install_dir="$2"; shift 2 ;;
            *)      shift ;;
        esac
    done

    # Detect container runtime
    local compose_cmd="" container_cmd=""
    if command -v docker >/dev/null 2>&1 && docker info >/dev/null 2>&1; then
        if docker compose version >/dev/null 2>&1; then
            compose_cmd="docker compose"; container_cmd="docker"
        elif command -v docker-compose >/dev/null 2>&1; then
            compose_cmd="docker-compose"; container_cmd="docker"
        fi
    fi
    if [ -z "$compose_cmd" ] && command -v podman >/dev/null 2>&1 && podman info >/dev/null 2>&1; then
        if command -v podman-compose >/dev/null 2>&1; then
            compose_cmd="podman-compose"; container_cmd="podman"
        fi
    fi

    if [ -z "$compose_cmd" ]; then
        echo -e "${RED}${BOLD}Docker or Podman is required.${NC}"
        echo ""
        echo -e "Install Docker:"
        echo -e "  macOS:   brew install --cask docker"
        echo -e "  Ubuntu:  curl -fsSL https://get.docker.com | sh"
        exit 1
    fi

    echo -e "${GREEN}Found:${NC} $compose_cmd"

    # Pull image
    echo -e "Pulling ${BOLD}ghcr.io/auctalis/nocturnusai:latest${NC}..."
    if ! $container_cmd pull ghcr.io/auctalis/nocturnusai:latest; then
        echo -e "${RED}Failed to pull Docker image.${NC}"
        exit 1
    fi

    # Create install directory and compose file
    mkdir -p "$install_dir/data"
    cat > "$install_dir/docker-compose.yml" <<'COMPOSE'
services:
  nocturnusai:
    image: ghcr.io/auctalis/nocturnusai:latest
    container_name: nocturnusai
    restart: unless-stopped
    ports:
      - "${PORT:-9300}:${PORT:-9300}"
    volumes:
      - ./data:/data
    environment:
      - PORT=${PORT:-9300}
      - HOST=0.0.0.0
      - STORAGE_DIR=/data
      - API_KEY=${API_KEY:-}
      - LLM_PROVIDER=${LLM_PROVIDER:-}
      - LLM_MODEL=${LLM_MODEL:-}
      - ANTHROPIC_API_KEY=${ANTHROPIC_API_KEY:-}
      - OPENAI_API_KEY=${OPENAI_API_KEY:-}
      - GOOGLE_API_KEY=${GOOGLE_API_KEY:-}
    healthcheck:
      test: ["CMD", "curl", "-sf", "http://localhost:${PORT:-9300}/health"]
      interval: 10s
      timeout: 5s
      retries: 5
      start_period: 30s
COMPOSE

    # Create .env with port
    if [ ! -f "$install_dir/.env" ]; then
        echo "PORT=$port" > "$install_dir/.env"
    fi

    # Start
    echo ""
    echo -e "${BOLD}Starting NocturnusAI...$NC"
    (cd "$install_dir" && $compose_cmd up -d)

    # Wait for health
    echo ""
    printf "Waiting for server"
    for i in $(seq 1 30); do
        if curl -sf "http://localhost:$port/health" >/dev/null 2>&1; then
            echo ""
            echo -e "${GREEN}${BOLD}Ready!${NC}"
            break
        fi
        printf "."
        sleep 2
    done

    # Banner
    echo ""
    echo -e "${GREEN}${BOLD}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
    echo -e "${GREEN}${BOLD}  NocturnusAI is running!${NC}"
    echo -e "${GREEN}${BOLD}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
    echo ""
    echo -e "  ${BOLD}Server${NC}       http://localhost:$port"
    echo -e "  ${BOLD}Health${NC}       http://localhost:$port/health"
    echo -e "  ${BOLD}API Docs${NC}     http://localhost:$port/llm.txt"
    echo -e "  ${BOLD}MCP${NC}          http://localhost:$port/mcp"
    echo ""
    echo -e "  ${CYAN}# Quick start${NC}"
    echo -e "  curl -s http://localhost:$port/assert/fact \\"
    echo -e "    -H 'Content-Type: application/json' \\"
    echo -e "    -H 'X-Tenant-ID: default' \\"
    echo -e "    -d '{\"predicate\":\"human\",\"args\":[\"socrates\"]}'"
    echo ""
    echo -e "  ${BOLD}Manage${NC}"
    echo -e "  cd $(cd "$install_dir" && pwd)"
    echo -e "  $compose_cmd logs -f nocturnusai   ${DIM}# tail logs${NC}"
    echo -e "  $compose_cmd down                   ${DIM}# stop${NC}"
    echo -e "  $compose_cmd up -d                  ${DIM}# restart${NC}"
    echo ""
    echo -e "  ${DIM}Config: $(cd "$install_dir" && pwd)/.env${NC}"
    echo -e "  ${DIM}Note: Install the CLI binary for the full setup wizard with LLM config.${NC}"
    echo ""
}

# ── Banner ──────────────────────────────────────────────────────────────────
echo ""
echo -e "${CYAN}${BOLD}NocturnusAI${NC} — Logic server for Agentic AI"
echo ""

# ── Detect platform ─────────────────────────────────────────────────────────
os="$(uname -s | tr '[:upper:]' '[:lower:]')"
arch="$(uname -m)"
if [ "$os" = "darwin" ]; then os="macos"; fi
if [ "$arch" = "aarch64" ]; then arch="arm64"; fi

binary="nocturnusai-${os}-${arch}"
url="https://github.com/Auctalis/nocturnusai/releases/latest/download/${binary}"

# ── Determine install location ──────────────────────────────────────────────
# Prefer /usr/local/bin only if already writable (no sudo prompts).
# Otherwise use ~/.local/bin — standard user-space location.
SUDO=""
if [ -w "/usr/local/bin" ]; then
    install_path="/usr/local/bin/nocturnusai"
else
    mkdir -p "$HOME/.local/bin"
    install_path="$HOME/.local/bin/nocturnusai"
fi

# ── Download CLI binary ────────────────────────────────────────────────────
echo -e "Downloading ${BOLD}${binary}${NC}..."
tmp_path=$(mktemp)

if ! curl -fL --progress-bar "$url" -o "$tmp_path"; then
    rm -f "$tmp_path"
    echo -e "${YELLOW}No CLI binary for ${os}/${arch} — falling back to Docker.$NC"
    echo ""
    docker_fallback "$@"
    exit 0
fi

chmod +x "$tmp_path"

# Verify binary runs (background + kill guards against hangs on older builds)
"$tmp_path" --help >/dev/null 2>&1 &
_pid=$!
sleep 2
if ! kill -0 "$_pid" 2>/dev/null; then
    # Process exited — check if it succeeded
    wait "$_pid" 2>/dev/null || {
        rm -f "$tmp_path"
        echo -e "${YELLOW}Binary not compatible — falling back to Docker.$NC"
        echo ""
        docker_fallback "$@"
        exit 0
    }
else
    # Still running after 2s (old build with --help bug) — kill it, it's fine
    kill "$_pid" 2>/dev/null; wait "$_pid" 2>/dev/null || true
fi

# Move to install path
mv "$tmp_path" "$install_path"

echo -e "${GREEN}CLI installed:${NC} $install_path"

# ── Add ~/.local/bin to PATH if needed ──────────────────────────────────────
if [[ "$install_path" == *".local/bin"* ]]; then
    added_to_rc=false
    for rc in "$HOME/.bashrc" "$HOME/.zshrc" "$HOME/.profile"; do
        if [ -f "$rc" ] && ! grep -q '\.local/bin' "$rc"; then
            echo 'export PATH="$HOME/.local/bin:$PATH"' >> "$rc"
            added_to_rc=true
        fi
    done
    export PATH="$HOME/.local/bin:$PATH"
    if $added_to_rc; then
        echo -e "${YELLOW}Note:${NC} Installed to ~/.local/bin — run ${BOLD}source ~/.zshrc${NC} or open a new terminal to use ${BOLD}nocturnusai${NC}"
    fi
fi

# ── Run setup wizard ────────────────────────────────────────────────────────
# Redirect stdin from /dev/tty so interactive prompts work even when
# the script itself was piped from curl. All flags ($@) are forwarded.
echo ""
if [ -e /dev/tty ]; then
    exec "$install_path" setup "$@" < /dev/tty
else
    exec "$install_path" setup --non-interactive "$@"
fi
