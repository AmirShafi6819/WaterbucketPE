# Changelog

All notable changes are documented here. Format based on Keep a Changelog,
versioning follows [semantic versioning](https://semver.org/).

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

[1.1.0]: https://github.com/AmirShafi6819/WaterbucketPE/releases/tag/v1.1.0
[1.0.0]: https://github.com/AmirShafi6819/WaterbucketPE/releases/tag/v1.0.0
