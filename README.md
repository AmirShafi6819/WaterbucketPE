# WaterbucketPE

![WaterbucketPE](.github/banner.png)

[![License](https://img.shields.io/badge/License-GPL%20v2-blue.svg)](LICENSE)
[![Java](https://img.shields.io/badge/Java-17%2B-orange.svg)](https://adoptium.net/)
[![Release](https://img.shields.io/github/v/release/AmirShafi6819/WaterbucketPE)](https://github.com/AmirShafi6819/WaterbucketPE/releases)
[![Upstream](https://img.shields.io/badge/upstream-WaterdogPE-5B6B8C)](https://github.com/WaterdogPE/WaterdogPE)

**WaterbucketPE** is a performance-focused fork of [WaterdogPE](https://github.com/WaterdogPE/WaterdogPE),
the well-known Minecraft: Bedrock Edition proxy. It keeps full drop-in compatibility with
WaterdogPE setups while cutting memory and CPU overhead and tightening the core systems.

```text
  _      __     __          __            __       __  ___  ____
 | | /| / /__ _/ /____ ____/ /  __ ______/ /_____ / /_/ _ \/ __/
 | |/ |/ / _ `/ __/ -_) __/ _ \/ // / __/  '_/ -_) __/ ___/ _/
 |__/|__/\_,_/\__/\__/_/ /_.__/\_,_/\__/_/\_\\__/\\__/_/  /___/
```

## What changed compared to WaterdogPE

All changes are conservative: no protocol behavior, rewrite logic or plugin API semantics were
removed or changed. Every change is measured in the benchmark below.

### Performance

- **Release builds run with Netty leak detection truly disabled.** Upstream had a bug where the
  raise-only guard in `setLeakDetection` silently kept every release build at `SIMPLE` tracking,
  paying reference tracking on every direct buffer allocation. This is the single largest
  per-session memory win (see benchmark).
- **Right-sized Netty event loop groups.** The worker group is sized to the CPU core count
  instead of Netty's 2x default; the RakNet listener already binds one channel per core with
  `SO_REUSEPORT`, so every channel keeps a dedicated loop while halving thread stacks,
  selectors and context switching.
- **O(1) player name lookups.** `PlayerManager` now keeps a case-insensitive name index instead
  of scanning every online player (with per-entry `toLowerCase` allocations) on each lookup.
- **Login path allocation trims.** The username space replacement no longer compiles a regex
  per login.
- Development (`-SNAPSHOT`) builds still run with debug-mode leak detection, exactly like before.

### Stability

- The scheduler no longer dereferences a task that was concurrently cancelled while it ran, and
  the cancellation flag is `volatile` so async tasks observe it across threads.
- Regression tests were added for the player index, scheduler cancellation and the language
  fallback (see `src/test`).

### Compatibility (drop-in replacement)

- Existing `config.yml` files keep working (same config version and migrations).
- Language files written for WaterdogPE keep working: unknown `waterbucket.*` keys fall back to
  the legacy `waterdog.*` keys automatically.
- The `Waterdog_*` login extras sent to downstream servers are still sent, so backend plugins
  that read them keep working. `Waterbucket_*` keys are added alongside.
- bStats service id and the upstream dependency repository are unchanged.

## Benchmarks

Measured with [`benchmarks/bench.py`](benchmarks/METHODOLOGY.md): both proxies built from the
same upstream commit, run on the same 2-core container with identical JVM flags
(`-Xms128M -Xmx512M -XX:+UseG1GC`) and identical config. Handshakes are driven by the same
CloudburstMC RakNet library real connections use.

![Benchmark](benchmarks/benchmark.png)

| Metric | WaterdogPE 2.0.4 | WaterbucketPE 1.0.0 | Difference |
| --- | --- | --- | --- |
| Cold startup (median) | 2.77 s | 2.78 s | equal |
| Idle RSS (median) | 214.8 MB | 219.9 MB | equal (within noise) |
| Idle CPU (median) | 1.50 % | 1.50 % | equal |
| RakNet ping RTT median | 0.261 ms | 0.226 ms | **-13 %** |
| RakNet ping RTT p95 | 0.676 ms | 0.582 ms | **-14 %** |
| RakNet ping RTT p99 | 1.506 ms | 1.295 ms | **-14 %** |
| Handshake connect p95 | 20.22 ms | 11.98 ms | **-41 %** |
| Handshake throughput | 68.7 /s | 70.8 /s | +3 % |
| CPU during handshake churn | 52.2 % | 49.1 % | **-6 %** |
| RSS per live session (200 held) | 58.9 KB | 18.7 KB | **-68 %** |
| CPU while holding 200 sessions | 35.5 % | 34.2 % | **-4 %** |

Raw data: [`benchmarks/results.json`](benchmarks/results.json). Reproduce it yourself with
[`benchmarks/bench.py`](benchmarks/METHODOLOGY.md). Absolute numbers depend on the machine;
the relative comparison is what matters, and your hardware will shift both sides.

## Installation

1. Download the latest `Waterbucket.jar` from
   [Releases](https://github.com/AmirShafi6819/WaterbucketPE/releases).
2. Put it in an empty folder next to the matching start script:
   - `start.sh` (Linux / macOS) - `chmod +x start.sh && ./start.sh`
   - `start.bat` (Windows) - double-click or run from `cmd`
3. Java 17 or newer is required. The start scripts use G1 flags tuned for proxies and
   restart the proxy automatically if it exits.

Switching from WaterdogPE: replace the jar, keep your `config.yml`, `plugins` and `lang.ini`.
Everything else keeps working.

## Building

```bash
./gradlew build
# output: build/libs/Waterbucket.jar
```

Java 17 toolchain is used automatically; the build downloads it if configured
(see `COMPILING.md`).

## For developers

WaterbucketPE keeps the plugin API shape of WaterdogPE 2.x with the new
`dev.waterbucket.waterbucketpe` package namespace. Plugins written against WaterdogPE need a
recompile and a package import update; behavior is unchanged.

## License

WaterbucketPE is licensed under the GNU GPL v2, same as upstream WaterdogPE.
This project is a fork of [WaterdogPE](https://github.com/WaterdogPE/WaterdogPE) by the
WaterdogTEAM - all credit for the original proxy goes to them; this fork's changes are the
optimizations listed above.
