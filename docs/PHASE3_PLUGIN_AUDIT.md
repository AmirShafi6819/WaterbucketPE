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
