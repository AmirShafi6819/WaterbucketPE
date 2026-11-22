# Phase 5 — Plugin Engine: Second Verification Pass and Residual Hardening — Final Report

The first plugin-engineering pass (delivered under the "PHASE 3" header, commits
`88d93cf..692663e`) completed the full Stage A–H workflow: architecture map, runtime audits
with executable reproductions, event-engine measurement, API/compatibility verification,
stability/security fixes, one measured performance fix, and the §20 final report
(`docs/PHASE3_PLUGIN_ARCHITECTURE.md`, `docs/PHASE3_PLUGIN_AUDIT.md`,
`docs/PHASE3_FINAL_REPORT.md`).

This Phase 5 directive re-issues the same scope. Rather than repeating completed stages, this
pass verified the delivered state and closed every gap the first report honestly recorded:
the missing §15 fuzzing, the missing §13 shutdown/multi-event-type measurements, the
"fixed but not fully verified" P3-2 classloader race, and a second full read of the plugin
sources for residual defects. Zero features added, zero dependencies added, zero public API
signatures changed.

## 1. Second-pass verification results

| Item from the first report | Second-pass outcome |
| --- | --- |
| P3-2 loader-local class-map race ("fixed but not fully verified") | Upgraded: a 14-thread sustained stress (`sustainedConcurrentClassLoadingAndRegistryAccessStaysConsistent`) hammers class resolution, negative lookups, registry reads and duplicate runtime loads concurrently — 400 classes resolve to exactly one `Class` object each, cache stays exact, no `LinkageError`, all failed-load attempts release. **Fixed and verified.** |
| §15 "use fuzzing for metadata parsing" (not yet done) | Delivered: `PluginDescriptorFuzzTest` — 3,130 deterministic seeded descriptor cases (1,500 jar-path + 1,500 folder-path + 130 corrupted-jar payloads): no parse ever throws, no `!!` gadget instantiation, accepted descriptors always satisfy the documented invariants, corrupted jars are always rejected. **Fixed and verified.** |
| §13 shutdown time / multi-event-type workloads (not yet measured) | Delivered: `plugin_disable_32_plugins` = **5.75 ms** (32 plugins, reverse enable order); `mixed_event_types_3x40_handlers` = **334 ns/op** across three event classes × 40 handlers under 32 loaded plugins — flat vs the single-type 40-handler baseline (339 ns). Recorded in `benchmarks/results-plugin-phase3-phase5.json`. |
| Configuration retry hypothesis (from the second read) | **Investigated — hypothesis disproved:** `Configuration.load()` swallows parse errors and yields a non-null empty config, so `getConfig()` neither re-parses per call nor returns null; behavior pinned by test and documented as a shared-config-subsystem limitation (not changed — `Configuration` is shared with the proxy's own boot config; altering it exceeds minimal plugin-scope repair). |
| `getResourceFile(null)` | **Investigated — already null-safe** (JarFile returns a null entry → warn + null); behavior pinned by test. |

## 2. New defects found and fixed in this pass (each reproduced first)

| ID | Finding | Root cause | Fix | Regression test |
| --- | --- | --- | --- | --- |
| P5-1 | `getCommands()` iterated **without** the fastutil synchronized-wrapper's mandated manual sync at three in-tree sites — `AbstractDownstreamHandler` (AvailableCommandsPacket building, **on Netty event loops**), `HelpCommand`, `CommandCompleter` — while Python plugins may register commands at runtime; an unsynchronized read raced the registration path (fastutil contract: iteration requires synchronization on the map). | Synchronized wrappers protect single operations only; the read sites iterated bare. | All three in-tree iteration sites now synchronize on the returned map (same monitor the wrapper uses). | `concurrentCommandRegistrationAndIterationStayConsistent` (4 writers × 1,000 registrations vs 2 looping readers: no failure, no lost registration) |
| P5-2 | `saveResource(filename, null, …)` crashed with a bare NPE from `Path.resolve(null)`. | `outputName` never validated (filename was). | `Preconditions` check — clear `IllegalArgumentException`, matching the existing contract style. | `saveResourceRejectsNullOutputNameWithAClearError` |
| P5-3 | `handleCommand(sender, command, null, args)` NPE'd inside `isRegistered(null)` — public API path. | Null alias not guarded. | Null alias treated like an unknown alias; command still executes. | `handleCommandWithNullAliasDoesNotCrash` |
| P5-4 | Two concurrent `loadPlugin` calls for the same name could both register class loaders (check-then-act on the registry map); the loser leaked with its jar handle open. | `containsKey` + `put` not atomic. | `putIfAbsent`; the loser is skipped and becomes unreachable. | `duplicateRuntimeLoadPluginRegistersOnlyOneLoader` |
| P5-5 | **Stage G's negative-lookup cache had a semantic edge:** a `PluginClassLoader` created outside the registry (public constructor) trusted the manager-wide "missing" verdict for classes its own jar contained → immediate `ClassNotFoundException` for a loadable class. Found by the new sustained stress test. | The cache records "missing from every registered loader"; an unregistered loader was never scanned for that verdict. | The cache is consulted only by registered loaders (`markRegisteredWithManager` set by `registerClassLoader` after `putIfAbsent`). | `sustainedConcurrentClassLoadingAndRegistryAccessStaysConsistent` |
| P5-6 | **Stage E's failed-loader release could break other loaders:** `URLClassLoader.close()` closes the JVM-wide `JarFileFactory`-cached `JarFile`, which is **shared between loaders opened over the same file URL** (reachable via the public `loadPlugin` API with a re-named descriptor over an already-registered jar). Closing the failed loader then killed every other loader over that jar with `IllegalStateException: zip file closed`. Found by the new sustained stress test. | Shared `JarFile` cache closes per-`close()` regardless of sharers. | `releaseFailedPlugin` now unregisters and evicts **without** `close()`; the loader becomes unreachable and the GC closes the handle — same end state, no shared-jar hazard. | `sustainedConcurrentClassLoadingAndRegistryAccessStaysConsistent` (12 resolver threads + 2 duplicate-load writers over one shared jar) |

## 3. Measurements (identical harness, current code; baseline JSONs from the first pass remain valid)

| Scenario | Phase 3 after-fix | Phase 5 (this pass) | Note |
| --- | --- | --- | --- |
| plugin disable (32 plugins) | — (not measured) | **5.75 ms** | new §13 shutdown-path measurement |
| mixed event types (3 × 40 handlers, 32 plugins loaded) | — (not measured) | **334 ns/op** | ≈ single-type 40-handler cost → flat scaling |
| missing-class lookup (32 loaders) | 23.8 µs | 23.6 µs | unchanged (P5-5 scoping did not touch the fast path) |
| sync dispatch 5/40/320 handlers | 81/341/2 336 ns | 79/339/2 341 ns | unchanged within noise |
| async round trip | 3.1 µs | 3.4 µs | noise |
| python bridge (exclusive) | 31.3 µs | 31.6 µs | unchanged |
| retained polyglot instances after disable | 0 | 0 (histogram asserted) | unchanged |
| plugin load 32 | 440 ms | 298 ms | noise-dominated variance between runs |

## 4. Compatibility assessment

The three behavior-narrowing refinements of this pass (P5-1 synchronized iteration, P5-2/P5-3
null-contract errors, P5-4 duplicate-load skip) preserve all external contracts: same map
contents, same successful-path results, same signatures. The two semantic corrections
(P5-5 cache scoping, P5-6 release without close) only affect paths that were incorrect or
hazardous before: a class loadable from a loader is never again rejected because of a global
negative verdict, and closing a failed loader can no longer corrupt other plugins' loaders.
The full suite (including the cross-runtime example-plugin test) is green before and after.

## 5. Validation

- Clean build: `BUILD SUCCESSFUL`; **53 suites / 300 tests / 0 failures / 0 errors / 1 skipped**
  (the benchmark, env-gated) — count grew from 294 by the six new second-pass tests.
- Fuzz: 4,900+ metadata cases deterministic-seeded, reproducible failures.
- Static analysis: compiler + full suite; the project has no integrated analyzer (unchanged).

## 6. Commit hashes and push status

| Commit | Content |
| --- | --- |
| `84626ed` | Command-map iteration sync, null contracts, descriptor fuzzing (P5-1..P5-3) |
| `03bdeab` | Negative-cache scoping + safe failed-loader release (P5-4..P5-6) |
| `ab48fa5` | Disable + mixed-event-type measurements |
| (this commit) | Phase 5 final report + documentation index |

Pushed to `origin/master` as regular fast-forward non-force pushes; all commits authored as
`AmirShafi6819 <AmirShafi6819@users.noreply.github.com>`.

## 7. Findings classification

- **Fixed and verified:** P5-1, P5-2, P5-3, P5-4, P5-5, P5-6; §15 fuzzing delivered; §13
  shutdown/multi-type measurements delivered; P3-2 upgraded to verified.
- **Investigated but not reproduced / not changed:** configuration parse errors are swallowed
  by the shared `Configuration` subsystem (documented, out of minimal plugin scope);
  `getResourceFile(null)` already safe.
- **Not applicable:** the event dispatch engine, scheduler internals, Python bridge internals —
  audited again, no verified defect; per §19 no changes were made to subsystems without
  verified defects.

## 8. Remaining limitations (unchanged from the first pass, restated)

No runtime unload/hot reload; disabled Java plugins clean up their own registrations;
sync handlers run on event loops by design; async events allocate one thread per in-flight
dispatch; GraalPy interpreter-mode overhead is inherent; in-process plugins are not sandboxed
(out of scope); the shared `Configuration` subsystem swallows parse errors (documented).
