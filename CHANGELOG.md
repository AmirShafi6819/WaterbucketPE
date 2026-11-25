# Changelog

All notable changes are documented here. Format based on Keep a Changelog,
versioning follows [semantic versioning](https://semver.org/).

## [1.6.1] - 2026-11-26

### Fixed

- **Logins to PocketMine-MP backends**: `forward_client_ip` shipped on by default in 1.5.6 and
  1.6.0, and the forwarded fields (Waterdog_IP, Waterbucket_IP, Waterbucket_Port) are extra keys
  in the client data object of the login packet. Strict server software - PocketMine-MP and its
  forks - rejects unknown keys with `JSON property "Waterdog_IP" does not exist in object of type
  ...ClientData` and drops the login as a bad packet, so every install pointed at such a backend
  could not accept logins at all. The switch is now off by default, and a config upgrade step
  resets a carried-over `true` once with a warning that names the way back: set
  `forward_client_ip: true` for claim-aware backends (Nukkit-MOT, Geyser, or a login-extras
  plugin); `use_login_extras` still implies forwarding.

## [1.6.0] - 2026-11-26

### Added — World View (the proxy sees the real worlds)

- **`world_view` config section**: the proxy learns the worlds of every downstream server
  from the packets the clients receive - chunks with real block data, block entities,
  entities, players, inventories, world identity (name, seed, spawn), time, difficulty,
  game rules and weather. Nothing is guessed: the model is exactly what the clients were
  shown, kept live with a retention janitor and an LRU cap.
- **Go runtime sidecar**: the world model and the plugin loader run in compiled Go,
  embedded in the release jar and spawned at boot (Unix domain socket bridge, TCP loopback
  fallback). The sidecar auto-restarts with a budget; losing it only disables world
  queries, never the proxy network.

### Added — Go plugin loader

- **Plugins in Go** load from `plugins-go/` as plain source folders (`plugin.yml` +
  `main.go`) interpreted by the sidecar - no Go toolchain needed by users. The compiled
  `pluginapi` surface covers worlds, chunks, blocks, block entities, entities, players,
  inventories and 43 event types with real cancel semantics; observation events stay
  non-cancellable because the proxy cannot undo what a downstream server already decided.
- **Example Go plugins** ship in `examples/plugins/go/` and load through the real host in
  tests; the end-to-end test drives the actual sidecar from a real chunk blob to a plugin
  cancel verdict.

### Added — compat world events and the world API (every language)

- **43 new compatibility events** across world (init/unload, chunks, time, weather,
  difficulty, game rules, spawn position), block (break, place, start break, update, sign
  change), entity (spawn, despawn, move, motion, effect, health change, damage, health
  regain, data update, teleport), player (interact, item held, drop item, toggles,
  animation, death, respawn, gamemode, dimension change, form respond, entity interact,
  mouse over, bed enter/leave) and inventory (open, close, update, craft, transaction).
- **Real cancellation**: cancelling an action event drops the client packet - the break,
  place, sign edit, item drop, animation, form response or bed entry genuinely never
  happens; a cancelled craft is answered with a proper stack-request rejection.
- **Derived events carry real data**: damage and health regain come from actual health
  attribute deltas; block updates resolve their old name from the live model.
- **World queries**: `World`, `Chunk`, `Block`, `BlockEntity`, `Item`, `Inventory`,
  `Entity` and `Player` views over the live model - block lookups, entity snapshots,
  inventories, entities near, world meta - exposed through `compat.world()` for Python,
  PHP and Go alike, with a short-TTL cache for hot block lookups.

### Added — plugin api-version

- **`api-version` gate in every loader** (Java jar + annotation, Python, PHP, Go):
  `1.6.0` is this build's generation; `1.0.0` is the legacy wildcard that loads on every
  generation; anything else is rejected before any plugin code runs with the exact reason
  in the log. Missing declarations normalize to the legacy wildcard.

### Performance

- Chunk blobs stay packed in the model (lazy index unpacking per query), deltas ride a
  bounded drop-on-full queue so the sidecar can never stall a Netty event loop, and
  cancellable verdicts fail open after a bounded 8ms wait. The bridge speaks a compact
  binary protocol pinned by golden-vector conformance tests on both sides.


### Added — Persian Fix (display-ready Persian text)

- **`persian_fix` config section (off by default)**: Persian (and every other Arabic-script
  language) becomes display-ready for the client - joined letters, correct right-to-left
  order - computed once inside the proxy, so it works with every downstream server software
  without any server-side plugin. The engine is a faithful port of the well-known Persian
  server fix plugin family: letters map to their exact Arabic presentation forms
  (isolated/initial/medial/final, lam-alef ligature included) and the line is reordered into
  visual order while Latin words, numbers, bracketed groups, `§` color codes and leading
  `><[]` markers stay readable. Verified against the complete vector set of the original
  engine's own test suite (49 vectors, byte for byte).
- **Per-area toggles** under the section: `chat` (player chat before it reaches the server
  and other clients), `messages` (system messages, popups, tips, whispers), `sign` (sign
  edits and server-driven sign updates, with the original 14-character wrap-to-four-lines
  rule), `title` (titles and subtitles), `boss_bar`, `scoreboard` (display names and
  fake-player score entries) and `forms` (every text field inside form JSON - titles,
  content, buttons, labels, dropdown options). Each defaults to on; the master `enable` key
  defaults to false.
- **Never fixes twice**: text that already carries presentation forms (the proxy's own chat
  echo coming back downstream, or a server that ships the same fix server-side) passes
  through untouched, so both directions can be fixed at once safely.
- **The bundled Persian font pack is offered automatically** while the fix is enabled and the
  proxy's resource pack pipeline is active, so clients render the shaped glyphs exactly like
  the original plugin offering its pack.
- **Zero cost where it matters**: pure Latin traffic is rejected by a single O(n) scan and
  returns the original string instance - no allocation, no packet re-encode; a changed
  packet re-encodes alone. 63 tests cover the engine vectors, the toggle matrix, the
  never-fix-twice guard, sign wrapping, form JSON and the hot path.

### Added — compatibility plugin API for the original server software's plugins

- **compatibility plugin surface, native in the proxy** (`dev.waterbucket.waterbucketpe.plugin.compat`):
  the plugin API shape of the well-known Bedrock server software is mirrored onto the proxy with its
  own syntax - `Server`, `Player`, `Config`, `Scheduler`, plugin manager - every method backed
  by a real proxy operation (player lookups, broadcasts, message/tip/popup/actionbar/title/
  toast/forms, chat dispatch, kick with its own cancellable event, permissions, motd, command
  dispatch, the scheduler). World-touching calls of the original software have no meaning on a
  proxy and are not faked. The surface lives next to the proxy's own API; nothing existing
  changed.
- **Compatibility events raised on real proxy moments**: `PlayerLoginEvent` (cancelling
  refuses the login), `PlayerJoinEvent` / `PlayerQuitEvent` (join and quit messages broadcast
  like the original), `PlayerChatEvent` (cancel + edit flow back into the proxy pipeline
  synchronously), `PlayerCommandPreprocessEvent` and `PlayerKickEvent` (fires on the compat
  `kick()`, veto keeps the player online). Proxy-native handlers run first; compatibility
  plugins see the same moment afterwards.
- **All three plugin languages**: Java plugins import the package; Python and PHP plugins get
  a `compat` global (`compat.getServer()`, `compat.playerChatEvent()`, `compat.newConfig(...)`) bound
  next to `plugin`/`proxy`/`logger`, with snake_case aliases. PHP event names now resolve the
  compatibility package by simple name too.
- **Economy plugin rebuilt and tested end to end**: the classic economy plugin re-implemented
  purely on the compatibility surface (Config persistence, join-event auto accounts,
  `/money`, `/pay`, `/eco`) runs green against the proxy in the test suite, and ships as the
  `examples/plugins/java-economy` example. 22 new tests cover the facades, the event bridge
  (cancellation and edits included), the config utility, the scheduler, and the Python and
  PHP runtimes using the same surface.

## [1.5.6] - 2026-11-25

### Added — duplicate login handling

- **`kick_existing_players` config key (default off)**: when enabled, a new
  login whose display name matches an online session of a different identity closes the older
  session with a clean notice (`waterbucket.duplicate-login.kick-old`, overridable in
  `ingame-messages.yml`) and takes over the name; the closed session leaves the registry
  immediately instead of waiting for the ghost sweep. When disabled, behavior is exactly as
  before: both sessions stay online and only the name lookup changes hands.
- The same-identity reconnect path (same XUID/UUID joining again) keeps closing the previous
  session unconditionally and is untouched by the flag. Covered by four new tests plus the
  coexist-behavior tests that pin the default.

### Fixed — downstream servers now see the player's real IP

- **Real IP forwarding, on by default (`forward_client_ip: true`)**: the player's real
  address and source port are embedded into the client data JWT that is re-signed and sent
  to every downstream server, so servers stop logging joins as `127.0.0.1` with a random
  port (the proxy's own dial socket) and can show the player's true IP. The `Waterdog_IP`
  field is the de-facto claim the ecosystem already reads — modern Bedrock server software
  and the proxy login-extras plugins all check it — and the fork adds
  `Waterbucket_IP` / `Waterbucket_Port` under its own namespace.
- **Works on existing installs without touching config.yml**: the new key is missing from
  older files, so the field default (enabled) applies and the key is appended on the next
  start. Operators who do not want the real address shared with backends set
  `forward_client_ip: false`.
- **`use_login_extras` keeps its meaning**: it still controls the XUID / Minecraft ID
  fields and continues to imply IP forwarding, so setups that relied on it behave exactly
  as before. Claim injection is split into tested helpers (`addForwardingClaims`,
  `addLoginExtras`) covering both switches, IPv6 addresses and the off/off case.
- **Forwarding can never break a login**: an unusual session address (a non-socket type,
  an unresolved host without an IP) now skips the claims or falls back to the host text
  instead of throwing into the login catch-all and disconnecting the player with a generic
  "Login failed". Covered by two dedicated edge-case tests (76 suites / 463 tests green).

## [1.5.5] - 2026-11-24

### Changed — faster joins and flat idle cost

- **Async events without listeners no longer pay an executor round-trip.** The join path
  fires several async events (login, initial server determined, pre-connect); with no
  plugin subscribed they now complete inline instead of hopping the event executor 3+
  times per join. Handler semantics are unchanged - the dispatch decision reads the same
  atomic snapshot the pipeline uses, and a handler registered between two events is picked
  up by the next one. Real effect: plugin-free proxies join at the speed of the network
  path alone, and login bursts stop queueing behind no-op dispatches.
- **Startup warmup now covers the downstream dial path**: the RakNet client channel classes,
  the proxy-side client session initializer chain and the initial server handler are loaded
  on the boot warm-up thread, so the first transfer pays only the network cost - no
  class-loading spike on the event loop mid-join.
- **Join duration telemetry (debug)**: the proxy logs `reached <server> in <ms> since
  spawn` per initial connection at debug level, so operators can measure the join path
  and tune priority lists with numbers instead of guesses. Zero cost when debug is off.
  73 suites / 431 tests green (4 new async-dispatch tests).

### Added — Only Proxy Join

- **`only_proxy_join` config section** (`enable`, `proxy_port`, `manage_firewall`): when
  enabled, the proxy maintains host firewall rules that block direct public access to every
  configured downstream server's UDP port, so players can only enter through the proxy on
  its own port - exactly the requested workflow: pick the port, flip the switch, and joining
  any server directly (bypassing the proxy) stops working while joining via the proxy port
  keeps working.
- **Host firewall automation, safe by design** - Linux: per-port iptables rules with a
  `waterbucket-opj` comment marker, loopback traffic explicitly allowed before a global
  UDP drop, so the proxy itself keeps dialing its downstreams; Windows: one named block
  rule per port (Defender Firewall exempts loopback by default). All commands run through
  ProcessBuilder without a shell; apply is idempotent (`-C` before `-I`), a minute-cycle
  refresh heals rules after a firewall flush, and removal runs on disable, shutdown and
  restart so the operator's firewall is never left dirty.
- **Failure isolation**: without admin/root the manager logs precise manual instructions and
  the proxy runs unprotected, never broken; `manage_firewall: false` turns the feature into
  pure verification for operators who manage rules with their own tooling.
- **`/wbonlyproxy [status|apply|remove]`** (alias `/wdonlyproxy`) reports per-port protection
  state and applies/removes rules on demand.
  72 suites / 427 tests green (5 new rule-plan tests; no host firewall is touched in tests).

### Added — sleep mode for empty proxies

- **`sleep_mode` config section** (`enable`, `after_minutes`, default on / 5 minutes): when
  no player has been online for the idle window and no other contact happened, the proxy
  falls asleep - a GC pass releases the join/session caches (logged with before/after heap
  numbers) and the periodic downstream probe cycle pauses, so an idle proxy sits at a flat
  CPU/RAM floor instead of pinging servers nobody is using.
- **Nothing is ever stopped**: the listener, tick loop, scheduler and every connection path
  stay fully alive. Waking is a single volatile flip plus one log line - the first ping,
  query, login, panel request or console/panel command after days of sleep is served
  instantly with the same entry cost as one second of idling.
- **Panel**: a sleeping badge appears in the header (moon icon, localized EN/FA) and the
  status API reports `sleeping`; downstream probe refreshes resume automatically on wake.
  Console/panel command `/wbsleep [status|sleep|wake]` shows the state or forces a
  transition (force-sleep is refused while players are online).
  71 suites / 422 tests green (5 new SleepModeManager state-machine tests).

### Added — mark a server offline while it keeps running

- **`/wbserveroffline <server> [on|off]`** (alias `/wdserveroffline`, permission
  `waterbucket.command.serveroffline`): toggles a downstream server's availability without
  touching the server itself. A marked-offline server keeps running, but the proxy reports it
  offline (server-list health checks and the panel fail instantly, no probe traffic),
  skips it in join/fallback/round-robin priority lists like a genuinely unreachable server,
  and refuses new connections before any network attempt - players reconnect through the
  normal fallback chain instead of hanging on a doomed dial. Players already on it are not
  kicked. Toggling persists to config.yml (`servers.<name>.disabled: true`, omitted when
  false so existing files stay byte-identical) and survives restarts; startup logs servers
  that come up marked offline.
- `ServerDisabledException` drives the refusal through the regular connect-failure paths, so
  a disabled server behaves exactly like a dead one everywhere (transfers, initial join,
  reconnect handler, panel health tracker). Join/reconnect handlers now resolve the proxy
  from the connecting player (same instance at runtime, but testable).
  70 suites / 417 tests green (5 new offline-toggle tests).

### Added — professional `/restart`

- **`/restart` command** (alias `/wdrestart`, permission `waterbucket.command.restart`, also
  runnable from the panel console): a managed, one-shot restart sequence instead of a bare
  process kill.
- **Sequence contract** — `ProxyRestartEvent` (new, synchronous, cancellable: plugins can veto
  or defer a restart) → broadcast + graceful player kick with the new
  `waterbucket.restart.broadcast` / `waterbucket.restart.kick` messages → plugins disabled
  through the normal shutdown path → relaunch script → clean `shutdown(0)`. Every step is
  failure-isolated; the sequence always ends in a clean shutdown, never a half-dead proxy.
- **Relaunch script** — `restart.sh` / `restart.bat` is auto-generated next to the config
  (executable, editable), waits for the old process to exit (`kill -0` polling / `tasklist`),
  then relaunches `java <same JVM options> -jar <same jar>` from the same directory - so
  **newly added plugins, packs and config values are picked up**, which no in-process reload
  can guarantee, and the listener ports are free (no bind race). Configure a custom script
  via `restart.script_path`; disable the mechanism with `restart.use_script: false` (wrapper
  managers such as systemd/Docker restart policies keep working).
- **Safety** - concurrent restart requests (console spam, panel + console at once, SIGTERM
  racing in) collapse into the first one; a plugin veto releases the guard so a later request
  is not stuck; environments that cannot exec scripts (sandboxed hosts, dev runs) log a clear
  reason and fall back to a plain graceful shutdown.
  69 suites / 412 tests green (6 new RestartManager sequence tests).

### Added — PHP plugin language

- **PHP is now a first-class plugin language** alongside Java and Python. Plugins are
  folder-based (`plugins/MyPHPPlugin/waterbucket.yml` + `main.php`) and run on the embedded
  **Quercus** PHP interpreter (PHP-on-JVM, GPLv2, shipped inside the proxy jar) - no system
  PHP installation is required, and servers without PHP plugins pay nothing at boot.
- The bridge keeps one persistent Quercus environment per plugin, so functions the script
  defines stay callable for the whole lifetime and state survives across dispatches; all
  PHP execution is serialized per plugin with Quercus' thread-local environment installed
  for the duration, making concurrent event dispatch safe.
- Lifecycle hooks `on_startup($plugin)`, `on_enable($plugin)`, `on_disable($plugin)` are
  optional functions detected by name; events (`$plugin->subscribe('PlayerChatEvent', fn)`),
  commands (`$plugin->registerCommand(...)` with aliases), and scheduling
  (`scheduleAsync/scheduleDelayed/scheduleRepeating`) mirror the Python facade. Event classes
  resolve by simple name, FQCN or any loaded plugin's class loader. PHP closures marshal
  natively into Java callbacks - nothing is re-evaluated per dispatch.
- The pipeline-safety contract matches the Java and Python bridges: a throwing PHP event
  handler is logged and swallowed (including on completable events, so a broken plugin can
  never disconnect players), and disable detaches subscriptions, unregisters commands and
  cancels scheduled tasks automatically before the interpreter closes.
- Runnable example under `examples/plugins/php-example/`; plugin guides (EN/FA) gained the
  PHP quick start, facade reference, troubleshooting rows and source references.
  68 suites / 406 tests green (8 new end-to-end PHP bridge tests).

### Added — familiar proxy features, reverse engineered

Three stable, battle-tested proxy listener features were reimplemented natively for the
Bedrock proxy, with the same config names and semantics so operators migrating from other
proxies
feel at home.

- **`listener.ping_passthrough`** (`disabled` / `description` / `all`, default `disabled`):
  the proxy keeps a background snapshot of the first enabled priority server's real
  server-list pong and answers its own pings with it - `description` swaps the backend's
  motd/sub-motd in (player counts stay live), `all` also swaps the backend's player counts
  and game type. The snapshot refreshes every 5 seconds through a single in-flight probe
  that never runs on the ping path itself, pauses while the proxy sleeps and keeps the
  last-good value while the backend is down, so the server list answers instantly and can
  not flap. Values apply before `ProxyPingEvent` fires, so plugins always get the final say.
- **`listener.show_max_players`** (default `true`): set to `false` to show the live player
  count as the maximum, so the proxy never looks full.
- **`listener.command_rate_limit`** (default `20`, `0` disables): a smooth per-player token
  bucket with one second of burst drops commands past the rate and answers with the new
  `waterbucket.command.ratelimit` message. Console and panel commands are never limited,
  players are isolated from each other, and idle buckets are swept every ten minutes so the
  limiter can not grow with the day's visitor count.
  75 suites / 448 tests green (12 new passthrough/rate-limiter tests).

## [1.5.0] - 2026-11-24

The operator-experience release: full console/brand rebrand, a rebuilt Liquid Glass web
panel with Persian localization, every player message in one editable file, and the fixes
the first real deployment surfaced. 59 suites / 339 tests green; the plugin-API
round at the end of the release brought the suite to 62 suites / 364 tests.

### Added — complete plugin API (2026-11-24, shipped in the same release)

The plugin module was audited against the upstream plugin documentation and rounded out to a full surface. 62 suites / 364 tests green.

- **`PlayerCommandEvent`** — a new cancellable event fired for every command a player types,
  before the proxy decides whether the command belongs to it or to the downstream server.
  The command can be rewritten (`setCommand`) or cancelled (consumed: neither the proxy nor
  the backend server runs it). `DispatchCommandEvent` keeps covering the dispatch stage.
- **Runtime max players** — `ProxyServer.getMaxPlayers()` / `setMaxPlayers(int)` /
  `setMaxPlayers(int, boolean persist)` / `isFull(ProxiedPlayer)`. The effective value
  (runtime override or `listener.max_players`) now drives the server-list ping, the query
  response, `/wbstatus` and the panel, and is **enforced at login**: when the proxy is full,
  players are disconnected with the new `waterbucket.server.full` message unless they hold
  the `waterbucket.bypass.maxplayers` permission.
- **`/wbmaxplayers [count]`** — new built-in command (alias `/wdmaxplayers`) to show or set
  the limit; `wbmaxplayers 0` disables it. The persisted form survives restarts.
- **Player lookup + broadcast** — `getPlayerExact(name)` (case-insensitive exact match),
  `matchPlayer(name)` (partial match) and `broadcastMessage(String|TextContainer)` which
  reaches every online player plus the console.
- **Plugin-owned scheduler tasks** — `scheduleTask/scheduleDelayed/scheduleRepeating(Plugin,
  Runnable, ...)` register a task to its plugin; everything scheduled that way is cancelled
  automatically when the plugin disables (so a repeating task can no longer outlive its
  owner), plus `cancelTask(int)` and `cancelTasks(Plugin)` for manual control. Tasks are
  deregistered as they complete, cancel or the scheduler shuts down.
- **Runtime plugin disable** — `PluginManager.disablePlugin(Plugin)` disables a plugin and
  every enabled dependent first (cycle-safe; no infinite recursion on a depends loop).
- **Plugin.yml `description`** — the optional descriptor field is now parsed and shown by
  `wbplugins verbose` (which also prints author; plain `wbplugins` output is unchanged).
- **`Plugin.reloadConfig()`** — discard the loaded config and re-read config.yml from disk.
- **Docs** — `docs/PLUGINS.en.md` / `docs/PLUGINS.fa.md` updated in lockstep: a new
  "Working with the proxy" chapter (players, servers, max players, broadcast, managers,
  join/fallback/forced-host handlers), plugin-owned scheduling, `PlayerCommandEvent`,
  the `description` field and the new commands; the Java example plugin now tours the full
  API (and compiles against the shipped JAR).

### Fixed — follow-up patch (2026-11-24, shipped in the same release)

- **Panel showed giant broken icons** — the HTTP server cached `style.css`/`app.js` for one
  hour (`max-age=3600`) while `index.html` was never cached, so after an update the browser
  rendered fresh HTML against the previous stylesheet and every sprite icon became a huge
  black filled shape (and the theme/language buttons looked swapped, which read as "clicking
  Persian turns light into dark"). HTML/CSS/JS are now served `Cache-Control: no-store`, and
  the icon rules ship inline in `index.html` as a fallback so icons render correctly even
  against a stale stylesheet.
- **Downstream servers always showed offline** — the panel's health probe only existed as
  the manual "Ping now" button, and that button only recognized servers pinned to RakNet;
  config servers (`server_type: bedrock`) are `AutoServerInfo` instances, so every probe
  answered "RakNet server not registered" and the 5s sampler's default state marked them
  offline forever. Servers are now probed automatically every 10 seconds through a
  polymorphic `ServerInfo.ping` (RakNet ping for RakNet, RakNet → NetherNet signaling
  fallback for `bedrock`/auto, signaling probe for `nethernet`), and unprobed servers start
  as "online (ping unknown)" instead of offline.
- **`IOException: An established connection was aborted by the software in your host
  machine` spam** — closing/refreshing the browser tab aborted an in-flight panel poll and
  the exception ran to the tail of the Netty pipeline, printing a full stack trace per
  abort. The panel handler now swallows benign transport errors and closes the connection
  quietly; unexpected failures still log.
- **Players could lock themselves out of localhost** — any `IOException` on a Bedrock peer
  connection (the client's own network dropping the session) triggered the security
  manager's error-timeout IP block; with reconnect retries that could repeatedly block the
  player (and localhost testing) for 15s stretches. Transport-level IO errors no longer
  block IPs and log at debug instead of error with a stack trace.

### Added — plugin API completion & memory stability (2026-11-24, shipped in the same release)

Second plugin-API round, this time completing the surface with the declarative additions
operators actually ask for, plus a targeted pass at the RAM/CPU sawtooth. The suite grew
from 62 suites / 364 tests to **67 suites / 398 tests, all green**.

- **`@PluginDescriptor` annotation** — a jar can now declare its plugin descriptor directly
  on the main class (declarative workflow) instead of shipping `waterbucket.yml`. The id is
  required and follows the same name rules; YAML wins when both exist, so updating an
  existing plugin can not change behavior by accident. The boot scan pre-filters jars by a
  constant-pool marker scan and reads candidate classes with `initialize=false`, so plugin
  static initializers never run while merely reading metadata.
- **`@Subscribe` annotation listeners** — declaration-style event registration: public
  methods with one `Event` parameter, `priority()` following the LOWEST..HIGHEST ordering and
  `ignoreCancelled()` for the contract that cancelled events do not reach
  uninterested handlers. `registerListener(plugin, listener)` / `unregisterListener(listener)`
  / `unregisterListeners(plugin)`; listeners registered with an owner are detached
  automatically when the plugin disables (even when onDisable threw), mirroring the
  plugin-owned task contract. Wrong signatures are skipped with a warning naming the method;
  handler exceptions stay isolated exactly like the functional API.
- **The fluent `TaskBuilder` time scheduler** — `scheduler.buildTask(plugin, runnable)
  .delay(2, TimeUnit.SECONDS).repeat(500, TimeUnit.MILLISECONDS).schedule()` returns a
  `ScheduledTask` handle and runs on a dedicated fixed time-scheduler pool, independent of
  the tick loop. Repeating tasks use fixed-delay semantics (a slow task backs up instead of
  overlapping itself), exceptions are logged with task id + owner and never kill the
  schedule, cancelled one-shots are purged from the queue, and plugin-owned time tasks join
  the same auto-cancel-on-disable contract as tick tasks.
- **`ConnectionRequest` with a real result future** — `player.createConnectionRequest(server)
  .connectWithIndication()` completes with the real outcome (`SUCCESS`, `ALREADY_CONNECTED`,
  `CONNECTION_IN_PROGRESS`, `CONNECTION_CANCELLED`, `CONNECTION_FAILED`) instead of forcing
  plugins to infer it from events; `fireAndForget()` for the rest. The future always
  completes normally (failures are statuses, never exceptions), at the latest after 90s —
  safely above the proxy's 60s StartGame timeout. Synchronous outcomes of `connect()`
  (already connected, transfer cancelled, in flight) are reported per request through a
  per-player observer slot, and player disconnect settles every pending request as
  cancelled, so nothing leaks.
- **ProxyServer aliases** — `fire(event)` (= `callEvent`) and `getAllPlayers()`
  (= `getPlayers().values()`) for familiar call sites.
- **Flat RAM/CPU: bounded executors** — the scheduler and event-manager pools were
  `ThreadPoolExecutor(core, Integer.MAX_VALUE, SynchronousQueue)`: every burst spawned fresh
  threads that died 60s later, so RAM and CPU bounced up and down on join bursts and query
  storms. Both now use a fixed resident core with a large bounded queue (8192) and a small
  elastic head-room (2x core) — no per-task thread churn, flat idle memory, and a loud error
  (not a silent drop, not a caller-thread stall) if the queue ever saturates.
- **Smoother GC curve** — the start scripts now ask G1 for 120ms pause targets (was 200ms)
  and start concurrent cycles earlier (`InitiatingHeapOccupancyPercent=35`), which lowers the
  heap peaks the panel charts draw: smaller cycles, more of them, a flatter line.
- **Docs in lockstep** — the plugin guide (EN + FA) gained the `@PluginDescriptor`,
  `@Subscribe`, TaskBuilder and ConnectionRequest chapters plus a memory-stability note;
  the Java example plugin demonstrates the new APIs.

### Changed
- **Console rebrand** — new Water Bucket banner, default commands renamed `wd*` → `wb*`
  (old names stay as hidden aliases), branded interactive prompt (the library hardcoded
  "> "), `time LEVEL | message` log layout, startup/query/NetherNet software strings say
  WaterBucketPE. The panel and `/wbinfo` now report the real gradle version instead of a
  hardcoded 1.3.0.
- **Web panel rebuilt** — Liquid Glass design with a blue accent (plus cyan/indigo/teal) and
  adjustable glass intensity, dark **and** light themes, **Persian with full RTL** plus
  English (Vazirmatn/Inter fonts bundled, no CDN), a bottom hotbar dock with an animated
  active pill and live badges, and a layout editor (drag to reorder, hide/restore cards,
  persisted).
  Per-server management: live cards with ping sparklines and a detail dialog (ping/player
  charts, kick, move-to-server, ping-now) backed by the new `/api/servers/<name>` endpoint.
  Console: level-colored lines, filters, command history. Charts update in place — the
  servers chart no longer grows taller on every refresh (client fixed heights + server-side
  120-sample history cap).
- **Player messages in one file** — `ingame-messages.yml` (created on first start) holds
  every player-visible text with § colors and {%0} placeholders documented; overrides beat
  the built-in English defaults, deleted lines fall back to them. Previously hardcoded
  strings (shutdown notice, kick default, login guards, transfer-queue overflow, command
  feedback) resolve through the same pipeline.

### Fixed
- **Start scripts refused to boot at all** — `G1NewSizePercent`/`G1MaxNewSizePercent`/
  `G1ReservePercent` are experimental JVM flags and were passed without
  `-XX:+UnlockExperimentalVMOptions`, so the JVM exited instantly and the auto-restart loop
  spammed the error forever (which is why renaming the jar never helped). The hardcoded
  `Waterbucket.jar` name also ignored release jars like `Waterbucket-1.5.0.jar`. Both scripts
  now auto-detect the jar (exact name → newest `Waterbucket*.jar` → any single jar), verify
  Java 17+ properly, and only restart after a real run — an instant exit stops with a clear
  error instead of looping. `start.bat` ships with CRLF line endings again (LF-only broke
  `goto` labels on Windows), both scripts honor `WATERBUCKET_MEMORY`, and the heap-tuning
  flags are behind the unlock flag.
- **Hotbar pill escaped the dock** — the sliding selection pill was measured by JS while
  the button labels animated their width, so the measurements went stale and the indicator
  drifted outside the bar on selection/hover/resize. Replaced with a CSS-only pill that
  lives on the button itself (it physically cannot leave the dock, works in RTL), and labels
  no longer collapse/expand under the cursor.
- **Vazirmatn never applied in Persian mode** — the RTL font stack targeted `body[dir="rtl"]`
  while the attribute is set on `<html>`, so the selector never matched and Persian fell
  back to system fonts; global `letter-spacing` also put gaps into joined Arabic glyphs
  (now zeroed in RTL). Added the missing Vazirmatn SemiBold (600) weight.
- **Console in Persian mode** — the log inherited the page RTL direction and visually
  scrambled timestamps, levels and mixed text. The log area now always reads left-to-right
  like a terminal, each message is bidi-isolated (`unicode-bidi: plaintext`) so Persian
  text inside a log renders correctly in place, and the command row/input stay LTR. Mixed
  Latin values ("24 MB / 1.0 GB") no longer flip in RTL cards either.
- **Minecraft § codes showed raw in the panel console** — log lines carrying §b/§l style
  codes printed the escapes literally; they now render as real colors and formatting
  (Minecraft palette, dark-UI tuned).
- **Panel icons** — every emoji and unicode glyph (stat cards, hotbar, server cards, hidden
  chips, header buttons) replaced with a bundled stroke-icon SVG sprite; toolbuttons get
  translated titles.
- **Players view opened empty** — data only arrived with the next 5 s poll tick; switching
  to the Players view now fetches immediately.
- **Persian copy tidy-up** — consistent half-spaces (نیم‌فاصله) and natural phrasing for
  every panel string, including stat labels that were still hardcoded English
  ("max 20", "2 cores", "16 threads" now translate).
- **STUN/ICE log flood** — the transport logged four STUN mapping lines every 15 s (each
  probe gets a fresh ephemeral port, so the line never deduped) and the native ICE stack
  printed candidate-churn bursts after NetherNet joins. The repetitive mapping line is
  filtered at the logger, and the native level now actually reaches the library (the wrapper
  only forwards it once the native library is initialized) with an ERROR default that keeps
  real ICE failures visible.
- **Join-time spike** — the first join after boot paid JCE init, ECDH/AES/ECDSA, zlib and
  ~275 packet class loads at the moment a player was waiting. `StartupWarmup` moves all of
  it to a background thread at boot; start scripts reserve the heap up front (-Xms = -Xmx)
  so G1 stops resizing mid-join.
- **Panel security hardening** — the token is no longer accepted in URLs (headers only),
  wrong-token grinding triggers a per-IP lockout (10 fails/10 min → 15 min), failed attempts
  log the source IP, responses carry Permissions-Policy and COOP on top of the existing CSP,
  and non-loopback binds / short tokens log warnings. Pre-login polling no longer counts
  against the brute-force counter.
- **Version reporting** — the panel, /wbinfo and the startup banner showed a hardcoded
  1.3.0 on release jars; they now report the built version.

### Notes
- The Windows `Waterbucket.exe` and the standalone `panel.zip` are gone from the release
  assets: the panel ships inside the jar, `start.bat` is the Windows launcher, and the plugin
  examples stay as `plugin-examples.zip`.
- **Roadmap:** fallback routing (automatic player failover on downstream death) is the
  headline feature of the next release.

## [1.4.1] - 2026-11-24

The performance release. A post-1.4.0 audit pass with eight regression-tested
bug fixes, two measured headless-efficiency optimizations, and a re-measured
comparison against the fork base whose harness bugs (stdin EOF console spin, JIT
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
- **Comparison re-measured, no panel favors upstream anymore**
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
on the release build, and the fork-base comparison was re-measured on the
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
- **Fork-base comparison, like-for-like** (`benchmarks/results.json`): same
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
- **Techniques from other proxies evaluated** (see `docs/PHASE2_BASELINE.md`): flush consolidation,
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
- Ghost players (upstream issue #266): stale registrations whose disconnect event was
  lost are evicted by a 15-second sweep; a re-registered fresh session stays untouched.
- Query handler reverse-DNS on the event loop (`getHostName()` → `getHostString()`).
- `TranslationContainer.getTranslated()` no longer crashes when the proxy instance does not
  exist yet (command settings built before boot).

### Changed
- Compression: the configured zlib level is applied per connection leg (upstream vs
  downstream); previously both legs inherited the upstream level.
- Flush consolidation on both network legs (the approach used by other proxies, 256 explicit flushes).

## [1.0.0] - 2026-11-21

### Added
- Rebrand of the fork base to WaterbucketPE (code, resources, terminal banner).
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
