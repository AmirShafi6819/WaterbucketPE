# WaterbucketPE Benchmarks

This folder contains the A/B benchmark harness used to measure WaterbucketPE against
upstream WaterdogPE. The numbers reported in the README are produced by this script,
run locally; everything is measured from outside the JVM so both proxy variants run
under identical conditions.

## What is measured (round 2)

| Metric | How |
| --- | --- |
| Cold startup time | Wall time from process start until the first successful RakNet unconnected ping reply on `127.0.0.1:19132`; median of 3 runs |
| Idle memory (RSS) | `VmRSS` from `/proc/<pid>/status` after a 15 s settle, no clients connected |
| Idle CPU | `(utime + stime)` delta from `/proc/<pid>/stat` over a 20 s idle window |
| Ping RTT | 1500 sequential RakNet unconnected pings, paced under the per-address packet limit; median / p95 / p99 |
| Ping throughput | 8 sockets x 100 pings; replies per second and loss |
| Handshake churn | 300 complete RakNet OpenConnectionRequest1+2 exchanges via `RakBenchClient` (the same CloudburstMC RakNet library the proxy uses, i.e. byte-for-byte what real connections perform); success rate, per-connect latency, connects/s, CPU% |
| Live sessions | 200 sessions held open; RSS delta, KB per session, CPU% over the hold window |
| Leak check (new) | 3 churn rounds of 150 handshakes each with 4 s teardown windows; RSS growth per round. Flat or falling RSS passes |
| Scale test (new) | 500 concurrent sessions held; success rate, KB per session, CPU% |

Every scenario runs against a fresh proxy process; the traffic phases share one warmed
process. Nothing is mocked: packets are real, sessions are real, and memory/CPU are read
from the kernel.

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
-XX:MaxGCPauseMillis=200`), on the same machine, sequentially, with an identical
`config.yml` (connection_throttle disabled so the harness measures handshake
performance, not DoS protection). Raw repeat data is kept in `results.json`; the README
chart renders medians and labels every trade-off, including the slower ones.

Round-2 results (2026-11-21) live in `results.json`, the chart in `benchmark.png`,
regenerable via `python3 benchmarks/gen_chart.py`.
