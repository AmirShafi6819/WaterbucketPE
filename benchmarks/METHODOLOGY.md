# WaterbucketPE Benchmarks

This folder contains the A/B benchmark harness used to measure WaterbucketPE against
upstream WaterdogPE. The numbers reported in the README are produced by this script,
run locally; everything is measured from outside the JVM so both proxy variants run
under identical conditions.

## What is measured

| Metric | How |
| --- | --- |
| Cold startup time | Wall time from process start until the first successful RakNet unconnected ping reply on `127.0.0.1:19132` |
| Idle memory (RSS) | `VmRSS` from `/proc/<pid>/status` after a 20 s warm-up, no clients connected |
| Idle CPU | `(utime + stime)` delta from `/proc/<pid>/stat` over a 30 s idle window |
| Ping RTT | 2000 sequential RakNet unconnected pings from one socket; median / p95 / p99 |
| Ping throughput | 8 sockets x 500 pings; replies per second and loss |
| Session handshakes | Complete RakNet OpenConnectionRequest1+2 exchanges from 250 unique source ports; success rate and per-handshake RTT. Each success allocates a full server-side session through the Bedrock pipeline |
| CPU / ops under load | 4 threads pinging + 100 handshakes in one window; CPU percent and operations per second |

## How to reproduce

```bash
# 1. Build both jars
git clone https://github.com/WaterdogPE/WaterdogPE waterdog-baseline
(cd waterdog-baseline && git checkout <commit> && ./gradlew build -x test)
./gradlew build -x test

# 2. Run the harness (Java 17 toolchain expected at $BENCH_JAVA)
python3 benchmarks/bench.py waterdog-baseline/build/libs/Waterdog.jar WaterdogPE \
        build/libs/Waterbucket.jar WaterbucketPE results.json
```

Both variants run with identical JVM flags (`-Xms128M -Xmx512M -XX:+UseG1GC
-XX:MaxGCPauseMillis=200`), on the same machine, sequentially, with the default
`config.yml` each build generates on first start. Repeat runs are kept raw in the
results JSON, and the README chart shows medians of 5 runs per startup/memory metric.
