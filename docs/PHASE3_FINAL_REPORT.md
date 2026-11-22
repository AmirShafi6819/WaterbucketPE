# Phase 3 — Plugin Engineering, Runtime Stability and Deep Architectural Audit — Final Report

Baseline: commit `3649a8b` (post-Phase-2). Final code: the commits listed in §21.
Scope: the existing Plugin Engine only — zero new features, zero new dependencies
(SnakeYAML, GraalPy, fastutil, log4j, JUnit/Mockito all pre-existing), zero public-API
signature changes. The directive's mission text calls this phase "Phase 5"; the repository's
sequential numbering (PHASE1/PHASE2 docs) and the directive header use "Phase 3" — this
report follows "Phase 3".

Reading path: `docs/PHASE3_PLUGIN_ARCHITECTURE.md` (Stage A map) →
`docs/PHASE3_PLUGIN_AUDIT.md` (Stages B–G findings, reproductions, fixes) → this report.

---

## 1. Complete plugin architecture diagram

Full diagram and per-operation tables: `PHASE3_PLUGIN_ARCHITECTURE.md` §2/§3. Compact form:

```
plugins/ scan (boot thread) ──► PluginManager (registry: pluginMap, pluginClassLoaders,
                                  cachedClasses, missingClasses, pluginsToLoad, enabledOrder)
   jar  ─► PluginLoader ─► PluginClassLoader (URLClassLoader, parent-first,
   │        │                global cross-plugin cache, proxy-namespace guard)
   │        └─► waterbucket.yml|plugin.yml ──► PluginLoader.parseDescriptor (SafeConstructor)
   folder ─► PythonPluginLoader ─► PythonPlugin (GraalPy Context, lazily created at onStartup)
   ▼
loadAllPlugins ─► loadPlugin ─► plugin.onStartup() ─► pluginMap[name] (only after success;
                                                  failure releases loader + jar + cache)
   ▼
boot ─► enableAllPlugins ─► enablePlugin (synchronized, chain-local cycle set)
        ─► plugin.setEnabled(true) ─► onEnable() (subscriptions, commands, scheduled tasks)
   ▼
runtime ─ EventManager.callEvent ─┬─ sync: inline on caller thread (event loop),
        │                         │        priorities LOWEST→HIGHEST, per-handler isolation
        │                         └─ @AsyncEvent: threadedExecutor, CompletableFuture
        ─ PythonPlugin.subscribe/registerCommand/schedule* (tracked, auto-detached on disable)
        ─ loadServiceProvider: ServiceLoader across app + all plugin classloaders
   ▼
shutdown ─ disableAllPlugins (reverse enable order) ─ interfaces ─ scheduler ─ event executor
```

## 2. Plugin loading and execution flow

Discovery → descriptor parse (hardened) → classloader registration (dedup by name) →
reflective main-class construction → `Plugin.init` (logger, data folder) → `onStartup` →
registry insert → dependency-ordered enable (`onEnable`) → active execution (events,
commands, scheduler) → reverse-order disable → process exit (no runtime unload — documented
contract). Every step's entry point, data structures, thread context, error handling, cleanup
and tests are tabulated in `PHASE3_PLUGIN_ARCHITECTURE.md` §3.

## 3. Runtime architecture

- **Java runtime:** one `PluginClassLoader` per jar, parent-first delegation against the
  proxy classloader, cross-plugin sharing through a global `ConcurrentHashMap` cache,
  `dev.waterbucket.` namespace shadowing refused. No isolated JVM, no redefinition, no
  per-plugin threads (existing architecture preserved).
- **Python runtime:** GraalPy 25.0.5 `Context` per plugin (`allowAllAccess`, `EmulateJython`),
  created once at first `onStartup` (measured 4.2 s interpreter init), script globals
  `plugin`/`proxy`/`logger`, folder on `sys.path` via a bindings member. GraalPy serializes
  context execution across threads (verified by test).
- **Runtime independence:** no shared mutable state beyond the manager registry; one failing
  plugin cannot corrupt the other runtime (failure drops only that plugin, and since Stage E
  also releases its classloader).

## 4. Java runtime findings

| Finding | Classification |
| --- | --- |
| Incompatible class-file version (an `Error`) escaped `loadPluginJAR` and crashed the whole boot | **Fixed and verified** |
| Failed plugin kept its classloader registered, jar handle open, classes globally cached and servable to other plugins | **Fixed and verified** |
| Loader-local class cache (`Object2ObjectOpenHashMap`) mutated from event-loop threads — race real by construction; 16×400 stress did not observe the manifestation | **Fixed defensively** (ConcurrentHashMap + `findLoadedClass` guard); stress test kept |
| Missing-class lookup cost (194 µs/miss at 32 loaders, repeated per lookup) | **Fixed and verified** (−88 %) |
| Reflection overhead: one reflective construction per plugin, no per-dispatch reflection | Not applicable (no defect) |

## 5. Python runtime findings

| Finding | Classification |
| --- | --- |
| Scheduled tasks not tracked — a disabled plugin's repeating task kept firing into the closed interpreter until shutdown | **Fixed and verified** (tracked + cancelled on disable) |
| `subscribe`/`registerCommand` after disable registered live resources into the closed context (command leaked forever) | **Fixed and verified** (refused with warning) |
| `context.close(true)` vs an in-flight async handler → logged `PolyglotException` | Not applicable (accepted by design; disable ordering documented) |
| Reference ownership, module caching, interpreter init/shutdown, thread serialization | Not applicable (no defect; verified by existing + new tests) |
| Bridge overhead (~31 µs/dispatch interpreter mode) and 4.2 s one-time init | Not applicable (inherent to the runtime choice; GraalVM-JDK JIT note already documented in PLUGINS.md) |

## 6. Lifecycle findings

| Finding | Classification |
| --- | --- |
| Shared `enabling` HashSet: concurrent enable chains cross-rejected healthy dependencies as "circular dependency" | **Fixed and verified** (manager-serialized enable, chain-local set) |
| `enabledOrder` plain list lost entries under concurrent enables (wrong reverse-disable order) | **Fixed and verified** (`CopyOnWriteArrayList` + serialized enable) |
| `pluginMap`/`pluginClassLoaders` non-concurrent maps mutated by public runtime APIs while read from event loops / panel thread | **Fixed and verified** (concurrent maps) |
| `loadAllPlugins` live-list iteration could consume appends forever (latent; became observable through the P3-4 release path during Stage E) | **Fixed and verified** (snapshot iteration) |
| Registry retains disabled plugins after disable (no unload API) | Not fixed due to compatibility constraints — existing lifecycle contract, documented |
| Duplicate descriptor names at scan; failed-enable rollback; reverse-order disable | Not applicable (already correct from prior phases; re-tested green) |

## 7. Event dispatch analysis

- Correctness (§6.2/§6.3): priority ordering, same-priority registration order, duplicate
  suppression, per-handler `Throwable` isolation, unsubscribe semantics, concurrent
  subscribe/dispatch consistency — all covered by tests that stayed green through every
  change (behavior fingerprint).
- Performance (§6.1/§13): per-handler dispatch ≈ 7 ns; 320 handlers ≈ 2.3 µs; async round
  trip ≈ 3–6 µs; subscribe/unsubscribe ≈ 1–3 µs; allocations 208 B/op at 320 handlers.
  **No measured bottleneck in the dispatch path → no dispatch code changed.**
- Thread-per-concurrent-async-event executor: restated as an accepted, documented limit
  (bounded queue already rejected in Phase 2 — it would push blocking into event loops).

## 8. API compatibility assessment

- No public signature changed; no method renamed/removed; parameter and return types intact.
- Both shipped example plugins compile against the production API from their real sources and
  run end-to-end (`PluginCompatibilityTest`): load, enable, cross-runtime subscriptions,
  command execution (Java + full GraalPy interop with sibling import), payload-preserving
  dispatch, and the documented disable asymmetry (Python auto-detaches; Java cleans up itself).
- Deliberate behavioral refinements (all narrowing failure paths, never widening): broken
  plugins no longer keep a registered loader; unsafe descriptors (`!!` tags) are rejected;
  `saveResource` traversal returns false; post-disable registrations are refused;
  `subscribe`/`callEvent` null arguments now raise `IllegalArgumentException` instead of a
  deep NPE. Each is documented here and in the audit doc.

## 9. Thread-safety findings

Verified/concurrency-audited: event handler snapshots (existing), classloader caches
(fixed), registry maps (fixed), enable serialization (fixed), scheduler task guard and
cancellation flags (existing, re-tested), GraalPy context serialization (existing, tested),
descriptor parsing confined to the boot thread. Blocking plugin operations on event loops:
sync events run inline by design (documented contract, unchanged); Python long work goes
through `schedule*`. No new thread pools introduced; no concurrency level changed.

## 10. Memory and resource ownership findings

- **Fixed:** failed plugins release loader + jar handle + global cache entries; Python
  scheduled tasks cancelled on disable; negative-lookup set cleared on loader registration.
- **Verified clean:** after a Python enable→disable cycle, 0 retained polyglot instances
  (self-attached `jcmd GC.class_histogram` diff, capture asserted in the harness).
- **Documented:** disabled-but-loaded plugins remain in the registry (no unload API);
  failed plugins leave their per-name Log4j LoggerConfig until process exit (bounded,
  replaced per name); anonymous-class logger configs added with a stable binary name.

## 11. Security findings

- **Fixed and verified:** SnakeYAML 1.32 global-tag instantiation during descriptor parsing
  (CVE-2022-1471 class — probe class instantiated pre-fix, zero post-fix; jar + folder paths
  share the hardened parser); wrong-typed descriptor fields no longer coerce into garbage;
  `saveResource` output confinement; failed-loader release closes the jar handle.
- **Fixed (error handling):** plugin `Error`s contained at the load, lifecycle and command
  boundaries; clear argument validation on the event API; visible data-folder creation
  failures; anonymous plugin classes no longer crash `buildLogger` (found while testing).
- **Documented limitations (unchanged by design):** plugins run in-process with full JVM
  rights — a sandbox, permission system or malware scanner would be a new product and is
  out of scope; in-process handlers can never be fully isolated from the host.

## 12. Verified root causes

1. Boot crash on new-JVM jar: `catch (Exception)` at the load boundary misses `Error`.
2. Failed-plugin retention: release path did not exist (no `close()`, no unregistration).
3. False cycle rejection: cycle set shared across chains instead of per-chain.
4. Infinite load loop: live-list iteration consumed `registerClassLoader` appends after a
   failed load released its loader (introduced by the P3-4 fix, caught by the Stage E run).
5. YAML gadget: `CustomClassLoaderConstructor` honors global tags in SnakeYAML 1.32.
6. Python task leak: `TaskHandler` dropped by the bridge; no cancel on disable.
7. 194 µs missing-class misses: full cross-loader jar scan + exception fill per lookup.
8. Anonymous-class NPE: `getCanonicalName()` null for anonymous plugin classes.

## 13. Files and functions modified

Production (8 files, +≈460/−83):
- `plugin/PluginManager.java` — registry maps concurrent; failed-plugin release; serialized
  enable with chain-local set; snapshot load loop; hardened `yamlLoader`; negative-lookup cache.
- `plugin/PluginLoader.java` — `loadPluginJAR` catches `Throwable`; new `parseDescriptor`.
- `plugin/PluginClassLoader.java` — concurrent class cache; `findLoadedClass` guard;
  negative-cache consult.
- `plugin/Plugin.java` — `saveResource` confinement; `mkdirs` warning; anonymous-class logger.
- `plugin/python/PythonPlugin.java` — scheduled-task tracking/cancellation; post-disable
  guards on subscribe/register/schedule.
- `plugin/python/PythonPluginLoader.java` — descriptor parsing routed through the hardened parser.
- `event/EventManager.java` — argument validation.
- `command/SimpleCommandMap.java` — `executeInternal` catches `Throwable`.

Tests (6 files, +≈1,400 lines): `PluginFailureIsolationTest`, `PluginDescriptorParsingTest`,
`PythonRuntimeCleanupTest`, `PluginCompatibilityTest` (new); `PluginLifecycleTest` (extended);
`Phase3PluginEngineBenchmark` (new harness). Docs: 3 files (+656 lines). Benchmarks: 2 result
files.

## 14. Fixes implemented

Eleven fixes total (4 security/boundary, 5 stability/lifecycle, 2 resource ownership, plus
the load-loop and anonymous-class fixes found during the stages) — all listed with root
causes and evidence in §4–§6/§11 and `PHASE3_PLUGIN_AUDIT.md`. Every fix carries a regression
test; no subsystem without a verified defect was modified (the event dispatch engine, the
scheduler and the command maps were audited and left unchanged beyond the two one-line
boundary catches).

## 15. Plugin loading benchmarks

32 generated jars, identical harness before/after (`benchmarks/results-plugin-phase3-*.json`):

| Scenario | Before | After |
| --- | --- | --- |
| Scan (descriptor parse, 32 jars) | 90 ms | 140 ms |
| Load (classload + init, 32 plugins) | 519 ms | 440 ms |
| Enable (32 plugins) | 2.4 ms | 2.1 ms |

Within run-to-run variance; scan/load differences are noise-dominated (single-jvm runs,
documented rounds in the JSON). Per plugin ≈ 16 ms is dominated by classloader creation.

## 16. Event dispatch benchmarks

| Scenario | Before | After |
| --- | --- | --- |
| Sync dispatch, 0 handlers | 170 ns | 22 ns |
| Sync dispatch, 5 handlers × 5 priorities | 79 ns | 81 ns |
| Sync dispatch, 40 handlers | 342 ns | 341 ns |
| Sync dispatch, 320 handlers | 2 328 ns | 2 336 ns |
| Cancelled sync event | 30 ns | 43 ns |
| Completable sync event | 70 ns | 75 ns |
| Subscribe+unsubscribe round trip | 2.6 µs | 3.0 µs |

All differences inside observed run-to-run noise; per-handler cost ≈ 7 ns unchanged.

## 17. Runtime overhead measurements

| Scenario | Before | After |
| --- | --- | --- |
| Python script load (Context init) | 4.22 s | 4.21 s |
| Python no-op handler dispatch | 33 µs | 31 µs |
| Python bridge delta (320 Java + 1 Python) | 17.6 µs | 8.3 µs (variance-dominated; both ≈ python call cost) |
| Async event round trip | 6.3 µs | 3.1 µs |
| Missing-class lookup (32 loaders) | **194 µs** | **23.8 µs** (−88 %) |

Python numbers are inherent to interpreter-mode GraalPy (see §5); no Java-path regression
from any Phase 3 change.

## 18. Memory profiling results

Self-attached `jcmd GC.class_histogram` before/after a Python plugin's disable (post-`System.gc()`):
**0 retained instances** across all `org.graalvm`/truffle classes (capture validity asserted
in the harness). Allocation rate of the sync dispatch path: 208 B/op at 320 handlers (event +
iteration), unchanged. Post-fix resource releases (loader, jar handle, cache entries, task
handlers) are covered by functional tests rather than heap assertion — noted as such.

## 19. Plugin compatibility test results

- `PluginCompatibilityTest`: Java + Python example plugins, both runtimes together — **green**
  before any change and after every change (run again in the final validation).
- Full suite: **53 suites / 294 tests / 0 failures / 0 errors / 1 skipped** (the benchmark,
  gated behind `PHASE3_BENCH=true`) on the final clean build.
- Unclaimed compatibility: no third-party plugin was available for testing; compatibility is
  asserted only for the shipped examples and the documented API contract.

## 20. Remaining limitations

1. No runtime unload/hot reload of plugins (existing contract; adding it would be a feature).
2. Disabled plugins stay registered; Java plugins must unregister their own commands/links
   (documented; Python auto-detaches).
3. Sync event handlers run on Netty event loops — plugins must not block there (documented).
4. Async events allocate one thread per in-flight dispatch (Phase 2 trade-off, restated).
5. GraalPy interpreter-mode overhead (~30 µs/handler; 4.2 s init) is inherent on a standard
   JDK; a GraalVM runtime would JIT it (documented, not changed).
6. A missing class is negatively cached until a new loader registers; rewriting a plugin jar
   while the proxy runs (unsupported anyway) would not be picked up.
7. In-process plugins are not sandboxed (explicitly out of scope).

## 21. Commit hashes

| Stage | Commit |
| --- | --- |
| A — architecture map | `88d93cf` |
| B — runtime audit | `9dc11ee` |
| C — event engine measurement | `948b054` |
| D — API/compatibility verification | `adcf0fa` |
| E — stability fixes | `b2f4165` |
| F — security hardening | `42cc890` |
| G — negative-lookup cache | `ad0a9b5` |
| H — this report | (see git log) |

## 22. Push status

Pushed to `origin/master` (AmirShafi6819/WaterbucketPE) as regular non-force pushes after the
final clean-build validation; branch state and hashes verifiable upstream. All commits are
authored as `AmirShafi6819 <AmirShafi6819@users.noreply.github.com>`.

---

### Findings classification summary

- **Fixed and verified:** P3-4, P3-5, P3-7, P3-8, P3-9, P3-10, P3-14, P3-18, P3-19, P3-20,
  enable-chain serialization, load-loop, command `Error` containment.
- **Fixed but not fully verified:** P3-2 (race fixed by construction and stress-tested, but
  the failure mode was never observed in a run).
- **Investigated but not reproduced:** P3-6 (unknown keys were already tolerated — no change).
- **Not fixed due to compatibility constraints:** registry retention of disabled plugins
  (existing lifecycle contract).
- **Not applicable:** dispatch-engine internals, scheduler internals, Python bridge cost,
  sandboxing (all audited, no verified defect or explicitly out of scope).
