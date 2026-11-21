#!/usr/bin/env bash
# WaterbucketPE start script (Linux / macOS)
# Starts the proxy and restarts it automatically if it exits, so a crash or
# restart command keeps the process available.
set -u

cd "$(dirname "$0")"

JAR_NAME="Waterbucket.jar"
JAVA_BIN="java"

# Prefer JAVA_HOME when it is set
if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then
    JAVA_BIN="$JAVA_HOME/bin/java"
fi

# The proxy needs Java 17 or newer
if [ "$("$JAVA_BIN" -version 2>&1 | head -1 | cut -d'"' -f2 | cut -d. -f1)" -lt 17 ] 2>/dev/null; then
    echo "WaterbucketPE requires Java 17 or newer. Please install it and set JAVA_HOME." >&2
    exit 1
fi

while true; do
    "$JAVA_BIN" \
        -Xms512M -Xmx1G \
        -XX:+UseG1GC \
        -XX:MaxGCPauseMillis=200 \
        -XX:G1NewSizePercent=30 \
        -XX:G1MaxNewSizePercent=40 \
        -XX:G1ReservePercent=20 \
        -XX:G1HeapRegionSize=8M \
        -XX:+ParallelRefProcEnabled \
        -XX:+UseStringDeduplication \
        -jar "$JAR_NAME" nogui
    STATUS=$?
    if [ "$STATUS" -eq 0 ]; then
        echo "WaterbucketPE shut down cleanly. Restart loop stopped."
        break
    fi
    echo "WaterbucketPE exited with code $STATUS. Restarting in 3 seconds (Ctrl+C to stop)..." >&2
    sleep 3
done
