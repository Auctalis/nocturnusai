#!/bin/bash

# Find running process for the server (checking for the Gradle run process or the Java process)
# We look for the main class or the gradle task
PID=$(pgrep -f "nocturnusai-server:run" || pgrep -f "com.nocturnusai.server.ApplicationKt")

# Ensure a valid JAVA_HOME is set if not already
if [ -z "$JAVA_HOME" ]; then
    export JAVA_HOME=$(/usr/libexec/java_home -v 21 2>/dev/null || echo $JAVA_HOME)
fi

# Default Port if not set
export PORT=${PORT:-9300}
echo "Using JAVA_HOME: $JAVA_HOME"

if [ ! -z "$PID" ]; then
    echo "Stopping existing server instance (PID: $PID)..."
    kill $PID
    # Wait for it to exit
    while kill -0 $PID 2>/dev/null; do
        sleep 1
    done
    echo "Server stopped."
else
    echo "No existing server instance found."
fi

echo "Starting server..."
# Run in background or foreground? "Run this up" usually implies "start it".
# But if it's a script to "run", usually we want to see output.
# If I run it in background, user won't see logs. 
# Attempting to start.
./gradlew :nocturnusai-server:run
