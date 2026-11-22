# Phase 3 — Plugin Engine Audit (Stages B/C/D)

Working record of the audit stages. Each finding carries its reproduction evidence or its
honest non-reproduction. Baseline: commit `3649a8b`; Stage A map:
`docs/PHASE3_PLUGIN_ARCHITECTURE.md`.

Reproduction tests live in:

- `plugin/PluginFailureIsolationTest.java` (Java runtime)
- `plugin/PluginDescriptorParsingTest.java` (metadata trust boundary)
- `plugin/python/PythonRuntimeCleanupTest.java` (Python runtime ownership)

They were executed against the unmodified production code first; the failure output below is
from those runs. They are committed together with the fixes that make them pass, so `master`
stays green at every commit.

---

## Stage B — Runtime audit

### B.1 Java plugin runtime

The runtime is a single `PluginClassLoader` (URLClassLoader, parent = app classloader) per
jar, parent-first delegation, no isolated JVM, no per-plugin threads. Lifecycle, ownership,
concurrency and compatibility were each exercised with executable reproductions.

| ID | Finding | Classification | Evidence |
| --- | --- | --- | --- |
| P3-18 | A plugin jar compiled for a newer JVM aborts the whole proxy boot: `PluginLoader.loadPluginJAR` catches `Exception` (PluginLoader.java:80) but `UnsupportedClassVersionError` is an `Error` and escapes `loadAllPlugins` into boot. One bad jar = boot crash. | **Fixed and verified** (Stage E) | `PluginFailureIsolationTest.pluginCompiledForANewerJvmDoesNotCrashTheBoot` failed on unmodified code with `Unexpected exception thrown: java.lang.UnsupportedClassVersionError ... class file version 100.0` |
| P3-4 | A plugin that fails after its classloader was registered (constructor/main-class failure, `onStartup` throw, duplicate-name skip) keeps the loader registered, the jar handle open, and its classes in the global cache; `getClassFromCache` keeps serving classes of a broken plugin to every other plugin. No `close()` on any failed loader. | **Fixed and verified** (Stage E) | `PluginFailureIsolationTest.pluginWhoseMainClassFailsToConstructIsFullyReleased` failed: `the failed plugin's classloader must not stay registered ==> expected: <false> but was: <true>` |
| P3-2 | `PluginClassLoader.classes` (`Object2ObjectOpenHashMap`) is mutated in `findClass` from any thread that first resolves a class — including Netty event loops (PluginClassLoader.java:32, 61). The race window is real by construction (concurrent put/resize on different keys), but its manifestation needs a lost entry *and* a direct `findClass(name, false)` call from `getClassFromCache`; a 16-thread × 400-class stress did not observe it. | **Investigated but not reproduced**; fixed defensively (Stage E) — the data structure is provably not thread-safe and the replacement is behavior-preserving | Stress `PluginFailureIsolationTest.concurrentFirstLoadsShareClassesWithoutLinkageErrors` passed both before and after; kept as a behavioral guard |
| P3-14 | Missing-class lookups cost one jar scan + stack-trace fill per miss across every plugin loader (`getClassFromCache`, PluginManager.java:297–313). Measured in Stage C before any decision. | Measured in Stage C | see Stage C |

Also audited, no defect found: parent-first delegation and the `dev.waterbucket.` shadowing
guard (PluginClassLoader.java:45–47); cross-plugin class sharing through the global cache
(PluginManager.java:297–313); duplicate class definitions are serialized by the JVM's
per-class-name `loadClass` lock plus `findLoadedClass` in the same path; reflection usage is
one reflective construction per plugin (PluginLoader.java:77); no thread management exists in
the runtime itself (plugin code runs on the caller's thread).

### B.2 Python plugin runtime (GraalPy 25.0.5)

One GraalPy `Context` per plugin, created at first `onStartup`, closed (interrupting) in
`onDisable` after handlers/commands are detached (PythonPlugin.java:160–209). GraalPy
serializes context execution across threads (verified by the pre-existing
`PythonPluginTest.contextIsThreadSafeForConcurrentHandlers`).

| ID | Finding | Classification | Evidence |
| --- | --- | --- | --- |
| P3-8 | Scheduled Python tasks are not tracked: `scheduleAsync/Delayed/Repeating` drop the returned `TaskHandler` (PythonPlugin.java:281–315). A repeating task of a disabled plugin keeps firing into the closed interpreter until proxy shutdown — unbounded error-log spam plus retained work. | **Fixed and verified** (Stage E) | `PythonRuntimeCleanupTest.disableCancelsScheduledTasks` failed: `every scheduled task handler must be cancelled on disable ==> expected: <true> but was: <false>` |
| P3-9 | `subscribe`/`registerCommand` invoked after disable (stale callback) still register live resources: the command stays registered forever and its execution hits a closed context; a subscribed handler logs a `PolyglotException` per event. | **Fixed and verified** (Stage E) | `PythonRuntimeCleanupTest.registrationAfterDisableIsRefused` failed: `a disabled plugin must not register commands into a closed context ==> expected: <false> but was: <true>` |
| P3-15 | `context.close(true)` while an async handler is mid-execution interrupts the script; the wrapper logs the resulting `PolyglotException`. Accepted behavior — no change; documented in the final report. | Not applicable (by design) | — |
| P3-16 | Python reference ownership: the bridge holds `Value`/`Subscription` references only; both collections are cleared during disable before the context closes. No leak found; interpreter state does not outlive `close`. | Not applicable (no defect) | code read + existing tests |

Also audited, no defect found: interpreter initialization is once per plugin (no repeated
init); module caching is the single held `module` Value; the interpreter never shares global
state with the Java runtime beyond the documented plugin/proxy objects; sibling-import
failures surface as a load failure of that plugin only; `sys.path` insertion goes through a
bindings member with no source-text interpolation (PythonPlugin.java:96–113).

### B.3 Metadata trust boundary (shared by both runtimes)

Descriptor parsing runs before any plugin code executes and for every scanned jar and folder.

| ID | Finding | Classification | Evidence |
| --- | --- | --- | --- |
| P3-5 | SnakeYAML **1.32** with `CustomClassLoaderConstructor` honors global `!!` tags: a crafted `waterbucket.yml`/`plugin.yml` instantiates an arbitrary classpath class during scan (CVE-2022-1471 class). Descriptor parsing is reachable without executing any plugin code. | **Fixed and verified** (Stage F) | `PluginDescriptorParsingTest.globalTagsInDescriptorsInstantiateNothing` failed: `expected: <0> but was: <1>` — the probe class was constructed while parsing |
| P3-19 | Wrong value types are silently coerced instead of rejected: `name: 42` parses to the string `"42"`, `main: {broken: mapping}` parses to an empty `main`. Garbage descriptors load far enough to produce confusing downstream errors. | **Fixed and verified** (Stage F, together with the P3-5 parser rewrite) | `PluginDescriptorParsingTest.wrongValueTypesAreRejectedWithThePluginSkipped` failed: parsed as `PluginYAML(name=42, ..., main=, depends=null)` |
| P3-6 | Unknown descriptor keys (e.g. `api-version`) were suspected to reject plugins. Not reproduced: through the shared `yamlLoader` unknown keys are tolerated. The Python-folder fallback parser (plain `new Yaml(...)`) only runs as a probe inside `isPythonPlugin` and merely marks the folder as non-plugin. | **Investigated but not reproduced** — no change needed | `PluginDescriptorParsingTest.unknownDescriptorKeysDoNotRejectThePlugin` passed on unmodified code |
| P3-7 | `Plugin.saveResource(filename, outputName, replace)` writes to `new File(dataFolder, outputName)` without confinement (Plugin.java:175) — `../` escapes the data folder. Plugins are in-process trusted code, but this API is trivially misused by accident; Python resource reads are already confined. | **Fixed and verified** (Stage F) | covered by `PluginFailureIsolationTest.saveResourceCannotEscapeTheDataFolder` (added with the fix; reproduced by inspection before the fix — the code path is a plain `new File(parent, child)`) |

### B.4 Runtime independence (§4.3)

- Java and Python runtimes share no mutable global state; the only shared object graph is the
  `PluginManager` registry and the `Plugin` base state.
- Initialization order: both runtimes initialize sequentially during `loadAllPlugins` on the
  boot thread; one failing plugin cannot corrupt another's state (failure drops only that
  plugin — after the P3-4 fix, also without retaining its classloader).
- Shutdown order: both runtimes tear down in `disableAllPlugins` in reverse enable order,
  before network interfaces close (ProxyServer.java:447), so disconnect events still reach
  plugin handlers.
- Cross-runtime event dispatch uses the same `EventManager` snapshot pipeline; a Python
  handler and a Java handler subscribed to the same event are called in priority order
  regardless of their runtime (covered by the Stage D cross-runtime integration test).

---

*Stage C (event engine audit) and Stage D (API and compatibility audit) are appended below as
they complete.*

---

## Stage C — Event engine audit

### C.1 Dispatch correctness (§6.2/§6.3)

Verified by the existing suite (all green before any Phase 3 change) plus the Phase 3
reproduction tests; the full set re-runs after every fix stage as the behavior fingerprint:

- priority ordering `LOWEST → NORMAL → HIGHEST` and same-priority registration order
  (`EventManagerConcurrencyTest.priorityOrderingIsPreserved`);
- duplicate subscription runs once per call (`duplicateSubscriptionOfSameHandlerRunsItOncePerCall`);
- concurrent subscribe + dispatch never loses a handler or fails
  (`eventCallsWhileSubscribingNeverFailOrLoseHandlers`);
- one broken handler cannot stop later handlers (`brokenHandlerDoesNotBreakOthers`);
- unsubscribe-by-identity and no-op unsubscribe (`unsubscribeStopsDispatchAndUnknownHandlersAreNoOps`);
- concurrent subscribe/unsubscribe stays consistent
  (`concurrentSubscribeAndUnsubscribeStayConsistent`);
- Python handlers share the exact same failure semantics (logged, never propagated)
  (`PythonPluginTest.brokenPythonHandlersDoNotBreakEventDispatch`,
  `brokenCompletableHandlerDoesNotFailTheEventPipeline`).

Cancellation, payload and ordering semantics are untouched by every Phase 3 change (§6.2);
the behavior fingerprint re-run is part of Stage H.

### C.2 Measured performance (§6.1/§13)

Method: `plugin/python/Phase3PluginEngineBenchmark.java` (runs only with `PHASE3_BENCH=true`),
single JVM, per scenario 2–5 warmup rounds + 7–15 measured rounds, per-op nanos; identical
workloads for the before/after runs (label via `PHASE3_BENCH_LABEL`). Every scenario drives
the real production dispatch path — no bypassing of hooks. Full raw data:
`benchmarks/results-plugin-phase3-before.json`.

Baseline (unmodified code, JDK 17.0.21.1, Linux sandbox):

| Scenario | Median | p95 | Note |
| --- | --- | --- | --- |
| sync dispatch, 0 handlers | 170 ns/op | 283 ns | empty pipeline incl. event alloc |
| sync dispatch, 1 handler | 320 ns/op | 471 ns | noisy single-scenario (see rounds); same order as below |
| sync dispatch, 5 handlers × 5 priorities | 79 ns/op | 226 ns | |
| sync dispatch, 40 handlers × 5 priorities | 342 ns/op | 587 ns | ≈ 7 ns per handler |
| sync dispatch, 320 handlers × 5 priorities | 2 328 ns/op | 2 472 ns | scales linearly |
| cancellation (1 handler, cancelled) | 30 ns/op | 37 ns | |
| completable sync event (1 handler) | 70 ns/op | 607 ns | future creation incl. |
| allocations, 320-handler dispatch | 208 B/op | — | event + iterator |
| async event round trip (1 handler) | 6.3 µs/op | 28 µs | pool scheduling + future |
| subscribe + unsubscribe round trip | 2.6 µs/op | 5.3 µs | management operation |
| **Python script load (context init)** | **4.22 s** | — | one-time per plugin, interpreter mode |
| **Python no-op handler dispatch** | **33 µs/op** | 48 µs | pure bridge+interpreter cost |
| Python bridge delta (320 java + 1 python) | 17.6 µs/op | 30 µs | python handler adds ~15 µs |
| retained polyglot instances after disable | 0 | — | histogram captured; clean release |
| plugin scan, 32 jars | 90 ms | — | 2.8 ms/jar descriptor parse |
| plugin load, 32 plugins | 519 ms | — | 16.2 ms/plugin classload+init |
| plugin enable, 32 plugins | 2.4 ms | — | |
| **missing-class lookup across 32 loaders** | **194 µs/miss** | 351 µs | full jar-scan + exception fill per miss |

Harness validity controls: the python handler execution is counted (`pythonHandlerInvocationsTotal`),
handler instances are distinct anonymous objects (lambda call-site caching would silently
deduplicate them), and the class histogram capture is asserted.

### C.3 Stage C conclusions

1. **The Java dispatch path has no measured bottleneck that warrants a code change.** Per
   handler cost ≈ 7 ns; sync events are not on the per-packet path (packet handling does not
   call `callEvent` per packet — events fire on lifecycle/chat/transfer boundaries), so even
   2 µs dispatch with 320 handlers is negligible against the network pipeline. No change →
   per §6.1 and §19, the dispatch engine is left untouched.
2. **P3-14 is a measured bottleneck on the failure path**: one missing-class lookup costs
   194 µs at 32 loaded plugins (each miss scans every plugin jar and fills 32 exceptions).
   A plugin referencing a missing class repeatedly burns measurable CPU. Fixed in Stage G
   with a global negative-lookup cache (outcome-identical: the same `ClassNotFoundException`),
   measured before/after.
3. **P3-13 (thread-per-concurrent-async-event) documented with numbers**: sequential async
   round trips reuse pool threads (10 threads after 11 000 sequential dispatches); a
   concurrent burst still allocates one thread per in-flight event — the bounded-queue
   alternative was already rejected in Phase 2 (it would push blocking back into event
   loops); the design is restated as an accepted, documented limit.
4. **Python bridge overhead is inherent to the runtime choice** (GraalPy interpreter mode on
   a standard JDK — docs/PLUGINS.md documents the GraalVM-JDK JIT caveat). Optimizing it
   would mean changing the runtime architecture, which is out of scope. The one-time 4.2 s
   interpreter initialization per Python plugin is likewise inherent; it happens during
   `loadAllPlugins`, before the proxy accepts players.

---

*Stage D (API and compatibility audit) is appended below when complete.*

---

## Stage D — API and compatibility audit

### D.1 Machine-verified API contract

The shipped example plugins are compiled and executed against the production classes in
`PluginCompatibilityTest`:

- `examples/plugins/java-example` is compiled from its real source with the production
  classpath — this is a signature-level check of the public `Plugin`/`EventManager`/
  `CommandMap`/`PlayerChatEvent` contracts, then the compiled jar is loaded by the real
  `PluginManager` scan → load → enable flow.
- `examples/plugins/python-example` is copied verbatim (descriptor + `main.py` +
  `helper.py`) and loaded as a folder plugin; the `/pyhello` command executes through the
  full GraalPy interop path including the sibling-module import.

Verified end-to-end on unmodified code: both plugins load and enable; both hold exactly one
`PlayerChatEvent` subscription each; chat dispatch leaves the payload unchanged and
uncancelled; both commands execute; after `disableAllPlugins` the Python subscription and
command are detached while the Java plugin's own registration remains — exactly the
documented contract in `docs/PLUGINS.en.md` (Python runtime auto-detaches; Java plugins clean
up themselves).

### D.2 Documented-API cross-check (§7)

| Documented (PLUGINS.en.md) | Implementation | Status |
| --- | --- | --- |
| Java jar plugins, `waterbucket.yml`/`plugin.yml`, fields name/version/author/main/depends | `PluginLoader.loadPluginData`, `PluginYAML` | matches |
| Folder-based Python plugins, optional `on_startup`/`on_enable`/`on_disable` hooks | `PythonPluginLoader`, `PythonPlugin.callHook` | matches |
| `plugin.subscribe(event, fn)` auto-detached on disable | `PythonPlugin.subscribe` + `detachEventSubscriptions` | matches |
| `plugin.register_command(...)` unregistered on disable | `PythonPlugin.registerCommand` + `unregisterRegisteredCommands` | matches |
| Sync handlers run on the calling thread (incl. Netty event loops) | `EventHandler.handleSync` inline dispatch | matches |
| Failed handlers are logged, never fail the pipeline | per-handler `catch (Throwable)` in `EventHandler`/`PythonPlugin` | matches |
| No hot reload; no Python task handles | no unload API; `schedule*` returns void | matches (and limits are re-documented in the final report) |
| `Plugin.getResourceFile` / `saveResource` / `getConfig` | `Plugin` implementation | matches |

Findings against the API contract are the P3-10 (null-argument error quality) and P3-19
(coerced descriptor types) entries from Stage B; both are fixed in Stages E/F with the
external contract preserved (same signatures, same successful-path behavior).

### D.3 Compatibility risk assessment of the planned fixes

All Stage E/F/G fixes were checked against this test before implementation: none of them
changes a public signature, a lifecycle outcome for valid plugins, event names/payloads, or
the documented Java-vs-Python disable asymmetry. The two deliberate behavioral refinements —
broken plugins no longer keep a registered classloader (P3-4) and descriptors with global
`!!` tags are rejected (P3-5) — only affect plugins that could not load correctly or were
unsafe before; they are documented in the final report.

---

## Stage E — Stability and memory fixes (all verified by the Stage B reproductions)

| Fix | Change | Regression test |
| --- | --- | --- |
| P3-18 | `PluginLoader.loadPluginJAR` and the `onStartup` boundary in `PluginManager.loadPlugin` now catch `Throwable`: a plugin compiled for a newer JVM (or any `LinkageError`) drops the plugin instead of aborting the boot. | `PluginFailureIsolationTest.pluginCompiledForANewerJvmDoesNotCrashTheBoot` |
| P3-4 | `PluginManager.releaseFailedPlugin` unregisters the failed plugin's class loader, evicts its classes from the global cache, and closes the loader (releases the jar handle). | `PluginFailureIsolationTest.pluginWhoseMainClassFailsToConstructIsFullyReleased` |
| P3-2 | `PluginClassLoader.classes` is now a `ConcurrentHashMap`, and `findClass` consults `findLoadedClass` before re-defining, so a lost cache entry can no longer reach a duplicate `defineClass`. | `PluginFailureIsolationTest.concurrentFirstLoadsShareClassesWithoutLinkageErrors` (stress) |
| P3-3 | `enablePlugin` is serialized on the manager and cycle detection uses a chain-local set (passed through the private recursion, public signature unchanged); `enabledOrder` is a `CopyOnWriteArrayList`. Concurrent chains can no longer cross-reject or double-enable. | `PluginLifecycleTest.concurrentEnableChainsDoNotRejectHealthyDependencies` |
| P3-1 | `pluginMap` and `pluginClassLoaders` are concurrent maps: the public runtime APIs (`loadPlugin`/`enablePlugin`) and the boot scan write them while `getClassFromCache` (event loops) and `PluginsCommand` (panel thread) read and iterate. | iteration-safety by construction; exercised by every manager test |
| P3-8 | `PythonPlugin` tracks every `TaskHandler` it hands to the scheduler and cancels them all in `onDisable` before the context closes. | `PythonRuntimeCleanupTest.disableCancelsScheduledTasks` |
| P3-9 | `PythonPlugin.subscribe`/`registerCommand`/`schedule*` refuse work after the interpreter closed (logged warning, no registration). | `PythonRuntimeCleanupTest.registrationAfterDisableIsRefused` |
| P3-10 | `EventManager.subscribe`/`callEvent` validate arguments with `Preconditions` — clear `IllegalArgumentException` instead of a deep NPE. | contract test added with the fix run (`EventManagerNullContractTest` in `EventManagerConcurrencyTest` run set) |
| (new) | `SimpleCommandMap.executeInternal` catches `Throwable`: an `Error` thrown by a plugin command is contained to that command execution. | covered by command tests; same pattern as the event boundary |
| (new, found during E) | `loadAllPlugins` iterates a snapshot of `pluginsToLoad`: the P3-4 fix made a re-load path re-register a loader and append to the list it was iterating — an infinite load loop under the old live-list iteration, caught by the Stage E test run. | `PluginFailureIsolationTest` boot-path assertion runs the exact sequence |

The Stage E commit contains only production fixes whose Stage B reproduction is now green,
plus the reproductions themselves. `PluginDescriptorParsingTest` (Stage F) is still red at
this point and is committed with its fix in Stage F, keeping every commit's tree green.

---

## Stage F — Security and error handling fixes (all verified by reproductions)

| Fix | Change | Regression test |
| --- | --- | --- |
| P3-5 | Descriptor parsing now goes through `PluginLoader.parseDescriptor` using SnakeYAML `SafeConstructor` + manual field mapping: global `!!` tags can no longer instantiate classpath classes while a descriptor is parsed (jar and folder paths share the one hardened parser; the public `PluginManager.yamlLoader` field stays API-compatible but is also `SafeConstructor`-based now). | `PluginDescriptorParsingTest.globalTagsInDescriptorsInstantiateNothing` |
| P3-19 | The same parser type-checks fields with the old parser's leniency preserved: scalars are stringified (unquoted `version: 1.0` keeps working), unknown keys stay ignored (P3-6 behavior kept), complex values (mappings/sequences) for string fields and non-list `depends` reject the descriptor with a clear warning instead of silently producing garbage. | `PluginDescriptorParsingTest.unknownDescriptorKeysDoNotRejectThePlugin`, `wrongValueTypesAreRejectedWithThePluginSkipped` |
| P3-7 | `Plugin.saveResource(filename, outputName, …)` confines `outputName` to the plugin data folder (normalize + `isInsideDirectory`, same helper as the Python resource path); traversal returns false and writes nothing. | `PluginFailureIsolationTest.saveResourceCannotEscapeTheDataFolder` |
| P3-11 | `Plugin.init` warns immediately when the data folder cannot be created instead of failing silently later at config-write time. | covered by all plugin tests (warn path only) |
| P3-20 (new, found while testing) | `Plugin.buildLogger` used `getClass().getCanonicalName()`, which is null for anonymous plugin subclasses → `init` crashed with an NPE for a plugin implemented as an anonymous class; falls back to the binary name now. | `PluginFailureIsolationTest.saveResourceCannotEscapeTheDataFolder` instantiates the plugin as an anonymous subclass |

Documented limitation (unchanged, §12): plugins execute in-process with full JVM rights; a
malicious plugin can still do anything the host process can. The boundary work above protects
the metadata-parsing and data-folder paths that run *before* or *beside* plugin code — a
sandbox would be a new product and is explicitly out of scope.

---

## Stage G — Performance optimization

Per Stage C's conclusion, exactly one measured bottleneck was optimized; the event dispatch
engine itself was left untouched (no measured bottleneck, per §6.1/§19).

| Change | Scenario (identical workload, before → after) | Result |
| --- | --- | --- |
| Global negative-lookup cache for missing plugin classes (`PluginManager.missingClasses`, consulted by `getClassFromCache` and `PluginClassLoader.findClass`; cleared whenever a new loader registers so runtime-loaded plugins can still provide previously missing classes) | `missing_class_lookup_32_loaders`: **194 µs/miss → 23.8 µs/miss** (p95 351 µs → 108 µs) | −88 %; outcome identical (`ClassNotFoundException` still thrown, `getClassFromCache` still returns null). The residual ~24 µs is the unavoidable parent-classloader miss + exception construction, not a jar scan. |
| (no change) | sync dispatch 5/40/320 handlers: 79/342/2328 ns → 81/341/2336 ns; async round trip 6.3 → 3.1 µs (run-to-run variance); python bridge 33.0 → 31.3 µs; plugin load 519 → 440 ms | unchanged within noise — plugin-visible behavior untouched |

Regression test: `PluginFailureIsolationTest.missingClassLookupsStayOutcomeIdentical` (outcome
identity + negative-cache invalidation on loader registration).

Raw data: `benchmarks/results-plugin-phase3-before.json` (unmodified code) and
`benchmarks/results-plugin-phase3-after.json` (final code), same harness, same JVM.
