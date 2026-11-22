# Changelog

All notable changes are documented here. Format based on Keep a Changelog,
versioning follows [semantic versioning](https://semver.org/).

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

[1.2.0]: https://github.com/AmirShafi6819/WaterbucketPE/releases/tag/v1.2.0
[1.1.0]: https://github.com/AmirShafi6819/WaterbucketPE/releases/tag/v1.1.0
[1.0.0]: https://github.com/AmirShafi6819/WaterbucketPE/releases/tag/v1.0.0
