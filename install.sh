#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# NocturnusAI Installer
# Works everywhere. Installs everything. You're welcome.
#
# Usage:
#   curl -fsSL https://raw.githubusercontent.com/Auctalis/nocturnusai/main/install.sh | bash
#   curl -fsSL https://raw.githubusercontent.com/Auctalis/nocturnusai/main/install.sh | bash -s -- --ollama
#   curl -fsSL https://raw.githubusercontent.com/Auctalis/nocturnusai/main/install.sh | bash -s -- --key sk-ant-...
#   curl -fsSL https://raw.githubusercontent.com/Auctalis/nocturnusai/main/install.sh | bash -s -- --port 8080
#
# Options:
#   --ollama    Include local Ollama (no API key needed)
#   --monitoring Include Prometheus + Grafana dashboards
#   --dir DIR   Install directory (default: ./nocturnusai)
#   --port PORT Server port (default: 9300)
#   --key KEY   LLM API key (Anthropic/OpenAI/Google — auto-detected)
# ─────────────────────────────────────────────────────────────────────────────
set -eo pipefail

# Colors
GREEN='\033[0;32m'
YELLOW='\033[0;33m'
RED='\033[0;31m'
CYAN='\033[0;36m'
BOLD='\033[1m'
DIM='\033[2m'
NC='\033[0m'

# ── Error trap — never fail silently ────────────────────────────────────────
trap 'echo ""; echo -e "${RED}${BOLD}Install failed at line $LINENO${NC}"; echo -e "${DIM}Run with:  bash -x <(curl -fsSL https://raw.githubusercontent.com/Auctalis/nocturnusai/main/install.sh) to debug${NC}"; exit 1' ERR

VERSION="latest"
INSTALL_DIR="./nocturnusai"
export PORT=9300
USE_OLLAMA=false
USE_MONITORING=false
LLM_KEY=""

# ── Gum (styled terminal UI) ─────────────────────────────────────────────────
GUM=""
GUM_VERSION="0.14.5"

bootstrap_gum() {
    # Already have it?
    if command -v gum &>/dev/null; then
        GUM="$(command -v gum)"
        return
    fi

    # Only try to install if we have a real terminal
    [ -t 0 ] || return 0

    local os arch
    os="$(uname -s | tr '[:upper:]' '[:lower:]')"
    arch="$(uname -m)"
    [[ "$arch" == "x86_64" ]] && arch="x86_64"
    [[ "$arch" == "arm64" || "$arch" == "aarch64" ]] && arch="arm64"

    local tmp
    tmp="$(mktemp -d)"
    local url="https://github.com/charmbracelet/gum/releases/download/v${GUM_VERSION}/gum_${GUM_VERSION}_${os}_${arch}.tar.gz"

    if curl -fsSL "$url" -o "$tmp/gum.tar.gz" 2>/dev/null; then
        tar -xzf "$tmp/gum.tar.gz" -C "$tmp" 2>/dev/null || true
        if [ -f "$tmp/gum" ]; then
            GUM="$tmp/gum"
            chmod +x "$GUM"
        fi
    fi
    rm -rf "$tmp/gum.tar.gz" 2>/dev/null || true
}

# Wrappers — fall back gracefully if gum not available
gum_style() {
    if [ -n "$GUM" ]; then
        "$GUM" style "$@"
    else
        # Strip flags, just print text
        local text=""
        while [[ $# -gt 0 ]]; do
            case $1 in
                --*) shift; [[ $# -gt 0 && "$1" != --* ]] && shift ;;
                *) text="$1"; shift ;;
            esac
        done
        echo -e "${BOLD}${text}${NC}"
    fi
}

gum_choose() {
    if [ -n "$GUM" ]; then
        "$GUM" choose "$@"
    else
        # Plain numbered list fallback
        local header=""
        local -a items=()
        while [[ $# -gt 0 ]]; do
            case $1 in
                --header) header="$2"; shift 2 ;;
                --*) shift; [[ $# -gt 0 && "$1" != --* ]] && shift ;;
                *) items+=("$1"); shift ;;
            esac
        done
        [ -n "$header" ] && echo -e "\n${BOLD}${header}${NC}\n"
        for i in "${!items[@]}"; do
            echo "  $((i+1))) ${items[$i]}"
        done
        echo ""
        read -rp "Choice [1]: " idx
        idx="${idx:-1}"
        echo "${items[$((idx-1))]}"
    fi
}

gum_spin() {
    # gum spin --spinner dot --title "..." -- command
    if [ -n "$GUM" ]; then
        "$GUM" spin "$@"
    else
        # Extract title and command after --
        local title="Working..."
        local -a cmd=()
        local past_sep=false
        while [[ $# -gt 0 ]]; do
            if $past_sep; then
                cmd+=("$1")
            elif [[ "$1" == "--" ]]; then
                past_sep=true
            elif [[ "$1" == "--title" ]]; then
                title="$2"; shift
            fi
            shift
        done
        echo -n "$title "
        "${cmd[@]}" &>/dev/null &
        local pid=$!
        while kill -0 "$pid" 2>/dev/null; do echo -n "."; sleep 1; done
        wait "$pid" 2>/dev/null || true
        echo ""
    fi
}

bootstrap_gum

# ── Safe .env writer ─────────────────────────────────────────────────────────
# Writes KEY=VALUE to .env safely — avoids sed injection from special chars
# (/, &, \) that can appear in API keys.
set_env_key() {
    local key="$1" value="$2" file="${3:-.env}"
    # Remove any existing line (commented or not) for this key
    # || true prevents grep from returning exit 1 when all lines match
    grep -v "^[#[:space:]]*${key}=" "$file" > "${file}.tmp" || true
    mv "${file}.tmp" "$file"
    # Append new value using printf to avoid any interpretation of special chars
    printf '%s=%s\n' "$key" "$value" >> "$file"
}

# ── Parse args ───────────────────────────────────────────────────────────────
while [[ $# -gt 0 ]]; do
    case $1 in
        --ollama)      USE_OLLAMA=true; shift ;;
        --monitoring)  USE_MONITORING=true; shift ;;
        --dir)
            # Reject paths with null bytes or shell metacharacters
            if [[ "$2" =~ [[:cntrl:]\;\|\&\`\$] ]]; then
                echo -e "${RED}Invalid install directory: $2${NC}"; exit 1
            fi
            INSTALL_DIR="$2"; shift 2 ;;
        --port)        PORT="$2"; shift 2 ;;
        --key)         LLM_KEY="$2"; shift 2 ;;
        --help|-h)
            echo "Usage: curl -fsSL https://raw.githubusercontent.com/Auctalis/nocturnusai/main/install.sh | bash -s -- [OPTIONS]"
            echo ""
            echo "Options:"
            echo "  --ollama       Include local Ollama LLM (no API key needed)"
            echo "  --monitoring   Include Prometheus + Grafana dashboards"
            echo "  --port PORT    Server port (default: 9300)"
            echo "  --dir PATH     Install directory (default: ./nocturnusai)"
            echo "  --key KEY      LLM API key (auto-detects provider)"
            echo "  --help         Show this help"
            exit 0
            ;;
        *) echo -e "${RED}Unknown option: $1${NC}"; exit 1 ;;
    esac
done

# ── Banner ───────────────────────────────────────────────────────────────────
echo ""
if [ -n "$GUM" ]; then
    "$GUM" style \
        --foreground "#7c3aed" --bold \
        --border double --border-foreground "#7c3aed" \
        --padding "1 4" --margin "0 2" \
" _   _            _                                 _    ___
| \\ | | ___   ___| |_ _   _ _ __ _ __  _   _ ___   / \\  |_ _|
|  \\| |/ _ \\ / __| __| | | | '__| '_ \\| | | / __| / _ \\  | |
| |\\  | (_) | (__| |_| |_| | |  | | | | |_| \\__ \\/ ___ \\ | |
|_| \\_|\\___/ \\___|\__|\__,_|_|  |_| |_|\\__,_|___/_/   \\_\\___|"
    "$GUM" style --foreground "#a78bfa" --faint "  Logic server for Agentic AI"
else
    echo -e "${CYAN}${BOLD}"
cat << 'BANNER'
 _   _            _                                 _    ___
| \ | | ___   ___| |_ _   _ _ __ _ __  _   _ ___   / \  |_ _|
|  \| |/ _ \ / __| __| | | | '__| '_ \| | | / __| / _ \  | |
| |\  | (_) | (__| |_| |_| | |  | | | | |_| \__ \/ ___ \ | |
|_| \_|\___/ \___|\__|\__,_|_|  |_| |_|\__,_|___/_/   \_\___|
BANNER
    echo -e "${NC}"
    echo -e "${DIM}Logic server for Agentic AI${NC}"
fi
echo ""

# ── Check prerequisites ─────────────────────────────────────────────────────
check_cmd() {
    command -v "$1" &>/dev/null
}

HAS_DOCKER=false
HAS_PODMAN=false
HAS_COMPOSE=false
COMPOSE_CMD=""
CONTAINER_CMD=""   # docker or podman — used for pull/inspect

# Detect container engines first
if check_cmd docker && docker info &>/dev/null; then
    HAS_DOCKER=true
    CONTAINER_CMD="docker"
fi

if check_cmd podman && podman info &>/dev/null; then
    HAS_PODMAN=true
    [ -z "$CONTAINER_CMD" ] && CONTAINER_CMD="podman"
fi

# Detect compose — prefer docker compose (V2 plugin), then docker-compose, then podman-compose
if $HAS_DOCKER; then
    if docker compose version &>/dev/null 2>&1; then
        HAS_COMPOSE=true
        COMPOSE_CMD="docker compose"
    elif check_cmd docker-compose; then
        # docker-compose standalone requires a working Docker daemon
        HAS_COMPOSE=true
        COMPOSE_CMD="docker-compose"
    fi
fi

if ! $HAS_COMPOSE && $HAS_PODMAN; then
    if check_cmd podman-compose; then
        HAS_COMPOSE=true
        COMPOSE_CMD="podman-compose"
        CONTAINER_CMD="podman"
    fi
fi

if ! $HAS_COMPOSE; then
    echo -e "${RED}${BOLD}A container runtime with compose is required.${NC}"
    echo ""
    echo "Install Docker:"
    echo "  macOS:   brew install --cask docker"
    echo "  Ubuntu:  curl -fsSL https://get.docker.com | sh"
    echo "  Windows: https://docs.docker.com/desktop/install/windows-install/"
    echo ""
    echo "Or install Podman:"
    echo "  macOS:   brew install podman podman-compose"
    echo "  Ubuntu:  sudo apt install podman podman-compose"
    echo "  Fedora:  sudo dnf install podman podman-compose"
    exit 1
fi

echo -e "${GREEN}Found:${NC} $COMPOSE_CMD ($CONTAINER_CMD)"

# ── Download config or clone repo ────────────────────────────────────────────
REPO_URL="https://github.com/Auctalis/nocturnusai.git"
REPO_RAW="https://raw.githubusercontent.com/Auctalis/nocturnusai/main"
NEED_BUILD=false

mkdir -p "$INSTALL_DIR"
cd "$INSTALL_DIR"
echo -e "${GREEN}Installing to:${NC} $(pwd)"

# Try to pull the published image first (fast path — no source needed)
echo -e "${DIM}Checking for published container image...${NC}"
if $CONTAINER_CMD pull ghcr.io/auctalis/nocturnusai:latest &>/dev/null; then
    echo -e "${GREEN}Found published image${NC} — skipping build"
    # Grab .env.example for reference
    curl -fsSL "$REPO_RAW/.env.example" -o .env.example 2>/dev/null || true
else
    # No published image — need to clone and build from source
    echo -e "${YELLOW}No published image yet${NC} — building from source"
    NEED_BUILD=true

    if [ -d ".git" ]; then
        echo -e "${DIM}Updating existing source...${NC}"
        git pull --ff-only 2>/dev/null || true
    elif [ -f "Dockerfile" ]; then
        echo -e "${DIM}Existing source found, reusing...${NC}"
    else
        echo -e "${DIM}Downloading NocturnusAI source...${NC}"
        cd ..
        rm -rf "$INSTALL_DIR"
        if check_cmd git; then
            git clone --depth 1 "$REPO_URL" "$INSTALL_DIR"
        else
            mkdir -p "$INSTALL_DIR"
            curl -fsSL "https://github.com/Auctalis/nocturnusai/archive/refs/heads/main.tar.gz" \
                | tar -xz --strip-components=1 -C "$INSTALL_DIR"
        fi
        cd "$INSTALL_DIR"
    fi
fi

# ── Set up .env ──────────────────────────────────────────────────────────────
if [ ! -f .env ]; then
    if [ -f .env.example ]; then
        cp .env.example .env
    else
        touch .env
    fi
fi

# ── Configure .env ───────────────────────────────────────────────────────────
# Validate port is a number before writing
if ! [[ "$PORT" =~ ^[0-9]+$ ]] || [ "$PORT" -lt 1 ] || [ "$PORT" -gt 65535 ]; then
    echo -e "${RED}Invalid port: $PORT${NC}"
    exit 1
fi
set_env_key "PORT" "$PORT"

# Configure LLM provider
if [ -n "$LLM_KEY" ]; then
    # Auto-detect provider from key format
    if [[ "$LLM_KEY" == sk-ant-* ]]; then
        echo -e "${GREEN}Detected:${NC} Anthropic Claude"
        set_env_key "ANTHROPIC_API_KEY" "$LLM_KEY"
    elif [[ "$LLM_KEY" == sk-* ]]; then
        echo -e "${GREEN}Detected:${NC} OpenAI"
        set_env_key "OPENAI_API_KEY" "$LLM_KEY"
    elif [[ "$LLM_KEY" == AIza* ]]; then
        echo -e "${GREEN}Detected:${NC} Google Gemini"
        set_env_key "GOOGLE_API_KEY" "$LLM_KEY"
    else
        echo -e "${YELLOW}Unknown key format — setting as generic LLM_API_KEY.${NC}"
        set_env_key "LLM_API_KEY" "$LLM_KEY"
    fi
    USE_OLLAMA=false  # cloud provider, no need for Ollama
elif $USE_OLLAMA; then
    set_env_key "LLM_PROVIDER" "ollama"
    set_env_key "LLM_MODEL" "llama3.2"
    echo -e "${GREEN}Using:${NC} Ollama (local LLM — no API key needed)"
elif [ -t 0 ]; then
    # Interactive terminal — wizard
    echo ""
    CHOICE=$(gum_choose \
        --header "Choose your LLM provider (optional — core API works without one):" \
        "Skip  (server only — configure LLM later in .env)" \
        "Anthropic Claude" \
        "OpenAI GPT" \
        "Google Gemini" \
        "Ollama  (local, free, private — downloads ~2GB)")

    case "$CHOICE" in
        Ollama*)
            USE_OLLAMA=true
            set_env_key "LLM_PROVIDER" "ollama"
            set_env_key "LLM_MODEL" "llama3.2"
            echo -e "${GREEN}Using Ollama.${NC} Model will download on first start (~2GB)."
            ;;
        Anthropic*)
            read -rp "Anthropic API key (sk-ant-...): " KEY
            [ -n "$KEY" ] && set_env_key "ANTHROPIC_API_KEY" "$KEY"
            ;;
        OpenAI*)
            read -rp "OpenAI API key (sk-...): " KEY
            [ -n "$KEY" ] && set_env_key "OPENAI_API_KEY" "$KEY"
            ;;
        Google*)
            read -rp "Google API key (AIza...): " KEY
            [ -n "$KEY" ] && set_env_key "GOOGLE_API_KEY" "$KEY"
            ;;
        Skip*)
            echo -e "${YELLOW}Skipped. Edit .env later to configure LLM provider.${NC}"
            ;;
    esac
else
    # Non-interactive (piped) and no --key — server only, no LLM
    echo -e "${GREEN}Starting server only${NC} (no LLM — core API works without one)"
    echo -e "${DIM}  Add --ollama for a local LLM, or --key <api-key> for a cloud provider${NC}"
fi

# ── Server authentication ─────────────────────────────────────────────────────
AUTH_CONFIGURED=false
if [ -f .env ] && grep -qE '^API_KEY=' .env 2>/dev/null; then
    AUTH_CONFIGURED=true
fi

if [ -t 0 ] && ! $AUTH_CONFIGURED; then
    echo ""
    echo -e "${BOLD}Secure your server?${NC}"
    echo -e "${DIM}Set an API key to require authentication on all requests.${NC}"
    echo ""

    AUTH_CHOICE=$(gum_choose \
        --header "Set a NocturnusAI API key?" \
        "Skip — leave open (localhost only)" \
        "Generate a random key" \
        "Enter my own key")

    case "$AUTH_CHOICE" in
        *random*)
            GEN_KEY="nai-$(openssl rand -hex 20 2>/dev/null || head -c 40 /dev/urandom | od -An -tx1 | tr -d ' \n')"
            set_env_key "API_KEY" "$GEN_KEY"
            AUTH_CONFIGURED=true
            echo -e "${GREEN}API key set:${NC} $GEN_KEY"
            echo -e "${DIM}Use header:  X-API-Key: $GEN_KEY${NC}"
            ;;
        *own*)
            read -rp "API key: " AUTH_KEY
            if [ -n "$AUTH_KEY" ]; then
                set_env_key "API_KEY" "$AUTH_KEY"
                AUTH_CONFIGURED=true
                echo -e "${GREEN}Saved.${NC}"
            fi
            ;;
        *)
            echo -e "${DIM}Skipped. The server is open — fine for localhost development.${NC}"
            ;;
    esac
fi

# ── Detect configured LLM for banner ──────────────────────────────────────────
LLM_CONFIGURED=false
LLM_PROVIDER_LABEL="none"
if [ -f .env ]; then
    if grep -qE '^ANTHROPIC_API_KEY=' .env 2>/dev/null; then
        LLM_CONFIGURED=true; LLM_PROVIDER_LABEL="Anthropic Claude"
    elif grep -qE '^OPENAI_API_KEY=' .env 2>/dev/null; then
        LLM_CONFIGURED=true; LLM_PROVIDER_LABEL="OpenAI GPT"
    elif grep -qE '^GOOGLE_API_KEY=' .env 2>/dev/null; then
        LLM_CONFIGURED=true; LLM_PROVIDER_LABEL="Google Gemini"
    elif grep -qE '^LLM_API_KEY=' .env 2>/dev/null; then
        LLM_CONFIGURED=true; LLM_PROVIDER_LABEL="Custom LLM"
    fi
fi
if $USE_OLLAMA; then
    LLM_CONFIGURED=true; LLM_PROVIDER_LABEL="Ollama (local)"
fi

# ── Generate compose file (fast path only) ────────────────────────────────────
# Generated AFTER config so Ollama choice and env vars are reflected.
# Uses env_file to pass all .env vars (LLM keys, auth, etc.) to the container.
if ! $NEED_BUILD; then
    if $USE_OLLAMA; then
        cat > docker-compose.yml <<'COMPOSEFILE'
services:
  nocturnusai:
    image: ghcr.io/auctalis/nocturnusai:latest
    container_name: nocturnusai
    restart: unless-stopped
    ports:
      - "${PORT:-9300}:${PORT:-9300}"
    volumes:
      - nocturnusai-data:/data
    env_file:
      - .env
    environment:
      - HOST=0.0.0.0
      - STORAGE_DIR=/data
    healthcheck:
      test: ["CMD", "curl", "-sf", "http://localhost:${PORT:-9300}/health"]
      interval: 10s
      timeout: 5s
      retries: 5
      start_period: 30s

  ollama:
    image: ollama/ollama:latest
    container_name: nocturnusai-ollama
    restart: unless-stopped
    ports:
      - "11434:11434"
    volumes:
      - ollama-models:/root/.ollama
    healthcheck:
      test: ["CMD", "curl", "-sf", "http://localhost:11434/api/tags"]
      interval: 10s
      timeout: 5s
      retries: 10
      start_period: 15s

volumes:
  nocturnusai-data:
    driver: local
  ollama-models:
    driver: local
COMPOSEFILE
    else
        cat > docker-compose.yml <<'COMPOSEFILE'
services:
  nocturnusai:
    image: ghcr.io/auctalis/nocturnusai:latest
    container_name: nocturnusai
    restart: unless-stopped
    ports:
      - "${PORT:-9300}:${PORT:-9300}"
    volumes:
      - nocturnusai-data:/data
    env_file:
      - .env
    environment:
      - HOST=0.0.0.0
      - STORAGE_DIR=/data
    healthcheck:
      test: ["CMD", "curl", "-sf", "http://localhost:${PORT:-9300}/health"]
      interval: 10s
      timeout: 5s
      retries: 5
      start_period: 30s

volumes:
  nocturnusai-data:
    driver: local
COMPOSEFILE
    fi
fi

# ── Build & Launch ────────────────────────────────────────────────────────────
echo ""

# Profile flags only apply to the full compose (build path) — minimal compose
# includes services directly without profiles.
PROFILE_FLAGS=""
if $NEED_BUILD; then
    if $USE_OLLAMA; then
        PROFILE_FLAGS="--profile ollama"
    fi
    if $USE_MONITORING; then
        PROFILE_FLAGS="$PROFILE_FLAGS --profile monitoring"
    fi
fi

if $NEED_BUILD; then
    echo -e "${BOLD}Building NocturnusAI Docker image from source...${NC}"
    echo -e "${DIM}(first build takes 2-3 minutes — subsequent starts are instant)${NC}"
    $COMPOSE_CMD build nocturnusai
fi

echo ""
echo -e "${BOLD}Starting NocturnusAI...${NC}"
$COMPOSE_CMD $PROFILE_FLAGS up -d

# ── Wait for healthy ─────────────────────────────────────────────────────────
echo ""
HEALTHY=false

wait_for_health() {
    for i in $(seq 1 30); do
        if curl -sf "http://localhost:${PORT}/health" &>/dev/null; then
            return 0
        fi
        sleep 2
    done
    return 1
}

if [ -n "$GUM" ]; then
    if "$GUM" spin --spinner dot --title "Waiting for server to be ready..." -- bash -c "PORT=$PORT; $(declare -f wait_for_health); wait_for_health"; then
        HEALTHY=true
    fi
else
    echo -n "Waiting for server"
    for i in $(seq 1 30); do
        if curl -sf "http://localhost:${PORT}/health" &>/dev/null; then
            HEALTHY=true
            break
        fi
        echo -n "."
        sleep 2
    done
    echo ""
fi

if $HEALTHY; then
    echo -e "${GREEN}${BOLD}Ready!${NC}"
else
    echo ""
    echo -e "${YELLOW}Server still starting — check logs:${NC} $COMPOSE_CMD logs -f nocturnusai"
fi

# ── Pull Ollama model (background) ───────────────────────────────────────────
if $USE_OLLAMA && $HEALTHY; then
    OLLAMA_READY=false
    for i in $(seq 1 15); do
        if curl -sf http://localhost:11434/api/tags &>/dev/null; then
            OLLAMA_READY=true; break
        fi
        sleep 2
    done
    if $OLLAMA_READY; then
        echo -e "${DIM}Pulling Ollama model (llama3.2)... this runs in the background.${NC}"
        curl -sf http://localhost:11434/api/pull -d '{"name":"llama3.2"}' > /dev/null 2>&1 &
    fi
fi

# ── Install CLI binary ────────────────────────────────────────────────────────
CLI_INSTALLED=false
CLI_PATH=""

install_cli() {
    local os arch binary release_url install_dir install_path

    os="$(uname -s | tr '[:upper:]' '[:lower:]')"
    arch="$(uname -m)"
    [[ "$os" == "darwin" ]] && os="macos"
    [[ "$arch" == "x86_64" ]]            && arch="x86_64"
    [[ "$arch" == "arm64" || "$arch" == "aarch64" ]] && arch="arm64"

    binary="nocturnusai-${os}-${arch}"
    release_url="https://github.com/Auctalis/nocturnusai/releases/latest/download/${binary}"

    # Resolve install location — prefer /usr/local/bin, fall back to ~/.local/bin
    if [ -w "/usr/local/bin" ]; then
        install_path="/usr/local/bin/nocturnusai"
    elif sudo -n true 2>/dev/null; then
        install_path="/usr/local/bin/nocturnusai"
    else
        install_dir="$HOME/.local/bin"
        mkdir -p "$install_dir"
        install_path="$install_dir/nocturnusai"
    fi

    # Download
    if ! curl -fsSL "$release_url" -o "$install_path" 2>/dev/null; then
        return 1
    fi
    chmod +x "$install_path"

    # Verify it runs
    "$install_path" --help &>/dev/null || return 1

    CLI_PATH="$install_path"
    return 0
}

echo ""
echo -n "Installing CLI..."
if install_cli; then
    CLI_INSTALLED=true
    echo ""
else
    echo ""
fi

if $CLI_INSTALLED; then
    echo -e "${GREEN}CLI installed:${NC} $CLI_PATH"

    # Add ~/.local/bin to PATH if it's not already there
    if [[ "$CLI_PATH" == "$HOME/.local/bin/nocturnusai" ]]; then
        for rc in "$HOME/.bashrc" "$HOME/.zshrc" "$HOME/.profile"; do
            if [ -f "$rc" ] && ! grep -q '\.local/bin' "$rc"; then
                echo 'export PATH="$HOME/.local/bin:$PATH"' >> "$rc"
                echo -e "${DIM}  Added ~/.local/bin to PATH in $rc${NC}"
            fi
        done
        export PATH="$HOME/.local/bin:$PATH"
    fi
else
    echo -e "${YELLOW}CLI not installed${NC} — binary not available for this platform yet."
    echo -e "${DIM}  You can still use the HTTP API directly.${NC}"
fi

# ── Success banner ───────────────────────────────────────────────────────────
echo ""
echo -e "${GREEN}${BOLD}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
echo -e "${GREEN}${BOLD}  NocturnusAI is running!${NC}"
echo -e "${GREEN}${BOLD}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
echo ""

# ── Status summary ──
echo -e "  ${BOLD}Server${NC}       http://localhost:$PORT"
if $LLM_CONFIGURED; then
echo -e "  ${BOLD}LLM${NC}          ${GREEN}$LLM_PROVIDER_LABEL${NC}"
else
echo -e "  ${BOLD}LLM${NC}          ${DIM}none — add a key to .env and restart${NC}"
fi
if $AUTH_CONFIGURED; then
echo -e "  ${BOLD}Auth${NC}         ${GREEN}API key required${NC}  ${DIM}(X-API-Key header)${NC}"
else
echo -e "  ${BOLD}Auth${NC}         ${DIM}open (localhost dev)${NC}"
fi
if $CLI_INSTALLED; then
echo -e "  ${BOLD}CLI${NC}          ${GREEN}$CLI_PATH${NC}"
else
echo -e "  ${BOLD}CLI${NC}          ${DIM}not installed — use curl examples below${NC}"
fi
if $USE_OLLAMA; then
echo -e "  ${BOLD}Ollama${NC}       http://localhost:11434"
fi
if $USE_MONITORING; then
echo -e "  ${BOLD}Grafana${NC}      http://localhost:3000  ${DIM}(admin / nocturnusai)${NC}"
echo -e "  ${BOLD}Prometheus${NC}   http://localhost:9090"
fi
echo ""

# ── Quick start ──
echo -e "  ${BOLD}Quick start${NC}"
echo ""
if $CLI_INSTALLED; then
echo -e "    ${CYAN}# Start the REPL — just type and go${NC}"
echo "    nocturnusai"
echo ""
echo -e "    ${CYAN}# Or use one-liners${NC}"
echo "    nocturnusai -e \"tell human(socrates)\""
echo "    nocturnusai -e \"teach mortal(?x) :- human(?x)\""
echo "    nocturnusai -e \"ask mortal(?who)\""
else
echo -e "    ${CYAN}# 1. Store a fact${NC}"
echo "    curl -s http://localhost:$PORT/tell \\"
echo "      -H 'Content-Type: application/json' \\"
echo "      -H 'X-Tenant-ID: default' \\"
echo "      -d '{\"predicate\":\"human\",\"args\":[\"socrates\"]}'"
echo ""
echo -e "    ${CYAN}# 2. Teach a rule${NC}"
echo "    curl -s http://localhost:$PORT/teach \\"
echo "      -H 'Content-Type: application/json' \\"
echo "      -H 'X-Tenant-ID: default' \\"
echo "      -d '{\"head\":{\"predicate\":\"mortal\",\"args\":[\"?x\"]},\"body\":[{\"predicate\":\"human\",\"args\":[\"?x\"]}]}'"
echo ""
echo -e "    ${CYAN}# 3. Ask a question (backward chaining inference)${NC}"
echo "    curl -s http://localhost:$PORT/ask \\"
echo "      -H 'Content-Type: application/json' \\"
echo "      -H 'X-Tenant-ID: default' \\"
echo "      -d '{\"predicate\":\"mortal\",\"args\":[\"?who\"]}'"
fi
echo ""

# ── LLM-powered examples ──
if $LLM_CONFIGURED; then
echo -e "  ${BOLD}LLM-powered${NC}  ${GREEN}ready to use${NC}"
else
echo -e "  ${BOLD}LLM-powered${NC}  ${DIM}(add API key to .env, restart, then try these)${NC}"
fi
echo ""
echo -e "    ${CYAN}# Extract facts from natural language${NC}"
echo "    curl -s http://localhost:$PORT/extract \\"
echo "      -H 'Content-Type: application/json' \\"
echo "      -H 'X-Tenant-ID: default' \\"
echo "      -d '{\"text\":\"Socrates is a human. All humans are mortal.\",\"assert\":true}'"
echo ""
echo -e "    ${CYAN}# Ask a question in plain English${NC}"
echo "    curl -s http://localhost:$PORT/synthesize \\"
echo "      -H 'Content-Type: application/json' \\"
echo "      -H 'X-Tenant-ID: default' \\"
echo "      -d '{\"question\":\"Is Socrates mortal?\"}'"
echo ""

# ── Other endpoints ──
echo -e "  ${BOLD}Endpoints${NC}"
echo -e "    Health       http://localhost:$PORT/health"
echo -e "    API Docs     http://localhost:$PORT/llm.txt"
echo -e "    MCP          http://localhost:$PORT/mcp"
echo -e "    Agent Card   http://localhost:$PORT/.well-known/agent.json"
echo ""

# ── Manage ──
echo -e "  ${BOLD}Manage${NC}"
echo -e "    cd $(pwd)"
echo -e "    $COMPOSE_CMD logs -f nocturnusai   ${DIM}# tail logs${NC}"
echo -e "    $COMPOSE_CMD $PROFILE_FLAGS down  ${DIM}# stop${NC}"
echo -e "    $COMPOSE_CMD $PROFILE_FLAGS up -d ${DIM}# restart${NC}"
echo ""
echo -e "  ${BOLD}MCP config${NC} (Claude Desktop, Cursor, Windsurf, etc.):"
echo ""
echo "    {"
echo "      \"mcpServers\": {"
echo "        \"nocturnusai\": {"
echo "          \"url\": \"http://localhost:$PORT/mcp/sse\","
echo "          \"transport\": \"sse\""
echo "        }"
echo "      }"
echo "    }"
echo ""
echo -e "  ${DIM}Config: $(pwd)/.env${NC}"
echo -e "  ${DIM}Docs:   https://github.com/Auctalis/nocturnusai${NC}"
echo ""

# ── Post-install: CLI retry (interactive only) ────────────────────────────────
if [ -t 0 ] && ! $CLI_INSTALLED; then
    echo -e "${YELLOW}${BOLD}The CLI binary could not be installed automatically.${NC}"
    echo -e "${DIM}This usually means there is no pre-built binary for your platform yet.${NC}"
    echo ""

    CLI_CHOICE=$(gum_choose \
        --header "Try installing the CLI again?" \
        "Yes" \
        "No")

    if [[ "$CLI_CHOICE" == "Yes" ]]; then
        _cli_os="$(uname -s | tr '[:upper:]' '[:lower:]')"
        [[ "$_cli_os" == "darwin" ]] && _cli_os="macos"
        _cli_arch="$(uname -m)"
        [[ "$_cli_arch" == "aarch64" ]] && _cli_arch="arm64"
        _cli_url="https://github.com/Auctalis/nocturnusai/releases/latest/download/nocturnusai-${_cli_os}-${_cli_arch}"

        echo -e "${DIM}Downloading CLI...${NC}"
        if curl -fsSL "$_cli_url" -o /tmp/nocturnusai 2>/dev/null; then
            chmod +x /tmp/nocturnusai
            if /tmp/nocturnusai --help &>/dev/null; then
                if sudo mv /tmp/nocturnusai /usr/local/bin/nocturnusai 2>/dev/null; then
                    CLI_INSTALLED=true
                    CLI_PATH="/usr/local/bin/nocturnusai"
                else
                    mkdir -p "$HOME/.local/bin"
                    mv /tmp/nocturnusai "$HOME/.local/bin/nocturnusai"
                    CLI_INSTALLED=true
                    CLI_PATH="$HOME/.local/bin/nocturnusai"
                    for rc in "$HOME/.bashrc" "$HOME/.zshrc" "$HOME/.profile"; do
                        if [ -f "$rc" ] && ! grep -q '\.local/bin' "$rc"; then
                            echo 'export PATH="$HOME/.local/bin:$PATH"' >> "$rc"
                            echo -e "${DIM}  Added ~/.local/bin to PATH in $rc${NC}"
                        fi
                    done
                    export PATH="$HOME/.local/bin:$PATH"
                fi
                echo -e "${GREEN}CLI installed:${NC} $CLI_PATH"
                echo -e "${DIM}Run 'nocturnusai' to start the interactive REPL.${NC}"
            else
                rm -f /tmp/nocturnusai
                echo -e "${RED}Binary not compatible with this platform.${NC}"
            fi
        else
            echo -e "${RED}Download failed — binary not available for this platform yet.${NC}"
        fi
    else
        echo -e "${DIM}Skipped. You can install the CLI later from:${NC}"
        echo -e "${DIM}  https://github.com/Auctalis/nocturnusai/releases${NC}"
    fi
    echo ""
fi
