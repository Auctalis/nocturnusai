#!/bin/bash

# Configuration
API_PORT=9300

# Colors
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[0;33m'
NC='\033[0m'

# Kill any process using the specified port
kill_port() {
    local port=$1
    local pid=$(lsof -ti :$port)
    if [ -n "$pid" ]; then
        echo -e "${YELLOW}Killing existing process on port $port (PID: $pid)${NC}"
        kill -9 $pid 2>/dev/null
        sleep 1
    fi
}

cleanup() {
    echo -e "${RED}Stopping processes...${NC}"
    kill $(jobs -p) 2>/dev/null
    exit
}

trap cleanup SIGINT SIGTERM

echo -e "${GREEN}=== Starting NocturnusAI ===${NC}"

# Check for Java
if ! command -v java &> /dev/null; then
    echo -e "${RED}Java is not installed.${NC}"
    exit 1
fi

# Kill any conflicting processes
kill_port $API_PORT

# Load local overrides when present, otherwise fall back to example defaults.
ENV_SOURCE=""
if [ -f .env ]; then
    ENV_SOURCE=".env"
elif [ -f .env.example ]; then
    ENV_SOURCE=".env.example"
fi
if [ -n "$ENV_SOURCE" ]; then
    set -a
    source "$ENV_SOURCE"
    set +a
    echo -e "${GREEN}Loaded ${ENV_SOURCE} config${NC}"
fi

# Docker-friendly Ollama URLs do not resolve when the server runs directly on the host.
if [ "${LLM_PROVIDER}" = "ollama" ]; then
    if [ -z "${LLM_BASE_URL}" ] || [[ "${LLM_BASE_URL}" == "http://host.docker.internal:11434/v1" ]] || [[ "${LLM_BASE_URL}" == "http://ollama:11434/v1" ]]; then
        export LLM_BASE_URL="http://localhost:11434/v1"
        echo -e "${GREEN}Using local Ollama at ${LLM_BASE_URL}${NC}"
    fi
fi

# Start Server
echo -e "${GREEN}Starting NocturnusAI Server on port $API_PORT...${NC}"
./gradlew :nocturnusai-server:run --console=plain &
SERVER_PID=$!

echo -e "${GREEN}Server running with PID $SERVER_PID${NC}"
echo -e "${GREEN}API at http://localhost:$API_PORT${NC}"
echo -e "${GREEN}CLI: ./gradlew :nocturnusai-cli:run --args='--server http://localhost:$API_PORT --db default'${NC}"

wait
