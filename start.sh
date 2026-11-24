#!/usr/bin/env bash
# WaterBucketPE start script (Linux / macOS)
# - finds the proxy jar automatically: exact Waterbucket.jar first, then the
#   newest Waterbucket*.jar (so Waterbucket-1.5.0.jar straight from a release
#   works without renaming), then any single *.jar in this folder
# - picks Java 17+ from JAVA_HOME or PATH
# - restarts the proxy automatically after a crash; Ctrl+C stops the loop
set -u

cd "$(dirname "$0")"

# ---------- locate the jar ----------
JAR_NAME=""
if [ -f "Waterbucket.jar" ]; then
    JAR_NAME="Waterbucket.jar"
else
    for f in Waterbucket*.jar; do
        [ -e "$f" ] || continue
        if [ -z "$JAR_NAME" ] || [ "$f" -nt "$JAR_NAME" ]; then JAR_NAME="$f"; fi
    done
fi
if [ -z "$JAR_NAME" ]; then
    for f in *.jar; do
        [ -e "$f" ] || continue
        if [ -z "$JAR_NAME" ] || [ "$f" -nt "$JAR_NAME" ]; then JAR_NAME="$f"; fi
    done
fi
if [ -z "$JAR_NAME" ]; then
    echo "WaterBucketPE: no proxy jar found in this folder." >&2
    echo "Put Waterbucket.jar (or Waterbucket-<version>.jar) next to start.sh and run it again." >&2
    exit 1
fi

# ---------- locate Java 17+ ----------
JAVA_BIN="java"
if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then
    JAVA_BIN="$JAVA_HOME/bin/java"
elif ! command -v java >/dev/null 2>&1; then
    echo "WaterBucketPE requires Java 17 or newer." >&2
    echo "Install it and either put 'java' on PATH or set JAVA_HOME." >&2
    exit 1
fi

JAVA_MAJOR=$("$JAVA_BIN" -version 2>&1 | head -n1 | sed -n 's/.*"\([0-9][0-9.]*\)".*/\1/p' | cut -d. -f1)
if [ -n "$JAVA_MAJOR" ]; then
    if [ "$JAVA_MAJOR" -lt 17 ]; then
        echo "WaterBucketPE requires Java 17 or newer, found Java $JAVA_MAJOR." >&2
        echo "Set JAVA_HOME to a Java 17+ installation and try again." >&2
        exit 1
    fi
else
    echo "WaterBucketPE: could not read the Java version, trying to start anyway..." >&2
fi

# ---------- run ----------
HEAP="${WATERBUCKET_MEMORY:-1G}"

while true; do
    START_TS=$(date +%s)
    # shellcheck disable=SC2086  # WATERBUCKET_JAVA_OPTS is intentionally word-split
    "$JAVA_BIN" \
        -Xms"$HEAP" -Xmx"$HEAP" \
        -XX:+UseG1GC \
        -XX:+UnlockExperimentalVMOptions \
        -XX:MaxGCPauseMillis=120 \
        -XX:InitiatingHeapOccupancyPercent=35 \
        -XX:G1NewSizePercent=30 \
        -XX:G1MaxNewSizePercent=40 \
        -XX:G1ReservePercent=20 \
        -XX:G1HeapRegionSize=8M \
        -XX:+ParallelRefProcEnabled \
        -XX:+UseStringDeduplication \
        ${WATERBUCKET_JAVA_OPTS:-} \
        -jar "$JAR_NAME" "$@"
    STATUS=$?
    RAN_S=$(( $(date +%s) - START_TS ))
    if [ "$STATUS" -eq 0 ]; then
        echo "WaterBucketPE shut down cleanly. Restart loop stopped."
        break
    fi
    # An instant exit is a launch failure (bad JVM options, occupied port, broken
    # config) - restarting cannot fix it, so stop instead of looping on error spam.
    if [ "$RAN_S" -lt 5 ]; then
        echo "WaterBucketPE failed to start (exited after ${RAN_S}s with code $STATUS). Not restarting - fix the error above and run start.sh again." >&2
        break
    fi
    echo "WaterBucketPE exited with code $STATUS. Restarting in 3 seconds (Ctrl+C to stop)..." >&2
    sleep 3
done
