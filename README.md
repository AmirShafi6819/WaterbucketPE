<h1 align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset=".github/banner.png">
    <img alt="WaterbucketPE" src=".github/banner.png">
  </picture>
</h1>

<p align="center">
  <a href="https://github.com/AmirShafi6819/WaterbucketPE/releases"><img alt="Release" src="https://img.shields.io/github/v/release/AmirShafi6819/WaterbucketPE?color=1BA9E0&label=release"></a>
  <a href="LICENSE"><img alt="License" src="https://img.shields.io/badge/license-GPL%20v2-blue"></a>
  <a href="https://adoptium.net/"><img alt="Java" src="https://img.shields.io/badge/Java-17%2B-orange"></a>
  <a href="https://github.com/AmirShafi6819/WaterbucketPE/actions"><img alt="CI" src="https://img.shields.io/github/actions/workflow/status/AmirShafi6819/WaterbucketPE/build.yml?branch=master&label=build"></a>
  <a href="https://github.com/WaterdogPE/WaterdogPE"><img alt="Upstream" src="https://img.shields.io/badge/upstream-WaterdogPE-5B6B8C"></a>
</p>

**WaterbucketPE** is a drop-in replacement for [WaterdogPE](https://github.com/WaterdogPE/WaterdogPE), the
Minecraft: Bedrock Edition proxy. Same protocol behavior, same plugin API, same configs — with measurably
lower memory, a built-in web panel, an embedded **Python plugin runtime** next to the classic Java one, and
hardening for every real bug class we could reproduce.

> Every number on this page comes from the reproducible harness in
> [`benchmarks/`](benchmarks) (real UDP pings, real RakNet handshakes, `/proc` measurements).
> No synthetic values, no cherry-picking: slower results are shown too.

---

## Why switch from WaterdogPE?

Round-3 A/B benchmark (2026-11-22, shared 2-core VM, same upstream commit, same JVM flags —
methodology and caveats in [benchmarks/METHODOLOGY.md](benchmarks/METHODOLOGY.md)):

| | WaterdogPE 2.0.4 | WaterbucketPE 1.2.0 |
|---|---|---|
| Idle memory (RSS) | 202 MB | **190 MB (−6%)** |
| RakNet ping RTT (median / p95) | 0.22 / 0.61 ms | **0.21 / 0.59 ms** |
| Handshake latency (median, 300 real connects) | 2.89 ms | **2.72 ms** |
| Handshake CPU during churn | 58% | **50% (−14%)** |
| Handshake throughput | 66.0/s | 65.8/s (parity, ±1.5% run noise) |
| Cold startup (median of 3) | 2.22 s | 2.22 s (parity) |
| 500 concurrent sessions | 498 held | **500 held** |
| Leak churn growth (3 rounds) | up to +0.46 MB/round | ≤ +0.39 MB/round |
| Web panel | ✗ | **built-in, token-secured** |
| Python plugins | ✗ | **GraalPy, zero-config** |
| Localhost multi-port transfers | can silently bypass the proxy | **fixed** |
| Ghost-player leak (upstream #266) | leak possible | **sweeper + tests** |

Per-session memory: the held-session RSS delta is a noisy page-level metric (it moved in both
directions between runs), so we do not headline it. The object-level evidence — a 200-session
class-histogram diff kept in [`benchmarks/results-histograms.txt`](benchmarks/results-histograms.txt) —
shows equal-or-lower per-session instance counts than upstream (fewer MPSC queues, less
PoolThreadCache, fewer Object[] arrays).

**Compatibility promise:** existing WaterdogPE jar plugins run unchanged; configs, commands,
transfers, forwarding and event API semantics are preserved. The Python front-end is purely additive.

---

## Highlights

### Built-in web panel

The proxy serves its own dashboard (`enable_panel` in the config, loopback-only by default,
token-secured): live charts, players, downstream servers with real pings, and a console that
runs proxy commands.

| Dashboard | Players |
|---|---|
| ![Dashboard](docs/screenshots/panel-dashboard.png) | ![Players](docs/screenshots/panel-players.png) |
| **Servers** | **Console** |
| ![Servers](docs/screenshots/panel-servers.png) | ![Console](docs/screenshots/panel-console.png) |

- token auto-generated to `panel_token.txt` (owner-only permissions, never logged)
- per-IP rate limiting, request size caps, `nosniff`/`DENY`/`no-referrer` headers
- 36-minute in-memory history, sampled every 5 s — nothing touches the disk

### Plugins in Java *and* Python

The classic jar API is untouched; Python plugins are plain folders next to your jars:

```yaml
# plugins/HelloPy/waterbucket.yml
name: HelloPy
main: main.py
```

```python
def on_enable(plugin):
    PlayerChatEvent = java.type("dev.waterbucket.waterbucketpe.event.defaults.PlayerChatEvent")
    plugin.subscribe(PlayerChatEvent, lambda event: logger.info(
        "{} says {}", event.getPlayer().getName(), event.getMessage()))
    plugin.register_command("pyhello", "Greets from Python", "/pyhello", None, None,
                            lambda sender, alias, args: (sender.sendMessage("hello!"), True)[1])
```

Full documentation for both languages, including a facade reference and troubleshooting,
is in [docs/PLUGINS.md](docs/PLUGINS.md) — with runnable examples under
[examples/plugins](examples/plugins).

### Performance work that actually moved the numbers

- **Per-leg zlib levels** — upstream left `downstream_compression` dead code; both legs now
  honor their configured level (defaults 6 upstream / 2 downstream), removing most zlib CPU
  spent re-compressing relayed batches.
- **Flush consolidation on both legs** — the relay wrote one syscall per batch; bursts now
  collapse into one flush per read cycle, the same approach Velocity uses.
- **Right-sized event loops** — core-count worker loops instead of Netty's 2x, since the
  RakNet listener already binds one channel per core (`SO_REUSEPORT`).
- **O(1) player lookups** — case-insensitive name index instead of a full scan with per-entry
  allocations.
- **Release-mode leak detection actually off** — upstream's raise-only guard kept every
  release build at `SIMPLE` tracking; the largest single per-session memory win.
- **Phase 2 (1.3.0)** — per-packet header parse without a temporary slice, adaptive
  ~5 ms drain for proxy-generated packets (was a fixed 50 ms tick), bounded client packet
  queue, and a sweep of cross-loop visibility fixes through the relay and rewrite layers.
  The before/after harness run of the two builds of this repository
  ([methodology](benchmarks/METHODOLOGY.md), [raw data](benchmarks/results-phase2.json)):
  functionally identical (300/300 handshakes, 200/200 and 500/500 held sessions, zero
  ping loss), ping RTT median −5.3% / p99 −7.0%, handshake p95 −5.7%, memory and CPU
  within the shared-VM noise band — no stable regression anywhere.

### Real bug fixes (found upstream or in review, fixed here)

- **Cross-loop visibility (Phase 2)** — rewrite state (entity ids, dimension, spawn
  position, gamerules, palette rewrite), the downstream compression strategy and the
  batch bridge handler are handed between the client and server event loops with no
  other synchronization; all of them are memory-visible now, locked by a contract test.
- **Stale initial StartGame (Phase 2)** — the first-join handler applied a StartGame from
  a dial whose fallback had already begun, hijacking the player mid-recovery; it mirrors
  the transfer guards now.
- **Fast-transfer address matching** — localhost setups running several downstreams on
  `127.0.0.1` with different ports could silently fail the fast path (the raw `TransferPacket`
  reached the client and pointed players at their own machine). Matching is now non-blocking
  (no reverse DNS on event loops) and loopback aliases always resolve to their own port.
  See [ServerInfoAddressMatchTest](src/test/java/dev/waterbucket/waterbucketpe/network/serverinfo/ServerInfoAddressMatchTest.java).
- **Event pipeline thread safety** — events fire from every Netty loop while plugins subscribe;
  the old fastutil/EnumMap structures could corrupt entries or throw
  `ConcurrentModificationException`. Covered by [EventManagerConcurrencyTest](src/test/java/dev/waterbucket/waterbucketpe/event/EventManagerConcurrencyTest.java).
- **Ghost players (upstream #266)** — a lost disconnect event kept the identity registered
  forever and blocked reconnects; a periodic sweeper evicts only stale instances.
- **Shutdown teardown** — a panel `end` command used to interrupt its own shutdown halfway
  (interfaces stayed bound); the event executor shut down before the network and turned late
  disconnect events into `RejectedExecutionException`; a failed start exited with code 0.
- **Scheduler finalization** — completing a `CallbackTask` fired every callback a second time
  with `null` from the tick thread (the scheduler called `cancel()` on finished tasks).
- **Python/Java handler parity** — a throwing Python event handler failed the completable
  event future and kicked players; Java handlers were only logged. Both are logged and skipped
  now, and disabling a Python plugin detaches its subscriptions before the interpreter closes.
- **Plugin loading** — dependency cycles longer than two crashed boot with `StackOverflowError`;
  failed enables left plugins enabled-but-dead; `getResourceFile` leaked a `JarFile` per call;
  duplicate plugin names kept shadow classloaders; descriptor names could escape the plugins
  directory.
- **Transfer queue overflow** — batches rejected by the MPSC queue leaked their pooled buffers
  and their write promises never completed.
- **Legacy chunk rewriter** — a failed rewrite released the packet's own buffer (latent
  use-after-free), covered by [BlockMapOwnershipTest](src/test/java/dev/waterbucket/waterbucketpe/network/protocol/rewrite/BlockMapOwnershipTest.java).
- **Connection throttle** — concurrent first-hits from the per-core RakNet loops undercounted
  the per-IP limit exactly during floods (get-then-put race).
- **Query handler** stopped doing reverse DNS on the event loop for every response.
- Malformed transfer packets can no longer throw out of the packet handler and kill sessions.
- `sendPopup(message, subtitle)` no longer drops the subtitle.

---

## Benchmarks

![Benchmark](benchmarks/benchmark.png)

- Idle RSS **−6%**, ping RTT median **−8%**, handshake CPU **−14%**, equal startup, all 500
  concurrent sessions held (upstream held 498 in the same run).
- Every metric's methodology, environment, repeat counts and honest caveats (including which
  numbers are noisy) are in [benchmarks/METHODOLOGY.md](benchmarks/METHODOLOGY.md); raw data in
  [`benchmarks/results.json`](benchmarks/results.json), per-session histogram evidence in
  [`benchmarks/results-histograms.txt`](benchmarks/results-histograms.txt).

## Quick start

**Requirements:** Java 17+ (Temurin recommended). Players connect with any Bedrock client ≥ the
latest supported game version.

```bash
# Linux
java -jar Waterbucket.jar
# or the provided script
./start.sh
```

```bat
:: Windows - the release ships a native launcher
Waterbucket.exe
```

The first start writes `configs/config.txt` (servers, priorities, compression, panel), prints
the panel token location, and binds RakNet on `19132`. Point your Bedrock clients at the proxy
and register downstream servers under `servers:`.

Docker images are published from the workflow for every tagged release.

## Configuration highlights

| Option | Default | Purpose |
|---|---|---|
| `enable_panel` | `true` | serve the web dashboard from the proxy |
| `panel_bind_address` | `127.0.0.1` | keep loopback unless you front it with TLS |
| `panel_port` | `8080` | `0` picks a free port |
| `panel_token` | *(auto)* | API token; generated to `panel_token.txt` |
| `upstream_compression_level` | `6` | zlib level proxy → client |
| `downstream_compression_level` | `2` | zlib level proxy → servers (now actually applied) |
| `prefer_fast_transfer` | `true` | intercept downstream `TransferPacket`s into fast transfers |

## Documentation

- [Plugin API — Java & Python](docs/PLUGINS.md)
- [Benchmark methodology](benchmarks/METHODOLOGY.md)
- [Phase 2 baseline report](docs/PHASE2_BASELINE.md) · [Phase 2 final report](docs/PHASE2_FINAL_REPORT.md)
- [Web panel](docs/PLUGINS.md) (API reference) · config comments in `configs/config.txt`
- [Contributing](CONTRIBUTING.md) · [Security policy](SECURITY.md) · [Changelog](CHANGELOG.md)

## Building from source

```bash
./gradlew build          # tests + shaded jar in build/libs/Waterbucket.jar
./gradlew test           # full test suite
```

## Contributing

Issues and PRs are welcome — read [CONTRIBUTING.md](CONTRIBUTING.md) first. Performance PRs
must include benchmark numbers from the harness in `benchmarks/`.

## Acknowledgments

Built on [WaterdogPE](https://github.com/WaterdogPE/WaterdogPE) and the
CloudburstMC protocol stack. Flush-consolidation tuning follows
[Velocity](https://github.com/PaperMC/Velocity)'s published pipeline approach.

## License

[GNU GPL v2](LICENSE) — inherited from upstream WaterdogPE.
