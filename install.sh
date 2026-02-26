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
SUDO=""
if [ -w "/usr/local/bin" ]; then
    install_path="/usr/local/bin/nocturnusai"
elif sudo -n true 2>/dev/null; then
    install_path="/usr/local/bin/nocturnusai"
    SUDO="sudo"
else
    mkdir -p "$HOME/.local/bin"
    install_path="$HOME/.local/bin/nocturnusai"
fi

# ── Download CLI binary ────────────────────────────────────────────────────
echo -e "Downloading ${BOLD}${binary}${NC}..."
tmp_path=$(mktemp)

if ! curl -fsSL "$url" -o "$tmp_path" 2>/dev/null; then
    rm -f "$tmp_path"
    echo -e "${RED}${BOLD}Download failed.${NC} No binary for ${os}/${arch}."
    echo ""
    echo -e "Install from source instead:"
    echo -e "  git clone https://github.com/Auctalis/nocturnusai.git"
    echo -e "  cd nocturnusai && docker compose up --build -d"
    exit 1
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
        echo -e "${RED}Binary not compatible with this platform.${NC}"
        exit 1
    }
else
    # Still running after 2s (old build with --help bug) — kill it, it's fine
    kill "$_pid" 2>/dev/null; wait "$_pid" 2>/dev/null || true
fi

# Move to install path
if [ -n "$SUDO" ]; then
    $SUDO mv "$tmp_path" "$install_path"
else
    mv "$tmp_path" "$install_path"
fi

echo -e "${GREEN}CLI installed:${NC} $install_path"

# ── Add ~/.local/bin to PATH if needed ──────────────────────────────────────
if [[ "$install_path" == *".local/bin"* ]]; then
    for rc in "$HOME/.bashrc" "$HOME/.zshrc" "$HOME/.profile"; do
        if [ -f "$rc" ] && ! grep -q '\.local/bin' "$rc"; then
            echo 'export PATH="$HOME/.local/bin:$PATH"' >> "$rc"
            echo -e "${DIM}  Added ~/.local/bin to PATH in $rc${NC}"
        fi
    done
    export PATH="$HOME/.local/bin:$PATH"
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
