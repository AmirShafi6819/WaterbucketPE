# Changelog

All notable changes are documented here. Format based on Keep a Changelog,
versioning follows [semantic versioning](https://semver.org/).

## [1.4.1] - 2026-11-24

The performance release. A post-1.4.0 audit pass with eight regression-tested
bug fixes, two measured headless-efficiency optimizations, and a re-measured
WaterdogPE comparison whose harness bugs (stdin EOF console spin, JIT
compilation races inside sampled windows) were fixed for both proxies alike.

### Fixed
- **Block-definition registry race** — both legs of one player share the codec
  helper, so `UpdateBlockPacket` decoding called `getDefinition` from two event
  loops concurrently and mutated a plain fastutil map: resize race, corrupted
  or lost block definitions, session kills. Lookups are synchronized.
- **One bad packet no longer kills the peer** — a `HANDLED` packet whose
  re-encode failed carried a null buffer; `encodedLength`/concat threw and
  `exceptionCaught` disconnected the whole peer and IP-blocked the client.
  Unencodable wrappers are dropped from the batch instead.
- **Truncated chunk palettes degrade to pass-through** — a chunk payload cut
  mid-VarInt inside the palette section threw out of the packet handler and
  killed the session; it now degrades exactly like the words-truncation guard.
- **Resource-pack hot-reload visibility** — the pack maps and their packets
  were swapped by reload/plugin threads with no happens-before edge, so a join
  could pair new pack maps with stale packets and be disconnected. Volatile.
- **Pack registration snapshot-swap race** — concurrent register/unregister
  could swap from stale snapshots (pack lost with its ZipFile open, closed pack
  resurrected). Serialized by a swap lock around the swap sections only.
- **Config hot-reload visibility** — `proxyConfig`/`langConfig` swapped from
  the reload path without volatile while every event loop reads them. Volatile.
- **Duplicate dial on connect** — the `pendingServers` contains->add pair was
  not atomic across event loops/panel executors; duplicates dialled twice and
  a leftover entry blocked later connects with a permanent "connecting".
- **Python plugin disable race** — `subscribe`/`registerCommand`/`schedule*`
  racing `onDisable` could leave a registration attached past interpreter
  close; serialized by a lifecycle lock that never runs plugin callbacks.

### Changed
- **Headless console spin eliminated** — with a dumb terminal (docker, systemd,
  nohup, CI) JLine's `readLine` returns null immediately and the console thread
  looped at full speed flooding the log with `> ` prompts. The console loop now
  starts only when the process owns an interactive terminal; commands still
  come from the web panel and plugins. Idle CPU −23% on the same host.
- **Netty pooled allocator right-sized before Netty classes load** — direct
  arenas 2×cores → cores (each arena owns 16 MB chunks; on a 2-core host that
  halves the pre-committed direct memory) and per-thread caches trimmed so
  freed buffers return to the pool instead of parking per event loop.

### Measured
- **WaterdogPE comparison re-measured, no panel favors upstream anymore**
  (same 2-core VM, identical flags, fixed harness with open stdin, JIT
  warm-up, pooled handshakes and median-of-three held-session rounds):
  idle memory −10.5% (216.6 → 193.8 MB), cold startup −39.7%, ping RTT median
  −3.3%, handshake CPU −12.6%, marginal RSS per held session −51.6% at 200
  sessions and −13.4% at 500, CPU during the 500-session hold −3.0%, churn
  growth −59.5%. Idle CPU, ping RTT p95 and ping throughput measured as exact
  ties and are reported as parity. All 200 and all 500 sessions held, zero
  ping loss on both sides. Chart: `.github/benchmark-comparison.png`.

### Verification
- 59 test suites, **336 tests, 0 failures** (1 skipped), JDK 17
- Handshake churn 898/900 · held sessions 200/200 and 500/500 · ping loss 0.0%

## [1.4.0] - 2026-11-22

The final engineering pass. Two full rounds since 1.3.0 — a plugin-engine
audit-and-hardening round (Phase 3 + Phase 5, 13 commits) and a proxy-wide
stability hardening round (14 commits) — plus the final-phase benchmarking
and scale-validation tooling. Everything is evidence-backed: 328 tests green
on the release build, and the WaterdogPE comparison was re-measured on the
same VM with the same harness and JVM flags.

### Added
- **Progressive scale-validation harness** (`benchmarks/bench_scale.py`):
  drives cumulative hold stages (50 -> 600 sessions, one hold-forever
  `RakBenchClient` per delta) sampling RSS/session, CPU and paced
  unconnected-ping RTT, with a 5-loss safety gate that aborts instead of
  reporting unobserved capacity. `RakBenchClient` gained `holdSeconds < 0`
  (hold until killed) for cumulative populations. The partial run in this
  environment is recorded verbatim (`results-scale.json`); the 500-session
  milestone remains covered by `bench_final.py` (`scale_held=500`).
- **WaterdogPE comparison, like-for-like** (`benchmarks/results.json`): same
  VM, same harness, same flags — startup, idle RSS/CPU, ping RTT, handshake
  churn, 200 held sessions, ~500 held sessions, churn-leak probes on both
  proxies. Full context and caveats in `benchmarks/METHODOLOGY.md`.
- **Final engineering report** (`docs/FINAL_ENGINEERING_REPORT.md`): the
  whole project — architecture, method, numbers, comparison verdict,
  limitations — in one place, with the Gate A-F verification matrix.
- Bedrock-level A/B benchmark for transfers (`BedrockBench.java`,
  `bench_bedrock.py`): fast transfers measured end-to-end, not inferred.
- Bounded, portable final-phase driver (`bench_final.py`): no hardcoded JDK
  paths (`BENCH_JAVA` selects the runtime).

### Fixed (plugin engine, Phase 3 + 5)
- Descriptor parsing hardened and plugin file boundaries enforced (Stage F);
  negative class cache scoped to registered loaders, failed loaders released
  without closing shared jars (Stage G).
- Plugin failures contained, enable chains serialized, callback and
  security-listener failures isolated, displaced players restored (Stage E).
- Command-map iteration synchronized, plugin API null contracts validated,
  event descriptors fuzzed, disable and mixed-event-type workloads measured
  (Phase 5).

### Fixed (proxy-wide stability round)
- Transfer handoff guarded against duplicate logins and hostile payloads;
  malformed pongs, failed channel inits and listener floods contained;
  round-robin fallback bounded and empty priority lists refused cleanly.
- Configuration accessor contracts honored, parse errors survived without
  killing the proxy; displaced packs closed, manifests validated, chunk
  requests bounded.
- Panel: connection loss surfaced, empty states handled, a content security
  policy added; rolled log archives pruned after 30 days; startup/shutdown
  reported honestly to supervisors and terminals; runtime NetherNet identity
  material kept out of version control; plugin class definition serialized
  under the JVM per-name loading lock.

## [1.3.0] - 2026-11-22

Phase 2: a stability- and resource-efficiency pass over the networking, rewrite and
runtime layers. Every change is evidence-backed and listed in
`docs/PHASE2_BASELINE.md`; the before/after numbers come from the same reproducible
harness (`benchmarks/results-phase2.json`, `benchmarks/benchmark-phase2.png`).

### Fixed
- **Cross-loop visibility (JMM)**: the `RewriteData` entity ids, dimension, spawn
  position, rotation, gamerules, palette rewrite, store entrypoint, startGameSettings and
  immobile flag are written on the new downstream event loop and read by rewrites on the
  upstream loop with no other happens-before edge; they are volatile now (one write per
  switch). `RewriteMaps.blockMap`, `BedrockClientConnection.compressionStrategy` and
  `ProxyBatchBridge.handler` had the same defect. Locked by a contract test.
- **`BlockPalette` palette cache race**: the static per-version palette cache was a plain
  fastutil map mutated from every downstream event loop during StartGame handling; two
  loops inserting concurrently could corrupt it. It is a bounded `ConcurrentHashMap` now,
  with identical lookup semantics and a size cap against pathological servers.
- **Stale initial `StartGame` hijack**: `InitialHandler` (first join) applied any
  StartGame unconditionally, so a dial whose fallback/retry had already begun could
  overwrite `clientConnection` and the rewrite state mid-recovery. It now mirrors the
  superseded-connection guards `SwitchDownstreamHandler` applies to transfers. Regression
  tests cover the discarded and superseded-slot cases.
- **Shared player state**: `ProxiedPlayer.permissions` is a `ConcurrentHashMap` (plugins
  write it from arbitrary threads; `addPermission` uses `putIfAbsent` so concurrent
  first-adds cannot lose a permission), `pluginPacketHandlers` is a copy-on-write list
  (iterated per packet), and the lazily built downstream `LoginPacket` is published under
  one lock. The chunk-radius/cache flags crossing the upstream->downstream handoff are
  volatile.
- **Transfer queue drain on the wrong loop**: Netty runs `handlerRemoved` on the removing
  thread, so a transfer failure from a downstream loop drained the client-bound queue on
  that loop; the drain (and its writes) now runs on the channel's own event loop.
- **Scheduler pile-up**: a repeating async task re-arms at dispatch time, so one execution
  slower than its period spawned executor threads without bound. A task handler now
  admits at most one in-flight execution; runs due while the previous one executes are
  skipped, not queued. The tick loop also catches `Throwable` - an escaped `Error` used
  to kill the tick silently.
- **`ConnectionThrottle` eviction window**: a max-size eviction between `putIfAbsent` and
  the follow-up read NPE-ed out of the connection path during wide floods; it fails
  closed now.
- **Plugin class cache**: `cachedClasses` is written by `findClass` from whichever thread
  first loads a class (including event loops); it is a `ConcurrentMap` now.
- **Panel log tail** no longer allocates a `SimpleDateFormat` per line, and the stale
  `ProxyServer` TODO claiming the downstream compression level was unimplemented is
  corrected (it is per-connection since 1.1.0).

### Changed
- **Packet decode allocation**: `BedrockPacketCodec` parsed each packet header from a
  throwaway `retainedSlice` - one allocation per packet per batch on both legs; the
  header now parses directly on the packet buffer with the reader index restored on
  every path.
- **`ClientPacketQueue` drain**: the fixed 50 ms flush timer added up to 50 ms of latency
  to every proxy-generated handshake/transfer packet (hundreds of milliseconds across a
  transfer) and woke idle connections 20 times per second. Queued packets now drain
  ~5 ms after the first one is queued, scheduled only while packets are queued; bursts
  still leave as one batch and idle connections are never woken. The queue is bounded
  (4096 packets) with the same overflow handling as the transfer queue - previously it
  grew without limit - and its write promises complete at enqueue time.
- **Velocity techniques evaluated** (see `docs/PHASE2_BASELINE.md`): flush consolidation,
  fast paths for untouched batches, composite-buffer framing and lazy downstream decode
  were already present; cross-leg `setAutoRead` backpressure was rejected with reasons
  (RakNet legs stop ACKing when reads pause, which trades a slow queue for session
  timeouts).

### Compatibility
- Public plugin APIs unchanged; configuration formats unchanged; protocol behavior
  unchanged. All functional benchmark counts are identical to the pre-Phase-2 build
  (300/300 handshakes, 200/200 and 500/500 held sessions, zero ping loss).

### Post-release verification (2026-11-22)
- The benchmark harness gained real Bedrock sessions (`benchmarks/BedrockBench.java` +
  `benchmarks/bench_bedrock.py`): protocol-560 logins with self-signed JWT auth, both-leg
  encryption, resource packs, StartGame and fast transfers against two embedded fake
  backends. This closes the "transfer-path latency is a design improvement" gap from the
  Phase 2 A/B: measured on the same VM, fast-transfer median dropped **290 ms -> 120 ms
  (−59%)**, login-to-StartGame **340 ms -> 183 ms (−46%)**, in-game text round-trip
  unchanged, functional counts identical on both sides
  (`benchmarks/results-bedrock.json`, `benchmarks/bench_bedrock_chart.png`).

## [1.2.0] - 2026-11-22

### Fixed
- **Shutdown teardown** was silently aborting halfway when a panel `end` command triggered it:
  the panel executor's `shutdownNow()` interrupted the shutdown itself, so network interfaces
  stayed bound, executors were never terminated and bStats was never shut down. Panel console
  commands now run on the tick scheduler exactly like the terminal console, the teardown
  swallows a caller interrupt before its drain window, network interfaces close before the
  event executor, `ProxyServer.shutdown()` is entry-atomic, `RakNetInterface.running` is
  volatile, and a failed start exits with status 1.
- **Scheduler finalization** called `cancel()` on tasks that completed normally, firing every
  `CallbackTask` callback a second time (with `null`) from the tick thread; concurrent
  cancellation could run `onCancel()` twice. Completed handlers are unregistered without
  cancelling, `cancel()` is synchronized, `currentTick` is volatile (delays could fire early
  from stale reads), and the scheduler shutdown interrupts hung tasks and drains its registry.
- **Python event handlers** failing the completable event pipeline: a broken Python handler
  completed the event future exceptionally, which the login/transfer flows turn into a player
  disconnect - the identical Java failure was only logged. Handler exceptions in both languages
  are now logged and skipped. Documented in `docs/PLUGINS.en.md` / `PLUGINS.fa.md`.
- **Plugin disable leaked listeners**: handlers kept firing into a closed GraalPy context
  (PolyglotException per event) and commands stayed registered after disable. Python plugins
  now detach subscriptions/commands automatically; `EventManager.unsubscribe` (additive) and
  an ownership-aware `CommandMap.unregisterCommand` support Java plugins.
- **Plugin boot crash**: dependency cycles longer than two recursed to `StackOverflowError`
  (an Error escaping boot). Cycles are detected per enable pass and rejected.
- **Failed enables** left the plugin flagged enabled-but-dead and ran `onDisable()` against
  half-initialized state; the flag now reverts and the disable pass is not triggered.
- **`Plugin.getResourceFile`** leaked one `JarFile` handle per call and threw a bare NPE for
  missing entries; it reads the entry now and returns `null` with a warning.
- **Duplicate plugin names** kept a never-loaded classloader that could shadow the winner's
  classes; duplicates are skipped with a warning. Plugin names, Python `main` paths and
  `getResourceFile` are validated/confined to the plugin tree; `sys.path` injection goes
  through a bindings member instead of source-text interpolation.
- **ConnectionThrottle** get-then-put race let concurrent first-hits from the per-core RakNet
  loops create orphaned counters, under-counting the per-IP limit during floods (now
  `putIfAbsent`, covered by a 16-thread exact-limit test).
- **PacketQueueHandler overflow** leaked the batch rejected by the MPSC queue and never
  completed the write promises; rejected batches are released and promises completed/failed.
- **BlockMap (legacy chunk rewrite)** released the packet's own buffer on the failure path
  (latent use-after-free); ownership now moves only on success. Covered by regression tests.
- **wdme** checked a raw lang key instead of the translated permission, so granting
  `waterbucket.command.me.other` never unlocked inspecting other players; **wdsend** crashed
  with `ArrayIndexOutOfBounds` for a player who had not connected anywhere yet.
- **Cross-loop visibility**: `ConnectedUpstreamHandler.targetConnection` and
  `ProxiedPlayer.clientConnection` are volatile now (stale reads during transfers could drop
  the first packets of a new server).
- **Panel**: rate-limit buckets are bounded and expired windows evicted, non-object JSON
  bodies answer 400 instead of resetting the connection, token comparison is constant-time,
  and metrics sampling starts only after a successful bind (a failed bind leaked the sampler
  and the log-tail appender); the sample task is cancelled on stop.
- **Stale player-name index** on reconnect with a changed profile name (old name resolved to a
  dead instance until the ghost sweep).
- Quoted numeric `config_version` silently re-ran every migration; addresses without a port in
  the config now fail with a named error instead of `StringIndexOutOfBounds`.

### Changed
- Panel static assets are read from the jar once and served from a bounded in-memory cache
  instead of inflating jar entries on the event loop for every request.
- `sendPopup(message, subtitle)` renders the subtitle (appended below the popup text) instead
  of silently dropping it.
- Internal executors (tick, scheduler, events) are daemon threads and the scheduler shutdown
  interrupts hung tasks after its grace period.
- Benchmarks round 3: fresh A/B numbers, per-session class-histogram evidence
  (`benchmarks/results-histograms.txt`, `benchmarks/heap_diff.py`), honest noise caveats and a
  corrected chart generator (ping percentiles were swapped between the two proxies in the old
  chart; the scale annotation hardcoded stale values).

### Compatibility
- Public plugin APIs kept: all additions are additive (`EventManager.unsubscribe`,
  `EventManager.subscriberCount`, `TaskHandler` cancel semantics unchanged for callers).
- Observable behavior changes are documented: a throwing Python handler no longer fails the
  event pipeline (parity with Java), disabled Python plugins detach automatically, and a failed
  enable no longer runs `onDisable()`.

## [1.1.0] - 2026-11-21

### Added
- **Python plugin front-end** alongside the untouched Java plugin API: folder-based plugins
  under `plugins/`, evaluated by an embedded GraalPy interpreter (lazy, thread-safe, full
  `import java` interop). Event subscription, commands, scheduling, config helpers; per-plugin
  contexts; broken scripts never take the proxy down. See `docs/PLUGINS.md`.
- **Local web panel** served by the proxy itself (`enable_panel`): live dashboard with
  players/memory/CPU history charts, players with kick/transfer actions, downstream servers
  with real RakNet pings, and a console running proxy commands. Token-secured (auto-generated
  to `panel_token.txt`), loopback-bound, rate limited, hardened headers.
- Windows launcher: `Waterbucket.exe` (Launch4j, requires 64-bit Java 17+).
- Plugin documentation for both languages plus runnable Java and Python examples.
- Benchmarks round 2: leak-growth scenario and a 500-session scale test; `gen_chart.py` moved
  into the repo so the chart is reproducible.
- `EventLoops.ChannelType.serverSocketChannel()` accessor for custom TCP servers (panel).

### Fixed
- Fast-transfer address matching for localhost multi-port setups: all loopback spellings
  (127.x.x.x / localhost / ::1) resolve to the server on the matching port, matching never
  performs DNS on event loops, and malformed transfer packets no longer kill the downstream.
- Event pipeline thread safety: concurrent event calls plus late subscriptions could corrupt
  the handler map (fastutil) or throw `ConcurrentModificationException` (EnumMap+ArrayList);
  now concurrent map + atomic immutable snapshots, with a concurrency test suite.
- Ghost players (upstream WaterdogPE #266): stale registrations whose disconnect event was
  lost are evicted by a 15-second sweep; a re-registered fresh session stays untouched.
- Query handler reverse-DNS on the event loop (`getHostName()` → `getHostString()`).
- `TranslationContainer.getTranslated()` no longer crashes when the proxy instance does not
  exist yet (command settings built before boot).

### Changed
- Compression: the configured zlib level is applied per connection leg (upstream vs
  downstream); previously both legs inherited the upstream level.
- Flush consolidation on both network legs (Velocity's approach, 256 explicit flushes).

## [1.0.0] - 2026-11-21

### Added
- Rebrand of WaterdogPE to WaterbucketPE (code, resources, terminal banner).
- Terminal improvements: branded ASCII banner, persistent console history, log pattern fix.
- Benchmark harness (`benchmarks/`): real-UDP pings, real RakNet handshakes, RSS/CPU tracking.

### Fixed
- Release builds ran with Netty leak detection stuck at `SIMPLE` due to a raise-only guard.
- Scheduler NPE when a task was unregistered concurrently while it ran; cancellation flag is
  volatile so async cancellation is observed by the tick thread.
- `PlayerManager` name lookups are O(1) via a case-insensitive index; login path no longer
  compiles a regex per username.

[1.3.0]: https://github.com/AmirShafi6819/WaterbucketPE/releases/tag/v1.3.0
[1.2.0]: https://github.com/AmirShafi6819/WaterbucketPE/releases/tag/v1.2.0
[1.1.0]: https://github.com/AmirShafi6819/WaterbucketPE/releases/tag/v1.1.0
[1.0.0]: https://github.com/AmirShafi6819/WaterbucketPE/releases/tag/v1.0.0
