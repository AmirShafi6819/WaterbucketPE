# Proxy-Wide Stability Hardening — Third Pass — Final Report

The two plugin-engineering passes (Phase 3, `88d93cf..692663e`; the Phase 5 second pass,
`84626ed..bfb5d0c`) left the plugin engine verified and measured. This third pass turned the
same evidence-driven method on the proxy itself: every subsystem the previous reports had
*documented* as limited but untouched, plus a full read of the boot, network, protocol, pack,
panel, scheduler and configuration layers. Zero features added, zero dependencies added, zero
public API signatures changed; every fix below was reproduced or reasoned from source before
it was written, and the diff was reviewed commit by commit.

## 1. The documented limitations this pass closed

The Phase 5 report honestly recorded one deferral: *"the shared `Configuration` subsystem
swallows parse errors (documented)"* — left alone because altering shared configuration code
exceeded the minimal plugin-scope repair of that pass. This pass repaired it properly: parse
errors are logged with their actual exception, an empty or non-mapping document yields an
empty usable config instead of a latent NPE, and every accessor honors the documented
default-value contract (`getString` no longer returns the string `"null"`, numeric accessors
fall back on garbage, `getBoolean` accepts quoted values, a scalar mid-path returns the
default). Ten contract tests pin the behavior.

## 2. Findings and fixes by subsystem

| Subsystem | Finding (reproduced before fixing) | Fix |
| --- | --- | --- |
| Boot / shutdown | Unsupported-Java refusal and failed primary bind both exited **status 0**; restart policies treated every failed start as clean | `shutdown(int status)`; both failure paths report 1 |
| Boot / shutdown | JLine terminal never restored after `end` (operator shell left in raw mode); console history unflushed; Bugsnag client never closed | console thread joined before terminal close; `ErrorReporting.close()` on shutdown |
| Boot | `debug(false)` never lowered the logger level (SNAPSHOT builds kept debug logging with `enable_debug: false`); bStats on a read-only FS aborted the boot; additional ports threw out of `InetSocketAddress`; version-properties stream leaked | symmetric debug flag, non-fatal metrics failure, port range check, stream closed |
| Network | Malformed RakPong → promise never completed (panel ping hung) + retained slice leaked; channel closed before pong → promise never completed | parse under try/tryFailure, pong released, `channelInactive` fails the promise |
| Network | Netty swallows `initChannel` failures → connect promise unresolved → permanent "connecting..." | init failures and pre-active closes fail the promise |
| Network | One throwing disconnect listener aborted the rest and broke the `channelInactive` chain (transfer recovery lost) | listeners isolated, iterated over an immutable copy |
| Network | `Properties.contains()` is the value-matching legacy method — a property whose *value* equaled `disableNativeEventLoop` disabled the native transport | `containsKey` |
| Network | RakNet pong escaped only the MOTD, so a `;` in a sub-motd shifted player counts / dropped fields | every text field escaped |
| Network | Query-expiry warnings with session token → sustained warning flood from spoofed sources | debug level, no token |
| Fallback | All-duplicate priority list spun `while(hasNext())` **forever on an event loop** | bounded per-call candidate pass, stale names skipped, volatile iterator |
| Fallback | Empty priorities list → `IndexOutOfBoundsException` out of the join path → disconnect **plus IP block** | null = documented "no server" |
| Protocol | Transfer-slot race loser kept tracking entities into shared sets until its channel closed | loser drops handler immediately, mirroring superseded-connection cleanup |
| Protocol | Transfer drain iterated fastutil synchronized wrappers while the old downstream loop still wrote them → `ConcurrentModificationException` mid-transfer | every drain under the wrapper's own monitor |
| Protocol | Encryption handshake over a transport without encryption (NetherNet) desynchronized the stream | refused with disconnect |
| Protocol | Duplicate `LoginPacket` re-ran the handshake, fired login events twice, built a second player (kicking the mid-login session) | sequential-dispatch re-entry guard |
| Protocol | Login exception detail (`class/method` names) printed on the client's disconnect screen | detail stays in the server log |
| Protocol | Codec-updater registry: live mutable list, plugin-callable from any thread, iterated by `buildCodec` | copy-on-write snapshots |
| Protocol | Hostile chunk payload → `Integer.SIZE / 0` or out-of-bounds bulk read on the downstream loop; falling_block without VARIANT → null unbox | range checks skip the rewrite cleanly; null VARIANT unhandled |
| Login | Missing `ServerAddress` / wrong-type `DeviceOS` killed the login with opaque errors | documented defaults |
| Packs | Duplicate pack UUID silently overwrote and leaked the loser's ZipFile; every manifest/cache rejection leaked the ZipFile opened in the constructor | duplicates rejected + closed; every rejection path closes |
| Packs | Empty modules list passed `validate()` then crashed the boot in `rebuildPackets`; zero-byte `manifest.json` reported load success | validation requires a typed module; null manifest = load failure |
| Packs | `registerPack`/`unregisterPack` mutated live maps read by event loops | snapshot + whole swap, mirroring `buildPacks` |
| Packs | Chunk index vs. stale offered info packet → `NegativeArraySizeException` on the event loop | out-of-range offsets refused |
| Packs | >2GB pack size wrapped to negative in `saveToCache`; single `FileChannel.read` sent zero-padded chunks | oversize not cached; short reads looped |
| Scheduler | Throwing `CallbackTask` callback skipped later callbacks and escaped `TaskHandler.cancel()` into the cancelling thread | callbacks isolated under the list monitor |
| Scheduler | `Task.cancel()` before scheduling → raw NPE into plugin code | no-op |
| Security | Throwing security listener (runs inside `initChannel`/packet handling) killed the connection it gated | contained; allow-check fails open, logged |
| Players | Same-display-name registration evicted the first player's name lookup; after the newcomer left, the still-online player was unreachable by name (commands, panel kick) | conditional removal + displaced-holder restore |
| Plugin engine | `findClass` entered directly (manager cache scans, public override) bypassed the per-name define lock → concurrent first loads died with `LinkageError` (caught by the sustained stress test) | define step under `getClassLoadingLock(name)`; no manager calls inside the lock |
| Panel | Dead proxy left widgets silently stale; no CSP; login button stacked clicks; empty tables rendered bare headers; removed servers accumulated in metrics state | connection-lost banner, "Updated" stat, explicit empty rows, disabled button, CSP header, per-tick pruning |
| Config / I/O | `FileUtils.copyResource` used `available()` + single read (truncation) and closed nothing — an empty target file blocked every later boot | looped copy, streams closed, target removed on failure |
| Config / I/O | Config files written with platform-default charset (non-ASCII corrupted on Windows-1252); `LangConfig` reload kept removed keys and keys with trailing spaces | explicit UTF-8; reload clears; keys trimmed |
| Config | Default permissions shipped two real usernames (anyone registering those names inherited transfer/list permissions) | empty default |
| Config | Compression level outside 0–9 aborted the boot (or every transfer); unknown algorithm NPE'd later; `server_type` routed through the address converter failed every non-empty entry; duplicate server names silently merged | clamped with warning; clear load failure; plain string; duplicates reported |
| Logging | Rolled `logs/*.log.gz` archives accumulated forever | 30-day retention in the rollover strategy |
| Harness | `bench.py` hardcoded a machine-specific JDK path; a startup failure deleted the log with the workdir; a relative jar path resolved inside the temp workdir | JAVA_HOME derived from `BENCH_JAVA`; failure surfaces the log tail; jar resolved absolute |

## 3. Validation

- Full suite after every stage and once more on the final tree: **58 suites / 328 tests
  executed / 0 failures / 0 errors / 1 skipped** (the env-gated benchmark) — up from
  55/303 by 25 tests (three new suites, two extended).
- The sustained concurrent class-loading stress test (the one that caught the
  `PluginClassLoader` race) re-run **5×** after the fix: clean every time.
- Clean `./gradlew build`: shaded jar `151,770,944` bytes,
  sha256 `91feca1e8d703bd7dd01a593439eebb53382b3279cfdd9bae020eadf3f6778bf`.
- End-to-end bounded benchmark against the hardened build
  (`benchmarks/results-harden.json`): startup median **2.22 s**; 400 RakNet pings, **0
  lost**, median RTT **0.33 ms**; concurrent pings **264 replies/s at 0% loss**; handshake
  churn median **3.44 ms** at 38% proxy CPU; **120/120 sessions held** at ~119 KB/session;
  churn RSS growth **4.93 MB → 1.41 MB** between leak rounds (no leak pattern).
- Static analysis: compiler + full suite; the project has no integrated analyzer (unchanged).

## 4. Compatibility assessment

All fixes preserve external contracts: every packet, event, command, permission and config
key behaves identically on the happy path. The behavior-narrowing changes affect only paths
that were incorrect or hazardous before (duplicate logins, duplicate packs, out-of-range
chunk requests, hostile chunk payloads). Two defaults changed deliberately and are called out
here rather than hidden: the shipped `permissions` map is now empty (shipping credentials
that anyone could claim is a defect, not a contract), and query-expiry noise moved to debug
level. Both example plugins and the full plugin regression suite are green.

## 5. Commit hashes and push status

| Commit | Content |
| --- | --- |
| `7d5cd3a` / `4bc6f96` | NetherNet identity material ignored (root-anchored) |
| `6887973` | Boot/shutdown honesty: exit status, terminal restore, reporting close |
| `c236bbe` | Network containment: malformed pongs, failed inits, listener floods |
| `dab06aa` | Round-robin fallback bound; empty priority lists refused (6 tests) |
| `1f4fbc6` | Transfer handoff, duplicate logins, hostile payloads |
| `f8dc673` | Pack manifest validation, duplicate packs, chunk-range checks (8 tests) |
| `9b0b20b` | Configuration accessor contracts (10 tests) |
| `2b6bb1c` | Callback/security-listener isolation, displaced player restore (1 test) |
| `8aa953e` | Plugin class definition under the per-name loading lock |
| `cd78d17` | Panel connection loss, empty states, CSP, metrics pruning |
| `258029b` | Portable harness + bounded final-phase driver + results |
| `c6e1a6b` | 30-day log retention |

Pushed to `origin/master` as regular fast-forward non-force pushes; all commits authored as
`AmirShafi6819 <AmirShafi6819@users.noreply.github.com>`.

## 6. Findings classification

- **Fixed and verified:** every row of §2 — each has either a regression test in the suite,
  a measured reproduction, or (for the harness/portability items) an executed before/after
  demonstration; the classloader race additionally survived a 5× stress rerun.
- **Investigated but not reproduced / not changed:** none in this pass — every candidate
  examined had a concrete failure path in source; items found already correct were left
  untouched per the no-change-without-defect rule.
- **Not fixed due to compatibility constraints:** none.
- **Not applicable:** the flush-consolidation pipeline, packet rewrite caches, scheduler
  internals and the Python bridge — audited again this pass, no verified defect, unchanged.

## 7. Remaining limitations (restated honestly)

No runtime plugin unload/hot reload; disabled Java plugins clean up their own
registrations; sync handlers run on event loops by design; async events allocate one thread
per in-flight dispatch; GraalPy interpreter-mode overhead is inherent; in-process plugins
are not sandboxed (out of scope); the direct `findClass` door now serializes per loader
under the define lock, which is the same monitor `loadClass` always held — cross-loader
scans of many loaders remain sequential by design.
