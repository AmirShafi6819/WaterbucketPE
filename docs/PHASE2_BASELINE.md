# Phase 2 Baseline Report

This report was produced before any Phase 2 production change. It records the state Phase 2
starts from, the evidence for every planned improvement, and the Velocity techniques that
were evaluated. Each claim carries a file and line reference into the tree at commit
`59c244f884a4a88a6cb1fbeba4d19664b45522c9` ("Release 1.2.0").

## 1. Repository state at Phase 2 start

- Branch `master`, working tree clean, HEAD `59c244f` (tagged `v1.2.0`), 846 commits.
- Remotes: `origin` (AmirShafi6819/WaterbucketPE). No stray branches.
- Build: `./gradlew build` succeeds on a Java 17 toolchain (Gradle 9.6.1, shadow jar
  `Waterbucket.jar`, 15.2 MB). Build verified locally; see §3.
- Tests: `./gradlew test` — 263 tests in 45 classes, 0 failures, 0 errors, 0 skipped.
- Phase 1 deliverables present: rename, web panel (`panel/`), Python plugin runtime
  (`plugin/python/`), benchmark harness (`benchmarks/`), English/Persian plugin docs,
  release launcher tooling, `v1.0.0`/`v1.1.0`/`v1.2.0` tags.

## 2. Benchmark environment (used again for the after-Phase-2 run)

- Shared 2-core Intel Xeon VM, Linux 5.10, 4 GB RAM.
- Java: project toolchain JDK 17 (Temurin 17.0.21.1).
- Harness: `benchmarks/bench.py` (unchanged methodology, `benchmarks/METHODOLOGY.md`).
- Phase 2 A/B pair: the same harness runs the **pre-Phase-2 jar of this repository**
  (commit `59c244f`, sha256 `8a3ff4434e208c246a3ac130aa66dd42d7520ec16611426eae8c29db3da82ec7`)
  against the **post-Phase-2 jar** of this repository. Both sides of the A/B therefore use
  identical code, flags and environment; the upstream WaterdogPE comparison stays available
  by re-running the harness as documented.

## 3. Architecture summary (as inspected, not as designed)

**Threading.** One worker `EventLoopGroup` sized to core count, shared by the RakNet
listener (one channel per core via `SO_REUSEPORT`), all client sessions and all downstream
dials (`ProxyServer.java:236-241`, `RakNetInterface.java:66-98`, `BedrockServerInfo.java:68`).
An upstream leg and its downstream leg normally live on different loops of the same pool;
every cross-leg handoff must therefore be memory-visible (this drives findings C1-C4).

**Relay path, client -> server.** RakNet frame decode (zero-copy filter,
`RakNetFrameCodec.java:45-58`) -> `TransportFrameCodec` wraps the compressed body as a
retained slice (`TransportFrameCodec.java:48`) -> `ProxiedCompressionCodec` decompress ->
`BedrockBatchDecoder` retained-slices each packet (`BedrockBatchDecoder.java:39-45`) ->
`BedrockPacketCodec.decode` parses the header and fully decodes client-bound packets
(`BedrockPacketCodec.java:112-131`) -> `ProxiedBedrockPeer.channelRead` ->
`BedrockServerSession.onBedrockBatch` -> `ProxyBatchBridge.onBedrockBatch`
(`ProxyBatchBridge.java:60-94`). `ConnectedUpstreamHandler.sendProxiedBatch` retains the
batch across loops to the downstream connection (`ConnectedUpstreamHandler.java:57-61`).

**Relay path, server -> client.** Downstream packets are header-parsed only; decode is lazy
per packet in `ProxyBatchBridge.decodePacket` (`ProxyBatchBridge.java:113-135`); undecodable
packets are forwarded raw with log-once semantics. Untouched batches take a full fast path:
`BedrockBatchEncoder` retains and skips unmodified batches (`BedrockBatchEncoder.java:34-37`),
`ProxiedCompressionCodec` passes the already-compressed buffer through, `RakNetFrameCodec`
re-frames with a composite buffer around the original bytes — a relayed batch travels to the
client without decompressing or copying packet payload.

**Server switch.** `ProxiedPlayer.connect` -> async `ServerPreConnectEvent` (60 s timeout) ->
dial -> `SwitchDownstreamHandler.handle(StartGamePacket)` claims the transfer slot atomically
(`RewriteData.trySetTransferCallback`, `RewriteData.java:127-133`), tears the old leg down,
resets rewrite state, injects client cleanup, plans the dimension change, queues new-server
packets in `PacketQueueHandler` (MPSC, 256 batches / 8000 packets cap), and completes on the
client's dimension-change ACK(s) via `TransferCallback` phases. First server join uses
`InitialHandler`, which performs the same rewrite setup but **without** the superseded-connection
guards (finding C5).

**Already present from earlier phases** (so Phase 2 does not redo them): flush consolidation
on both legs (`FlushConsolidationHandler(256, true)`, `ProxiedSessionInitializer.java:94`),
per-leg zlib levels, MPSC queues with overflow accounting (`PacketQueueHandler.java:76-108`),
MTU-aware batch splitting, compressed-buffer reuse across legs
(`BedrockClientConnection.sendPacket:102-108`), DNS off the event loops
(`ServerInfo.java:48-108`), O(1) player name index (`PlayerManager.java:36-41`), ghost-player
sweeper, event-handler snapshot maps (`EventHandler.java:42-49`).

## 4. Velocity study: what was compared

Sources read: `PaperMC/Velocity` master (clone of 2026-11-22), specifically
`network/ConnectionManager.java`, `network/TransportType.java`,
`network/ServerChannelInitializer.java`, `connection/MinecraftConnection.java`,
`connection/backend/BackendPlaySessionHandler.java`, `connection/client/ClientPlaySessionHandler.java`.

| Velocity technique | WaterbucketPE status | Decision |
|---|---|---|
| Event loops: separate boss/worker groups, native transport | Present in equivalent form: RakNet binds one datagram channel per core with `SO_REUSEPORT` onto core-count worker loops (`RakNetInterface.java:66-98`, `ProxyServer.java:236-241`); native epoll/kqueue auto-selected (`EventLoops.java:37-47`) | Keep |
| Flush consolidation | Velocity flushes once per read cycle via `channelReadComplete` -> `readCompleted()`; WaterbucketPE already installs `FlushConsolidationHandler(256, true)` on both legs (`ProxiedSessionInitializer.java:94`, `ProxiedClientSessionInitializer.java:85`) | Keep existing |
| Fast paths for untouched packets | Present (`BedrockBatchEncoder.java:34-37`, `BedrockPacketCodec.encode:62-76`, compression pass-through) | Keep |
| **Cross-leg backpressure**: `channelWritabilityChanged` -> `setAutoRead(writable)` on the opposite leg (`BackendPlaySessionHandler.java:520-534`, `ClientPlaySessionHandler.java:577-599`, `MinecraftConnection.java:231-234`) | **Missing.** Neither relay leg pauses reading when the opposite leg is unwritable. | **Evaluated, rejected for the RakNet legs** — pausing reads on a RakNet channel also pauses ACK generation, so a stalled leg would stop ACKing and drift toward the 10 s session timeout (`RAK_SESSION_TIMEOUT`, `RakNetInterface.java:83`) instead of backpressuring gracefully. Velocity's TCP legs retransmit safely under autoRead-off; RakNet legs do not. Documented here rather than forced in. The bounded-queue protections that do exist (`PacketQueueHandler` cap, RakNet transport congestion control) remain the primary defense. |
| Per-message handler dispatch on the session handler | Present in equivalent form (`ProxyBatchBridge` + `ProxyPacketHandler`) | Keep |
| Composite/retained-slice buffer discipline | Present (`BedrockBatchEncoder.concat`, `RakNetFrameCodec.frame`, `TransportFrameCodec.decode`) | Keep; one allocation reduction adopted (finding C8) |
| Bounded queues with kick-on-overflow | Present for the transfer queue (`PacketQueueHandler`); **missing** for `ClientPacketQueue` single-packet queue (finding C9) | Adopt bound |

## 5. Findings (all verified in source at `59c244f`)

Grouped by the mission's review areas. "Fix" entries are the Phase 2 work items.

### Stability / concurrency (findings C1-C7)

- **C1 — `BlockPalette.paletteCache` race.** Static `Int2ObjectOpenHashMap` with
  contains->get->put (`BlockPalette.java:35,47-56`), mutated from every player's downstream
  loop during StartGame (`InitialHandler.java:130`, `SwitchDownstreamHandler.java:175`).
  Two loops inserting concurrently can corrupt the map; also unbounded growth per distinct
  palette hash. Fix: concurrent, bounded cache. Regression test to follow.
- **C2 — `RewriteData` cross-loop fields are not memory-visible.** `entityId`,
  `originalRuntimeEntityId`, `originalUniqueEntityId`, `dimension`, `spawnPosition`,
  `rotation`, `gameRules`, `immobileFlag` (`RewriteData.java:44-106`) are written in
  `SwitchDownstreamHandler.handle(StartGamePacket)` on the *new downstream loop* and read by
  rewrites on the *upstream loop* (client->server packets) without any happens-before edge.
  Fix: make the cross-loop fields volatile (write cost: once per server switch).
- **C3 — `BedrockClientConnection.compressionStrategy` cross-loop.** Written by
  `setCompressionStrategy` on the downstream loop (`BedrockClientConnection.java:137-147`),
  read by `sendPacket` from the upstream loop (`:102-106`). Fix: volatile.
- **C4 — `ProxyBatchBridge.handler` cross-loop.** `setHandler` is called from the upstream
  loop (`BedrockClientConnection.setPacketHandler:235-241`, reached from
  `TransferCallback.onTransferPhase2Completed`), while the new downstream loop concurrently
  reads `this.handler` inside `onBedrockBatch` (`ProxyBatchBridge.java:47-49,99`). Fix: volatile
  handler field (bridge is otherwise loop-confined).
- **C5 — `InitialHandler.handle(StartGamePacket)` lacks the superseded-connection guards.**
  `SwitchDownstreamHandler` re-checks `isConnected` and `pendingConnection == connection`
  (`SwitchDownstreamHandler.java:116-131`); `InitialHandler` does neither
  (`InitialHandler.java:114-161`). A stale initial dial whose StartGame arrives after a
  fallback began overwrites `clientConnection`, rewrite state and fires
  `InitialServerConnectedEvent`, hijacking the player mid-recovery. Fix: mirror the guards.
- **C6 — unsynchronized player state.** `permissions` plain map (`ProxiedPlayer.java:135`)
  mutated by plugins from any thread and read from event loops; `pluginPacketHandlers`
  plain `ObjectArrayList` (`:184`) iterated per packet while plugins may add/remove.
  Fix: concurrent/synchronized equivalents without API change.
- **C7 — pipeline mutation from foreign loops.** `BedrockServerSession.setTransferQueueActive`
  and `discardTransferQueue` (`BedrockServerSession.java:108-140`) mutate the *upstream*
  pipeline; `addBefore` is called from the new downstream loop
  (`SwitchDownstreamHandler.java:311`), `remove` also from downstream failure paths.
  Fix: confine the mutation to the upstream event loop.

### Resource efficiency (findings C8-C10)

- **C8 — per-packet temporary allocation in `BedrockPacketCodec.decode`.** Every packet of
  every batch allocates a throwaway `retainedSlice()` just to parse the header
  (`BedrockPacketCodec.java:117`); the parse only advances a reader index, so the shared
  buffer can be used directly with index save/restore. Removes one allocation per packet per
  batch on both legs.
- **C9 — `ClientPacketQueue` is unbounded and adds fixed 50 ms latency.** Single packets
  queue into an unbounded MPSC and drain on a fixed 50 ms tick
  (`ClientPacketQueue.java:34,40`), so every proxy-generated handshake/transfer packet waits
  up to 50 ms (roughly 300-400 ms added across a transfer's handshake) and a misbehaving
  plugin can grow the queue without limit. Fix: bounded queue with PacketQueueHandler-style
  overflow handling plus a short adaptive drain delay scheduled only while packets are queued.
- **C10 — scheduler pile-up of slow async repeating tasks.** A repeating async task is
  re-armed immediately after dispatch, not after completion (`WaterbucketScheduler.java:133-141`);
  with the unbounded `SynchronousQueue` executor one execution slower than its period
  multiplies threads indefinitely. Fix: skip re-dispatch while the previous execution of the
  same task is still running (no semantic change for tasks faster than their period).
  Related: `tickProcessor` catches only `Exception`, so an escaped `Error` silently stops the
  tick loop forever (`ProxyServer.java:407-412`); fixed to log and continue.
- **C11 — `ConnectionThrottle` eviction NPE window.** Between `putIfAbsent` returning null
  and the follow-up `get` (`ConnectionThrottle.java:51-54`), a max-size eviction (5000 IPs
  under flood) can null the entry, NPE-ing on the event loop. Fix: handle the evicted case
  explicitly (fail closed, consistent with throttle semantics).
- **C12 — plugin class caches mutated from arbitrary threads.** `pluginClassLoaders` and
  `cachedClasses` are plain fastutil array maps (`PluginManager.java:52-54`) written by
  `PluginClassLoader.findClass` from whichever thread first loads a class
  (`PluginClassLoader.java:59`) and iterated concurrently in `getClassFromCache`
  (`PluginManager.java:290-306`). Fix: concurrent maps.
- **C13 — small resource leaks/bugs.** `ClientEventHandler.channelInactive` does not call
  `super` (`ClientEventHandler.java:37-39`); `PanelMetrics.LogTailAppender` allocates a
  `SimpleDateFormat` per log line (`PanelMetrics.java:233-236`); `EventPriority.values()`
  clones its array on every event dispatch (`EventHandler.java:67,94,102`). All fixed.
- **C14 — stale TODO/doc drift.** `ProxyServer.java:343` "support downstream compression
  level too" is already implemented (`ProxiedClientSessionInitializer.java:89-90`,
  `BedrockClientConnection.java:76-79,127-132`) — comment removed; `ServerTransferEvent`
  javadoc references a nonexistent `PreTransferEvent` — corrected.

### Rejected (documented, not implemented)

- **Velocity-style autoRead backpressure on RakNet legs** (see §4 table): unsound for
  RakNet's ACK-driven reliability; would trade a slow-queue condition for session timeouts.
- **Zero-copy decoding of client-bound packets:** client packets must be fully decoded
  (commands, chat, login, rewrite inputs); there is no safe "skip decode" subset that
  preserves `PlayerChatEvent`/command/rewrite behavior.
- **Bounded event executor queues:** converting the async-event executor to a bounded queue
  would route event dispatch onto caller (event-loop) threads under load, blocking network
  I/O — worse than the status quo. The scheduler pile-up fix (C10) removes the actual
  growth driver.

## 6. What Phase 2 will claim at the end

Only what is demonstrated: build results, full test-suite results, per-change regression
tests, and a before/after benchmark run of the same harness on the same VM with both jars
built from this repository. Metrics that the harness cannot measure (e.g. per-transfer
handshake latency) are documented as design improvements with unit tests, not as benchmark
wins. No claim is made about upstream WaterdogPE beyond re-running the documented harness.
