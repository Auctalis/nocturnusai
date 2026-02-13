#!/bin/bash

# Configuration
API_PORT=9300
WEB_PORT=9350

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

echo -e "${GREEN}=== Starting Local Development Environment ===${NC}"

# Check for Java
if ! command -v java &> /dev/null; then
    echo -e "${RED}Java is not installed.${NC}"
    exit 1
fi

# Check for Node
if ! command -v npm &> /dev/null; then
    echo -e "${RED}npm is not installed.${NC}"
    exit 1
fi

# Kill any conflicting processes
kill_port $API_PORT
kill_port $WEB_PORT

# Load .env if present
if [ -f .env ]; then
    set -a
    source .env
    set +a
    echo -e "${GREEN}Loaded .env config${NC}"
fi

# Start Server
echo -e "${GREEN}Starting Gradle Server...${NC}"
./gradlew :axiombase-server:run --console=plain &
SERVER_PID=$!

# Start Web
echo -e "${GREEN}Starting Web Client...${NC}"
cd axiombase-web
npm install # Ensure dependencies are installed
npm run dev -- --port $WEB_PORT &
WEB_PID=$!
cd ..

echo -e "${GREEN}Server running with PID $SERVER_PID${NC}"
echo -e "${GREEN}Web running with PID $WEB_PID${NC}"
echo -e "${GREEN}Access Web at http://localhost:$WEB_PORT${NC}"
echo -e "${GREEN}Access Server at http://localhost:$API_PORT${NC}"

wait
