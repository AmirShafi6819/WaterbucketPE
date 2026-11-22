# WaterbucketPE Benchmarks

This folder contains the A/B benchmark harness used to measure WaterbucketPE against
upstream WaterdogPE. The numbers reported in the README are produced by this script,
run locally; everything is measured from outside the JVM so both proxy variants run
under identical conditions.

## What is measured (round 3)

| Metric | How |
| --- | --- |
| Cold startup time | Wall time from process start until the first successful RakNet unconnected ping reply on `127.0.0.1:19132`; median of 3 runs |
| Idle memory (RSS) | `VmRSS` from `/proc/<pid>/status` after a 15 s settle, no clients connected |
| Idle CPU | `(utime + stime)` delta from `/proc/<pid>/stat` over a 20 s idle window |
| Ping RTT | 1500 sequential RakNet unconnected pings, paced under the per-address packet limit; median / p95 / p99 |
| Ping throughput | 8 sockets x 100 pings; replies per second and loss |
| Handshake churn | 300 complete RakNet OpenConnectionRequest1+2 exchanges via `RakBenchClient` (the same CloudburstMC RakNet library the proxy uses, i.e. byte-for-byte what real connections perform); success rate, per-connect latency, connects/s, CPU% |
| Live sessions | 200 sessions held open; RSS delta, KB per session, CPU% over the hold window |
| Leak check | 3 churn rounds of 150 handshakes each with 4 s teardown windows; RSS growth per round. Flat or falling RSS passes |
| Scale test | 500 concurrent sessions held; success rate, KB per session, CPU% |
| Class histogram (new in round 3) | 200 held sessions vs idle baseline, JVM `SIGQUIT` histogram diff via `heap_diff.py`; see `results-histograms.txt` |

Every scenario runs against a fresh proxy process; the traffic phases share one warmed
process. Nothing is mocked: packets are real, sessions are real, and memory/CPU are read
from the kernel.

## Round-3 environment and caveats (read before comparing)

- Date: 2026-11-22. Upstream baseline: WaterdogPE at commit `ea26e46` (2.0.4).
  WaterbucketPE: jar built from this repository at the round-3 commit.
- Machine: shared 2-core Linux VM (Debian 13), Temurin JDK 17.0.21.1 for both proxies,
  identical JVM flags (`-Xms128M -Xmx512M -XX:+UseG1GC -XX:MaxGCPauseMillis=200`),
  identical `config.yml` (connection_throttle disabled so the harness measures handshake
  performance, not DoS protection).
- A shared VM makes CPU/throughput numbers noisy between runs. Numbers that were stable
  across two independent round-3 executions: idle RSS (WaterbucketPE 5-8% lower), ping
  RTT median (8-13% lower), handshake per-connect latency and handshake CPU (10-14%
  lower), leak churn growth (both small; upstream showed ~30 MB growth spikes in one
  run). Numbers that wobbled between runs: connects/s (within ±1.5%), sessions CPU,
  and especially the **held-session RSS delta** (KB/session), which is a page-level
  allocator metric.
- Because of that noise, the held-session RSS delta is reported as measured but is NOT
  presented as a differentiator. The object-level evidence is the class-histogram diff
  in `results-histograms.txt`: per-session instance counts are equal or lower for
  WaterbucketPE than upstream (fewer MPSC queues, less PoolThreadCache, fewer Object[]
  arrays), with no per-session object type growing materially.
- The round-2 "+0.5 s cold start" trade-off from the embedded Python jars did not
  reproduce in round 3: startup is equal (2.22 s vs 2.22 s medians).

## How to reproduce

```bash
# 1. Build both jars
git clone https://github.com/WaterdogPE/WaterdogPE waterdog-baseline
(cd waterdog-baseline && git checkout ea26e46 && ./gradlew build -x test)
./gradlew build -x test

# 2. Run the harness (Java 17 toolchain expected at $BENCH_JAVA)
python3 benchmarks/bench.py waterdog-baseline/build/libs/Waterdog.jar WaterdogPE \
        build/libs/Waterbucket.jar WaterbucketPE results.json

# 3. Optional: per-session class histogram diff (SIGQUIT histogram, ~2 min)
python3 benchmarks/heap_diff.py build/libs/Waterbucket.jar WaterbucketPE 200

# 4. Regenerate the chart from results.json
python3 benchmarks/gen_chart.py
```

Raw repeat data is kept in `results.json`; the chart in `benchmark.png` renders medians
and labels every trade-off, including the slower ones. Round-3 results (2026-11-22)
live in `results.json` and `results-histograms.txt`.

## Phase-2 A/B (1.2.0 -> 1.3.0, 2026-11-22)

Phase 2's own before/after run compares **two builds of this repository** - the pre-Phase-2
jar at commit `59c244f` (sha256 `8a3ff4434e208c246a3ac130aa66dd42d7520ec16611426eae8c29db3da82ec7`)
against the post-Phase-2 jar built from the Phase 2 commits - through the same harness
phases, same JVM flags, same VM (`benchmarks/results-phase2.json`, chart
`benchmark-phase2.png` from `gen_phase2_chart.py`).

What changed and what did not (medians; raw repeat data attached to every metric):

- **Functionally identical**: 300/300 handshakes, 200/200 and 500/500 held sessions, zero
  ping loss on both sides. The Phase 2 fixes are compatibility-neutral by design.
- **Ping RTT** median 0.186 -> 0.176 ms (−5.3%), p95 −2.4%, p99 −7.0% (single execution
  each; direction consistent with the allocation reduction in the per-packet decode path).
- **Handshake churn** parity: median connect 2.64 -> 2.69 ms, p95 25.2 -> 23.8 ms,
  connects/s 69.0 -> 69.4.
- **No stable memory change**: idle RSS 206.6 -> 207.2 MB (page-level noise); the held-session
  kB/session metric wobbled across its two executions on both sides (pre 45.5-63.5, post
  42.1-75.7) exactly like in round 3, so it is reported but not presented as a differentiator.
- **CPU numbers wobbled on the shared 2-core VM** as in round 3: handshake CPU sampled
  43.9-48.7% (pre) vs 46.2-55.8% (post) across two executions - overlapping ranges, no
  stable difference; 200-session CPU 37.2 -> 35.7%.
- **Cold startup** stayed inside its observed 1.7-2.8 s bimodal band on both sides (pre
  median 2.23 s, post 2.79 s across four runs each; the band matches the 1.2.0 measurement
  of 2.22 s, so no startup regression was demonstrated, but the small sample cannot prove
  parity either).
- The per-session class histogram (`results-histograms.txt`) was not re-run for this A/B;
  the round-3 evidence remains the current object-level baseline. The Phase 2 allocation
  changes (header parse without a temp slice, bounded client packet queue, COW handler
  list) affect object churn, which the histogram would show only marginally.
- **Transfer-path latency** (the 50 ms fixed drain replaced by a ~5 ms adaptive drain) is
  covered by `ClientPacketQueueTest` on an EmbeddedChannel with virtual time; the harness
  does not drive Bedrock-level logins, so it is documented as a design improvement with a
  test, not as a harness-measured win.

To reproduce: build both jars (pre-Phase-2 from `git checkout 59c244f`, post from the
release tag), then run the harness as described above for each jar, or run
`python3 benchmarks/bench.py <pre.jar> Pre <post.jar> Post out.json` for the paired run.

## Bedrock-level A/B (2026-11-22, closes the transfer-path gap)

The Phase-2 A/B above left one path unmeasured: everything past the RakNet handshake.
`BedrockBench.java` + `bench_bedrock.py` close that gap with real Bedrock sessions -
built on the same CloudburstMC Protocol / RakNet libraries the proxy uses, so logins and
transfers are byte-for-byte what real clients and servers perform:

- **login** - sequential full logins (RakNet connect -> network settings -> self-signed
  JWT login -> encryption handshake -> resource packs -> StartGame), measuring
  connect->StartGame latency per client.
- **rtt** - one login, then sequential TextPacket round-trips through proxy and backend.
- **switch** - one login, then alternating fast transfers A->B->A...; the fake backend
  sends a `TransferPacket`, the proxy fast-transfers (dials the other fake backend,
  completes the full downstream login), and the client completes the synthetic
  dimension-change round trip. The client-visible completion point is the second
  `ChangeDimensionPacket` - the fast-transfer path consumes the new server's StartGame
  as rewrite input and does not deliver a second StartGame to the client.

Two fake backends (one per registered server) live in the same tool JVM as the clients;
`HandshakeUtils`-style encryption, the resource-pack sequence and the StartGame packet
mirror what BDS sends. Everything else follows the bench.py conventions: same shared VM,
same JVM flags, same config for both sides, both jars built from this repository
(`59c244f` = pre, master = post), and the tool is byte-compatible with both.

Results (`results-bedrock.json`, chart `bench_bedrock_chart.png`; medians over two
interleaved rounds per side, 30 logins / 300 round-trips / 30 switches per round):

| Scenario | Pre (v1.2.0) median | Post (1.3.0) median | Delta |
|---|---|---|---|
| Login -> StartGame | 339-343 ms | 178-188 ms | **-46%** |
| In-game text RTT | 20.1-29.9 ms (bimodal) | 20.0-28.9 ms (bimodal) | equal |
| Fast transfer | 290.0-290.3 ms | 119.8-119.9 ms | **-59%** |
| Fast transfer p95 | 315-316 ms | 145-160 ms | **-52%** |
| Login p95 | 747-772 ms | 663-691 ms | -11% |

- **Functional parity on every scenario**: 30/30 logins, 300/300 round-trips and 31/31
  switches (login + switches) on both sides, both rounds.
- The **fast-transfer** and **login** gains are exactly the paths the Phase-2 A/B could
  not measure: both are dominated by proxy-generated packet batches whose drain was a
  fixed 50 ms timer in v1.2.0 (`ClientPacketQueue`), and the transfer additionally waits
  for the downstream login. The adaptive ~5 ms drain (C9) removes that latency; the
  measured deltas match the baseline report's prediction ("hundreds of milliseconds
  across a transfer").
- The **text RTT** is bimodal on both sides (two round-trip paths: batches that catch a
  queue drain vs batches that pass untouched) and shows no stable difference - the text
  relay takes the untouched-batch fast path, which Phase 2 did not change.
- One harness fix mattered: `login_throttle` defaults to 2 logins per interval, so the
  bench config disables it (`login_throttle: -1`) alongside `connection_throttle: -1`;
  otherwise sequential logins from one address fail by design (29 of 30 in the first
  attempt), on both sides alike.

To reproduce:

```bash
javac -cp <proxy.jar> -d /tmp/wbtest8 benchmarks/BedrockBench.java
python3 benchmarks/bench_bedrock.py <proxy.jar> out.json            # all three scenarios
python3 benchmarks/gen_bedrock_chart.py                             # merge + chart
```
