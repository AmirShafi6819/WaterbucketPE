# Phase 3 — Plugin Engine Architecture Map (Stage A)

Baseline for this document: commit `3649a8b` (post-Phase-2), branch `master`, clean work tree.
Every statement below is derived from the checked-out source; file and line references point into
that revision. No production code was changed for this stage.

Scope: the plugin engine — discovery, loading, initialization, execution, event dispatch,
runtime bridges (Java, Python/GraalPy), failure handling, shutdown and cleanup — plus the
subsystems the plugin API reaches into (command map, scheduler, event manager).

Note on numbering: the directive header is "PHASE 3"; its mission text calls this phase
"Phase 5". This report uses **Phase 3** to stay consistent with the repository's existing
PHASE1/PHASE2 documentation and the directive's own Stage A–H lettering.

---

## 1. Subsystem inventory (as built)

| Area | Files | Lines (approx.) |
| --- | --- | --- |
| Plugin core | `plugin/Plugin.java`, `plugin/PluginLoader.java`, `plugin/PluginManager.java`, `plugin/PluginClassLoader.java`, `plugin/PluginYAML.java` | 253 / 117 / 336 / 66 / 34 |
| Python bridge | `plugin/python/PythonPlugin.java`, `python/PythonPluginLoader.java`, `python/PythonCommand.java`, `python/PythonCommandExecutor.java` | 363 / 112 / 56 / 30 |
| Event engine | `event/Event.java`, `event/EventHandler.java`, `event/EventManager.java`, `event/EventPriority.java`, `event/AsyncEvent.java`, `event/CompletableEvent.java`, `event/CancellableEvent.java` | 103 / 191 / 125 / 37 / 20 / 36 / 8 |
| Event types | `event/defaults/*.java` (28 events; 10 annotated `@AsyncEvent`) | ~700 |
| Scheduler (plugin-facing) | `scheduler/WaterbucketScheduler.java`, `TaskHandler.java`, `Task.java`, `CallbackTask.java` | 201 / 124 / ~50 / ~45 |
| Command API | `command/SimpleCommandMap.java`, `command/Command.java`, `command/PythonCommand` consumer | 158 |
| Exceptions | `utils/exceptions/{PluginChangeStateException,EventException,SchedulerException}.java` | small |
| Tests | `test/.../plugin/PluginLifecycleTest.java`, `plugin/python/PythonPluginTest.java`, `event/EventManagerConcurrencyTest.java`, `scheduler/*Test.java` | 210 / 290 / 196 |
| Examples | `examples/plugins/java-example/`, `examples/plugins/python-example/` | 2 plugins |
| Docs | `docs/PLUGINS.md`, `PLUGINS.en.md` (614 lines), `PLUGINS.fa.md` (614 lines) | API guide |

Prior engineering phases already hardened parts of this subsystem (commits `49971bf`,
`8bf6d78`, `3652c55`, `a9b105d`): concurrent event-handler snapshots, cycle detection in
enable, failed-enable state rollback, jar resource leak fixes, Python handler failure
semantics, path validation for plugin names and Python `main`/resource paths. Stage A maps the
current state; §9 registers what still looks wrong.

---

## 2. Architecture map

```
                 boot thread (ProxyServer.<init>)
                 ================================
 plugins/ folder ──► PluginManager.<init>            (ProxyServer.java:245)
                        │  PluginLoader (jar)   PythonPluginLoader (folders)
                        │  scan: Files.walk → jar/waterbucket.yml|plugin.yml
                        │  scan: Files.walk(depth 2) → folder descriptors
                        ▼
                 pluginsToLoad[(PluginYAML, path)]   (dedup by name, TreeMap dep-sort)
                        │
                 ProxyServer.<init> continues: scheduler, playerManager, eventManager,
                 packManager, security, commandMap  (ProxyServer.java:246-253)
                        │
                 PluginManager.loadAllPlugins()      (ProxyServer.java:255)
                        │  per entry → loadPlugin():
                        │    .py main → PythonPluginLoader.loadPlugin → PythonPlugin.initPython
                        │    else    → PluginClassLoader (registered in scan) →
                        │              PluginLoader.loadPluginJAR → reflect main → Plugin.init
                        │    plugin.onStartup()          ← plugin code first runs here
                        │    pluginMap[name]=plugin      (only after onStartup success)
                        ▼
                 ProxyServer.boot()                  (ProxyServer.java:273)
                        │  enableAllPlugins() → enablePlugin() with dependency recursion
                        │    (cycle detection via `enabling` set; missing deps rejected)
                        │    plugin.setEnabled(true) → onEnable()  ← subscriptions/commands
                        │    enabledOrder.append
                        │  ... network bind, ProxyStartEvent ...   (ProxyServer.java:331)
                        ▼
                 runtime (steady state)
                 ================
   Netty event loops ──► EventManager.callEvent(event)
                          ├─ sync event: EventHandler.handleSync → priorities LOWEST..HIGHEST
                          │              handlers run INLINE on the calling (event loop) thread
                          └─ @AsyncEvent: dispatched on EventManager.threadedExecutor,
                                          CompletableFuture returned to the caller
   plugin threads ────► EventManager.subscribe/unsubscribe (lock-free CAS snapshot swap)
                        PluginManager.loadPlugin/enablePlugin (public, runtime-capable)
                        WaterbucketScheduler.addTask → tick thread assigns → async pool
   panel/console ─────► PluginsCommand (reads pluginMap), dispatchCommand
                        ▼
                 shutdown (ProxyServer.shutdown, guarded)         (ProxyServer.java:424)
                        │  shutdown0(): panel.stop → disableAllPlugins() (reverse enable
                        │  order; PythonPlugin detaches handlers/commands, closes GraalPy
                        │  context) → disconnect players → interfaces.shutdown() →
                        │  scheduler.shutdown() → event executor shutdown → metrics
```

Event-engine data structures:

- `EventManager.handlerMap`: `ConcurrentHashMap<Class<? extends Event>, EventHandler>` —
  one `EventHandler` per event class, created lazily on first subscribe or first call
  (EventManager.java:40, 69, 104).
- `EventHandler.priority2handlers`: `volatile Map<EventPriority, List<Consumer<Event>>>`,
  immutable `List.copyOf` snapshots, replaced wholesale via
  `AtomicReferenceFieldUpdater` CAS loops on subscribe/unsubscribe (EventHandler.java:49,
  133–174). Dispatch iterates the snapshot; handler exceptions are caught per handler
  (EventHandler.java:118–130).
- `Event` cancellation flag + completable-future list (`Event.java:34–101`); async events
  return `CompletableFuture` (10 of 28 default events).

---

## 3. Lifecycle trace — operation by operation

For every operation: entry point → responsible classes → execution path → data structures →
thread context → error handling → cleanup → public contract → tests.

### 3.1 Discovery and scanning

- **Entry point:** `PluginManager.<init>` → `loadPluginsInside(proxy.getPluginPath())`
  (PluginManager.java:66–115), invoked from `ProxyServer.<init>` (ProxyServer.java:245).
- **Path:** `Files.walk` over `plugins/`; regular files ending `.jar` are parsed
  (`PluginLoader.isJarFile`, PluginLoader.java:37). Python plugins are directories found by a
  second bounded walk (`Files.walk(folderPath, 2)`) whose descriptor declares a `.py` main
  (PluginManager.java:100–114).
- **Data structures:** `TreeMap<PluginYAML, Path>` with a dependency-aware comparator that
  sorts dependents after dependencies (PluginManager.java:78–96); `pluginsToLoad` list
  preserves that order for loading.
- **Thread:** boot thread only, during `ProxyServer` construction.
- **Error handling:** descriptor parse failure → jar skipped with warning; walk IO failure →
  logged (PluginManager.java:72–74, 112–114). A duplicate plugin name is skipped at
  classloader registration (PluginManager.java:138–141).
- **Cleanup:** none needed (streams are try-with-resources).
- **Contract:** scanning is boot-time only; there is no runtime re-scan (no hot reload —
  documented in PLUGINS.en.md).
- **Tests:** indirectly via `PluginLifecycleTest` (manager constructed on empty temp dir).

### 3.2 Metadata parsing and validation

- **Entry point:** `PluginLoader.loadPluginData(File, Yaml)` (PluginLoader.java:86–115) for
  jars; `PythonPluginLoader.loadDescriptor(Path, Yaml)` (PythonPluginLoader.java:65–89) for
  folders. Both parse `waterbucket.yml` falling back to `plugin.yml`.
- **Path:** SnakeYAML `loadAs(stream, PluginYAML.class)` into a 5-field bean
  (PluginYAML.java:25–33: `name`, `version`, `author`, `main`, `depends`).
- **Validation:** `name` and `main` must be non-null (PluginLoader.java:100); `name` must
  match `[A-Za-z0-9_][A-Za-z0-9._ -]*`, ≤64 chars (PluginLoader.java:46–51) — names become
  data-folder directory names and map keys. Python `main` is confined to the plugin folder
  (PythonPluginLoader.java:96–99) and re-checked inside `PythonPlugin.loadScript`
  (PythonPlugin.java:106–112).
- **Thread:** boot thread.
- **Error handling:** parse/validation failures return `null` → jar/folder skipped with a
  warning; exceptions are caught and logged (PluginLoader.java:111–114).
- **Tests:** `PluginLifecycleTest.pluginNamesCanNotEscapeThePluginDirectory`.

### 3.3 Classloader creation (Java runtime)

- **Entry point:** `PluginManager.registerClassLoader` → `PluginLoader.loadClassLoader`
  (PluginManager.java:130–150, PluginLoader.java:60–67).
- **Path:** `new PluginClassLoader(pluginManager, proxyClassLoader, jarFile)` — a
  `URLClassLoader` per jar, parent = proxy app classloader (PluginClassLoader.java:34–37).
- **Class resolution:** `loadClass` is parent-first (JDK default), so proxy/JDK classes come
  from the parent; `findClass` (PluginClassLoader.java:44–63) checks the loader-local `classes`
  map, then the global `PluginManager.cachedClasses` (`ConcurrentHashMap`,
  PluginManager.java:61), then `super.findClass` (jar). Classes in the
  `dev.waterbucket.waterbucketpe.` namespace are refused in plugin loaders to prevent proxy
  shadowing (PluginClassLoader.java:45–47). Cross-plugin access works through the global
  cache lookup across all loaders (PluginManager.java:297–313).
- **Data structures:** loader-local `Object2ObjectOpenHashMap` (PluginClassLoader.java:32),
  global `ConcurrentHashMap` (PluginManager.java:61), loader map `Object2ObjectArrayMap`
  (PluginManager.java:54).
- **Thread:** map writes happen on whichever thread first resolves a class — including Netty
  event loops, as stated in the comment at PluginManager.java:56–61.
- **Error handling:** `MalformedURLException` → loader not created, plugin skipped
  (PluginLoader.java:63–66); `ClassNotFoundException` propagates to the referencing plugin.
- **Tests:** none dedicated to the classloader itself (gap registered in §9).

### 3.4 Instantiation, init, load (`onStartup`)

- **Entry point:** `PluginManager.loadPlugin(PluginYAML, Path)` (PluginManager.java:159–195),
  called from `loadAllPlugins()` (ProxyServer.java:255) and available as public runtime API.
- **Path (Java):** main class loaded through the plugin's loader, must extend `Plugin`
  (PluginLoader.java:71–74), reflective no-arg construction, `plugin.init(description, proxy,
  pluginFile)` (PluginLoader.java:76–79). `Plugin.init` builds the Log4j per-plugin logger
  config (Plugin.java:80–108) and creates the data folder
  `plugins/<name-lowercased>/` (Plugin.java:73–77).
- **Path (Python):** `PythonPluginLoader.loadPlugin` → `PythonPlugin.initPython` →
  `Plugin.init`; the interpreter is NOT started here — the GraalPy context is created in
  `PythonPlugin.loadScript()` when `onStartup()` runs (PythonPlugin.java:85–120).
- **State transition:** a plugin enters `pluginMap` **only after** `onStartup()` completed
  without exception (PluginManager.java:185–194); `Plugin.enabled` stays `false` until
  `enablePlugin`.
- **Error handling:** constructor/main-class failure → logged, `null` returned
  (PluginLoader.java:80–83); `onStartup` exception → logged, plugin dropped
  (PluginManager.java:185–190). `Plugin.init` rejects double init via `Preconditions`
  (Plugin.java:66).
- **Cleanup gap (candidate P3-4):** on failure after classloader creation the loader remains
  registered (see §9).
- **Tests:** `PythonPluginTest.lifecycleHooksAndSiblingImportsWork`.

### 3.5 Dependency resolution and enable

- **Entry point:** `PluginManager.enableAllPlugins()` (PluginManager.java:197–219) from
  `ProxyServer.boot()` (ProxyServer.java:275); `enablePlugin(Plugin, String)` is public.
- **Path:** for each plugin, dependencies are enabled depth-first first; recursion guarded by
  the `enabling` set for cycle detection of any length (PluginManager.java:229–274).
  Missing dependency → plugin not enabled; cycle → warning + not enabled (no crash — the
  StackOverflowError of the old 1-hop check was fixed in `a9b105d`).
- **State transition:** `plugin.setEnabled(true)` runs `onEnable()`; on exception the flag is
  rolled back to `false` and `PluginChangeStateException` is thrown (Plugin.java:227–247) —
  `onDisable` never runs against a half-enabled plugin.
- **Ordering:** successful enables are recorded in `enabledOrder`; `disableAllPlugins()`
  disables in reverse order so dependents shut down before dependencies
  (PluginManager.java:276–295).
- **Thread:** boot thread during startup; public API allows runtime calls from plugin threads.
- **Tests:** `PluginLifecycleTest.longDependencyCycleIsRejectedWithoutCrashingBoot`,
  `failedEnableLeavesPluginDisabledAndNeverRunsOnDisable`,
  `healthyEnableOrderIsRecordedForReverseDisable`.

### 3.6 Event registration and dispatch

- **Registration:** `EventManager.subscribe(Class, Consumer[, EventPriority])`
  (EventManager.java:54–71) → `EventHandler.subscribe` CAS-swap (EventHandler.java:133–152);
  duplicates by `equals` (lambda = identity) are ignored. `unsubscribe` removes by identity
  across all priorities (EventHandler.java:159–174).
- **Dispatch:** `EventManager.callEvent(event)` (EventManager.java:103–106) →
  `EventHandler.handle` (EventHandler.java:56–90):
  - sync events: priorities iterated `LOWEST → HIGHEST` (EventPriority.java:24–35) inline on
    the caller thread; cancellation via `Event.setCancelled` (Event.java:48–60) is visible to
    the caller on return.
  - `@AsyncEvent`: the whole priority loop runs on `EventManager.threadedExecutor`
    (`ThreadPoolExecutor(idleThreads, ∞, SynchronousQueue)`, EventManager.java:42–52); the
    returned future completes when handlers finish and, for completable events, after all
    futures added by handlers complete (`Event.completeFuture`, Event.java:72–93).
- **Failure isolation:** per-handler `catch (Throwable)` — one broken handler cannot stop
  later handlers, other priorities, or the caller (EventHandler.java:118–130); async
  exceptions complete the future exceptionally and are logged (EventHandler.java:74–79);
  a shutdown-race `RejectedExecutionException` falls back to caller-thread dispatch
  (EventHandler.java:80–88).
- **Python bridge:** `PythonPlugin.subscribe` wraps the callable — completable events get a
  future added before the call and completed in `finally`; throwables are logged, never
  propagated into the pipeline (PythonPlugin.java:226–246). Subscriptions are recorded and
  detached on disable (PythonPlugin.java:171–188).
- **Thread contract:** sync handlers run on Netty event loops — the existing architecture
  (proxy-wide) deliberately calls sync events inline; plugins must not block there
  (documented in PLUGINS.en.md).
- **Tests:** `EventManagerConcurrencyTest` (concurrent subscribe/dispatch, ordering,
  unsubscribe), `PythonPluginTest.pythonEventSubscriptionsReceiveEvents`,
  `brokenPythonHandlersDoNotBreakEventDispatch`, `contextIsThreadSafeForConcurrentHandlers`.

### 3.7 API access and core interaction

- **Service providers:** `ConfigurationManager.loadServiceProvider` (ProxyServer.java:257–258)
  uses `ServiceLoader` across the app classloader **and every plugin classloader** — a plugin
  jar can provide `IReconnectHandler`/`IJoinHandler` implementations selected by config name.
- **Commands:** `Plugin` code registers through `CommandMap.registerCommand(Command)`
  (SimpleCommandMap.java:62–70, synchronized maps); Python commands are wrapped in
  `PythonCommand` with permission and usage settings (PythonCommand.java:33–54). Python
  commands are unregistered on disable (PythonPlugin.java:190–199); Java plugins are
  responsible for their own command cleanup (documented).
- **Scheduler:** `scheduleAsync/Delayed/Repeating` wrap tasks with per-plugin error logging
  (PythonPlugin.java:281–315); repeating async tasks have an execution guard preventing
  pile-up (TaskHandler.java:53–76, WaterbucketScheduler.java:133–146).
- **Config/resources:** `Plugin.getResourceFile` reads jar resources fully into memory and
  closes the jar (Plugin.java:134–152); `saveResource` writes into the data folder
  (Plugin.java:172–194); Python resources are confined to the plugin folder
  (PythonPlugin.java:344–361).

### 3.8 Disable, shutdown, cleanup

- **Entry point:** `PluginManager.disableAllPlugins()` from `ProxyServer.shutdown0()`
  (ProxyServer.java:447) and from `Plugin.setEnabled(false)` paths.
- **Path:** reverse enable order; each `onDisable()` runs; `PluginChangeStateException` is
  logged and teardown continues (PluginManager.java:285–293).
- **Python cleanup order (PythonPlugin.java:160–209):** `on_disable` hook → detach event
  subscriptions → unregister commands → `context.close(true)` (interrupts a stuck script).
  Everything referencing the interpreter is detached *before* the context closes.
- **Proxy shutdown ordering:** plugins are disabled before network interfaces close, so
  disconnect events can still reach plugin handlers; scheduler and event executor shut down
  after the interfaces (ProxyServer.java:443–488).
- **Known cleanup state:** plugin objects stay in `pluginMap` after disable (no unload API —
  existing contract); GraalPy context and subscriptions are released; Java classloaders are
  never closed during runtime (retained until process exit — see P3-4/P3-14).

---

## 4. Runtime architecture

### 4.1 Java plugin runtime

- One `PluginClassLoader` per jar, parent = proxy classloader; parent-first delegation.
- No isolated JVM, no redefinition, no per-plugin worker threads — plugin code runs on
  whatever thread invokes it (event loops, scheduler pool, boot thread).
- Reflection is used only once per plugin (main-class instantiation, PluginLoader.java:77).
- Exception propagation: plugin exceptions are translated to
  `PluginChangeStateException` (enable/disable) or logged-and-dropped (load); event handler
  exceptions are logged and swallowed per handler.
- Shutdown: proxies never unload plugin classes at runtime; classloaders live until process
  exit (documented limitation, see P3-4/P3-14).

### 4.2 Python plugin runtime (GraalPy 25.0.5, `org.graalvm.polyglot:python`)

- One GraalPy `Context` per plugin, created in `loadScript()` with `allowAllAccess(true)` and
  `python.EmulateJython` so `import java` works (PythonPlugin.java:87–94). This is the
  existing integration architecture — unchanged by phases; GraalPy itself serializes context
  execution across threads (verified by
  `PythonPluginTest.contextIsThreadSafeForConcurrentHandlers`).
- Script globals: `plugin` (the `PythonPlugin`), `proxy`, `logger`; the plugin folder is
  prepended to `sys.path` through a bindings member (no string interpolation,
  PythonPlugin.java:96–113).
- Module caching: the evaluated main-module `Value` is held in `PythonPlugin.module`; sibling
  imports come from `sys.path`.
- Lifecycle: load at `onStartup` (fallback in `onEnable`), teardown in `onDisable`:
  hooks → detach handlers → unregister commands → `context.close(true)`.
- Reference ownership: Java holds `Value` references only inside the bridge; after close,
  `context`/`module` fields are nulled (PythonPlugin.java:201–209) and remaining
  subscriptions would log a `PolyglotException` instead of crashing (fixed in `8bf6d78`).
- Known, documented gaps (PLUGINS.en.md): Python task handles are not exposed (scheduled
  tasks cannot be cancelled from Python); no per-plugin interpreter reuse across disable/
  enable.

### 4.3 Runtime independence

- The two runtimes share only the `Plugin` base state (description, logger, data folder) and
  the proxy services; neither holds references into the other. Python state lives inside its
  own `Context`; Java state inside its own `PluginClassLoader`. No shared global mutable
  runtime state beyond the `PluginManager` registry itself.
- Initialization order: Java plugin classes load during `loadAllPlugins`; Python interpreters
  start during the same pass, sequentially per plugin — one failing interpreter cannot
  corrupt another plugin's state (failure drops only that plugin).

---

## 5. Public API contract (as implemented)

- `PluginManager`: `loadPlugin`, `loadAllPlugins`, `enableAllPlugins`, `enablePlugin(Plugin,
  String)`, `disableAllPlugins`, `getPluginMap` (unmodifiable view), `getPlugins`,
  `getPluginClassLoaders`, `getPluginByName`, `getClassFromCache`.
- `Plugin`: `onStartup`/`onEnable`/`onDisable`, `setEnabled`, `getConfig`/`loadConfig`,
  `getResourceFile`, `saveResource` (3 overloads), `getName`, getters.
- `EventManager`: `subscribe` ×2, `unsubscribe`, `callEvent`, `subscriberCount`.
- `PythonPlugin`: `subscribe`, `registerCommand`/`register_command`, `scheduleAsync`/
  `scheduleDelayed`/`scheduleRepeating` (+ snake_case aliases), `getModule`, `getProxy`,
  `loadScript`, `callHook`.
- Descriptor fields: `name`, `version`, `author`, `main`, `depends` (public fields of
  `PluginYAML`).

The documentation contract (PLUGINS.en.md) matches the implementation on the points
checked: two runtimes, no hot reload, no Python task handles, sync handlers run on the
calling thread, failed handlers never fail the pipeline.

---

## 6. Candidate findings register (unverified — Stage B/C/D/E/F verify)

Derived from the Stage A source read; none of these is confirmed yet, none is fixed yet.

| ID | Area | Candidate issue (evidence) |
| --- | --- | --- |
| P3-1 | Concurrency | `pluginMap`/`pluginClassLoaders` are non-thread-safe `Object2ObjectArrayMap`s mutated by the public runtime APIs `loadPlugin`/`enablePlugin` (PluginManager.java:54–55, 130–150, 159–195) while `getClassFromCache` (PluginManager.java:297–313) iterates the loader map from event-loop threads; `PluginsCommand` iterates the map view from the panel thread (command/defaults/PluginsCommand.java:37). No in-tree runtime writer today, but the API invites one. |
| P3-2 | Concurrency | `PluginClassLoader.classes` is a non-thread-safe `Object2ObjectOpenHashMap` mutated in `findClass` from any thread incl. event loops (PluginClassLoader.java:32, 61). Concurrent first-load of *different* classes can resize mid-read; a lost entry then re-enters `super.findClass` without a `findLoadedClass` guard → duplicate `defineClass` → `LinkageError` surfaced inside plugin code. |
| P3-3 | Lifecycle/concurrency | `enabling` is a shared `HashSet` and `enabledOrder` a plain `ArrayList` (PluginManager.java:227, 64): two concurrent `enablePlugin` chains from plugin threads would interact (false "circular dependency" rejections; unsynchronized appends). Boot path is single-threaded — exposure is runtime API use. |
| P3-4 | Resource ownership | A plugin that fails after classloader registration (main-class error, `onStartup` throw, duplicate-name skip) keeps its `PluginClassLoader` registered (PluginManager.java:138–150, 181–190), its jar handle open, and its classes in the global cache — `getClassFromCache` still serves classes of a broken plugin. No `URLClassLoader.close()` anywhere in the engine. |
| P3-5 | Security | SnakeYAML **1.32** with `CustomClassLoaderConstructor` (PluginManager.java:42–47, PythonPluginLoader.java:74–76) honors global YAML tags (`!!some.Class`) — the CVE-2022-1471 class. Descriptor parsing runs before any plugin code executes and for every scanned jar/folder; a crafted descriptor can instantiate arbitrary classpath classes during scan. |
| P3-6 | Compatibility/validation | Unknown descriptor keys (e.g. `api-version`) raise SnakeYAML `ConstructorException` → the whole plugin is rejected with a generic error (PluginLoader.java:99–114). The folder path does not even set `skipMissingProperties` (PythonPluginLoader.java:74–76). |
| P3-7 | Security | `Plugin.saveResource(filename, outputName, replace)` builds `new File(dataFolder, outputName)` without confinement (Plugin.java:175) — `../` writes outside the data folder (defense-in-depth; Python resource reads are already confined). |
| P3-8 | Resource ownership (Python) | Scheduled Python tasks are not tracked: `scheduleAsync/Delayed/Repeating` drop the returned `TaskHandler` (PythonPlugin.java:281–315). A disabled plugin's repeating task keeps firing into the closed interpreter until proxy shutdown (error log per period; work retained). |
| P3-9 | Lifecycle (Python) | `subscribe`/`registerCommand` called after disable (e.g. from a still-running scheduled task) register live resources pointing into a closed context; a command registered then is never unregistered (PythonPlugin.java:226–267). |
| P3-10 | API integrity | `EventManager.subscribe(null, …)`/`callEvent(null)` fail with a deep NPE from map internals instead of a clear contract violation (EventManager.java:54–71, 103–106). |
| P3-11 | Error reporting | `Plugin.init` ignores `mkdirs()` result (Plugin.java:74–76): a failing data-folder creation surfaces only later, indirectly, as failed config writes. |
| P3-12 | Loading correctness | The scan comparator is antisymmetric when both descriptors have `depends == null` (PluginManager.java:78–86) — benign with `TreeMap` (no crash), but fragile; documented only. |
| P3-13 | Performance/limits | The async event executor creates one thread per concurrently-dispatched async event (`ThreadPoolExecutor(idleThreads, ∞, SynchronousQueue)`, EventManager.java:51) — bounded queue was rejected in Phase 2 (would push blocking into event loops); needs a measured number in the report. |
| P3-14 | Performance | Missing-class lookups cost a full scan of every plugin jar per miss (`getClassFromCache` → `super.findClass` → CNFE with stack fill, PluginManager.java:297–313); measurable under plugin failure paths. |
| P3-15 | Python edge | `context.close(true)` while an async handler is mid-execution → `PolyglotException` on the worker thread, logged by the wrapper; acceptable, to be documented with the disable ordering. |
| P3-16 | Event correctness | `Event.cancelled` is a plain field (Event.java:35); safe under the documented contract (sync: same thread; async: future completion is the happens-before edge) — document why. |
| P3-17 | Data folder | Two plugins named `MyPlugin`/`myplugin` share one lowercased data folder (Plugin.java:73) — edge, documented only. |

---

## 7. What Stage A deliberately did NOT change

No production code, no build files, no tests were modified in this stage. The register above
feeds Stage B (runtime audit), Stage C (event engine audit with measurements), Stage D (API
and compatibility audit), and the fix stages E/F/G.
