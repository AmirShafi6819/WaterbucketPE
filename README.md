<h1 align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset=".github/logo.png">
    <img alt="Water Bucket" src=".github/logo.png" width="200">
  </picture>
  <br>
  WaterBucketPE
</h1>

<p align="center">
  <strong>The Bedrock proxy that stays smooth when players show up.</strong><br>
  A drop-in WaterdogPE replacement with a rebuilt networking core, a Liquid Glass web panel,
  plugins in Java <em>and</em> Python, and benchmarks we publish even when they hurt.
</p>

<p align="center">
  <a href="https://github.com/AmirShafi6819/WaterbucketPE/releases"><img alt="Release" src="https://img.shields.io/github/v/release/AmirShafi6819/WaterbucketPE?color=1BA9E0&label=release"></a>
  <a href="LICENSE"><img alt="License" src="https://img.shields.io/badge/license-GPL%20v2-blue"></a>
  <a href="https://adoptium.net/"><img alt="Java" src="https://img.shields.io/badge/Java-17%2B-orange"></a>
  <a href="https://www.minecraft.net/"><img alt="Platform" src="https://img.shields.io/badge/platform-Bedrock%20Edition-62B47A"></a>
  <a href="https://github.com/AmirShafi6819/WaterbucketPE/actions"><img alt="CI" src="https://img.shields.io/github/actions/workflow/status/AmirShafi6819/WaterbucketPE/build.yml?branch=master&label=build"></a>
  <a href="https://github.com/WaterdogPE/WaterdogPE"><img alt="Upstream" src="https://img.shields.io/badge/upstream-WaterdogPE-5B6B8C"></a>
</p>

<p align="center">
  <a href="#about">About</a> ·
  <a href="#features">Features</a> ·
  <a href="#screenshots">Screenshots</a> ·
  <a href="#benchmark">Benchmark</a> ·
  <a href="#quick-start">Quick start</a> ·
  <a href="#the-web-panel">Web panel</a> ·
  <a href="#plugins">Plugins</a> ·
  <a href="#roadmap">Roadmap</a> ·
  <a href="#contributing">Contributing</a>
</p>

---

## About

WaterBucketPE is what WaterdogPE would be if it were rebuilt around one question: *what does
an operator actually feel?* Smooth joins instead of CPU spikes, a panel you can read at a
glance in two languages, messages you can reword without touching code, and a benchmark chart
that shows upstream winning whenever it actually does.

Everything WaterdogPE offers still works here: the same protocol behavior, the same plugin
API, the same configuration format. **Existing WaterdogPE jar plugins run unchanged** —
configs, transfers, forwarding and the event API are preserved, and the old `wd*` commands
answer as aliases of the rebranded `wb*` ones. The Python front-end is purely additive.

| | |
|---|---|
| **Smooth joins** | the login crypto, zlib codec and every packet class warm up at boot, so the first player of the day doesn't pay for JIT and class loading in front of everyone |
| **Honest numbers** | the WaterdogPE comparison is measured by one harness on one machine, re-measured whenever the harness itself has a bug, adverse panels reported as ties |
| **Operator surface** | Liquid Glass panel (dark/light, fa/en), editable layout, per-server management, colored console |
| **Customizable messages** | every player-visible text — kick reasons, transfer errors, the shutdown notice — lives in one `ingame-messages.yml` with § color codes |

## Features

- **Stable proxy core** — cross-loop visibility fixes locked by contract tests, bounded
  queues, guarded transfer handoff, hot-reload visibility and snapshot races fixed, failure
  containment at every layer: one malformed packet never kills a peer or IP-blocks a client.
- **Fast networking** — per-leg zlib compression levels, flush consolidation on both legs,
  right-sized event loops and pooled allocators, adaptive packet-batch drain, O(1) player lookups.
- **Web panel built in** — token-secured, served by the proxy itself, no external services:
  live charts, players, per-server management with real pings, a command console with colored
  output, and a drag-and-drop layout editor. Persian and English, dark and light.
- **A complete plugin API** — every event from the upstream WaterdogPE docs (plus
  `PlayerCommandEvent`), runtime max players (`setMaxPlayers`), player lookups, broadcasting,
  plugin-owned scheduler tasks that auto-cancel on disable, runtime `disablePlugin`, the
  `description` descriptor field and `reloadConfig()`; Python plugins are
  plain folders next to your jars, powered by GraalPy with zero configuration.
- **Messages you own** — `ingame-messages.yml` covers every text a player can see, with §
  codes and `{%0}` placeholders documented inline.
- **Quiet logs by design** — STUN/ICE noise filtered without hiding real failures; the
  console shows `time LEVEL | message` and nothing floods.
- **Actively maintained** — 62 test suites / 364 tests green on every change, CI on every
  push, docker images for every tagged release.

## Screenshots

| Dashboard | Servers |
|---|---|
| ![Dashboard](docs/screenshots/panel-dashboard.png) | ![Servers](docs/screenshots/panel-servers.png) |
| **Console** | **Players** |
| ![Console](docs/screenshots/panel-console.png) | ![Players](docs/screenshots/panel-players.png) |

Light theme, Persian (RTL) and four accent colors are one click away in the panel settings.

## Benchmark

Measured comparison against upstream WaterdogPE — same 2-core VM, identical JVM flags, real
RakNet pings and handshakes, the same harness driving both proxies (re-measured 2026-11-23).
Held-session memory/CPU figures are medians of three counted rounds after an uncounted
calibration round; handshakes pool 900 connects after a 120-connect JIT warm-up — the same
protocol for both proxies.

<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset=".github/benchmark-comparison.png">
    <img alt="WaterbucketPE vs WaterdogPE benchmark" src=".github/benchmark-comparison.png" width="900">
  </picture>
</p>

Highlights: idle memory **−10.5%** (216.6 → 193.8 MB), cold startup **−39.7%**, ping RTT
median **−3.3%**, handshake CPU **−12.6%**, marginal memory per held session **−51.6%** at 200
sessions and **−13.4%** at 500, CPU during the 500-session hold **−3.0%**, churn growth
**−59.5%**. All 200 and all 500 concurrent sessions held on both sides, 898/900 handshakes
succeeded on both, zero ping loss on both. The remaining panels (idle CPU, ping RTT p95, ping
throughput) measured as exact ties — shown as-is, nothing hidden.

## Quick start

**Requirements:** Java 17+ (Temurin recommended). Players connect with any Bedrock client ≥
the latest supported game version.

```bash
# Linux / macOS
java -jar Waterbucket.jar
# or the provided script (auto-restart, tuned G1 flags)
./start.sh
```

```bat
:: Windows
start.bat
```

Download the jar (and the start scripts + plugin examples) from the
[Releases](https://github.com/AmirShafi6819/WaterbucketPE/releases) page, or build from source
(below). The first start writes `configs/config.txt` (servers, priorities, compression,
panel), creates `ingame-messages.yml`, prints the web panel token location, and binds RakNet
on port `19132`. Point your Bedrock clients at the proxy and register downstream servers
under `servers:`.

**Docker** — images are published to GitHub Container Registry for every tagged release:

```bash
docker run -d -p 19132:19132/udp -p 19132:19132/tcp \
  ghcr.io/amirshafi6819/waterbucketpe:latest
```

## Commands

Rebranded `wb*`; the old `wd*` names keep working as hidden aliases.

| Command | Purpose |
|---|---|
| `/wbhelp` | all commands |
| `/wbstatus` | uptime, players, memory, CPU, threads, OS, JVM |
| `/wbinfo` | version, build, protocol support |
| `/wblist [server]` | online players |
| `/wbme [player]` | how you are wired: proxy ↔ server, pings |
| `/server <name> [player]` | transfer yourself or someone else |
| `/wbsend <target>` | move everyone at once |
| `/wbnethernet …` | inspect the NetherNet transport |
| `/wbmaxplayers [count]` | show or set the max player count (`0` = unlimited, persisted) |
| `/wbplugins [verbose]` | plugins list; `verbose` adds description + author |
| `/wbreloadpacks`, `/end` | packs, shutdown |

## The web panel

The proxy serves its own dashboard — open `http://<host>:8080` (default) and paste the token
from `panel_token.txt`. Everything runs inside the proxy process: no separate service, no
database, nothing else to secure.

- **Overview** — players, CPU, memory with a live bar, uptime; rolling charts for all three.
- **Servers** — every downstream server as a live card (online state, players, ping
  sparkline); click one for its detail dialog: ping/player charts, its players, kick and
  move-to-server actions, ping-now.
- **Console** — the proxy console in the browser: level-colored, filterable, with command
  history.
- **Settings** — dark/light, four accents, glass intensity, fa/en with RTL, layout editor.
- **Security** — token required on every API call (headers only, never in URLs), constant-time
  comparison, per-IP rate limiting, brute-force lockout, CSP + hardened headers, and a
  warning when the panel is bound beyond loopback. Keep `panel_bind_address` at `127.0.0.1`
  unless you front it with TLS.

## In-game messages

Every player-visible text is one editable file, created on first start:

```yaml
# ingame-messages.yml
waterbucket.server.shutdown: "§cProxy shutdown"
waterbucket.downstream.transfer.failed: "§cConnection to {%0} failed: {%1}"
waterbucket.command.server.sent: "§aPlayer {%0} was sent to {%1} server!"
```

§ codes for colors, `{%0}` placeholders per message, English fallback when a line is deleted.
Full walkthrough in the file's header comment.

## Configuration highlights

| Option | Default | Purpose |
|---|---|---|
| `enable_panel` | `true` | serve the web dashboard from the proxy |
| `panel_bind_address` | `127.0.0.1` | keep loopback unless you front it with TLS |
| `panel_port` | `8080` | `0` picks a free port |
| `panel_token` | *(auto)* | API token; generated to `panel_token.txt` |
| `upstream_compression_level` | `6` | zlib level proxy → client |
| `downstream_compression_level` | `2` | zlib level proxy → servers |
| `prefer_fast_transfer` | `true` | intercept downstream `TransferPacket`s into fast transfers |

The full configuration lives in `configs/config.txt` with inline comments.

## Plugins

The classic jar API is untouched; Python plugins are plain folders next to your jars:

```yaml
# plugins/HelloPy/waterbucket.yml
name: HelloPy
main: main.py
```

```python
def on_enable(plugin):
    PlayerChatEvent = java.type("dev.waterbucket.waterbucketpe.event.defaults.PlayerChatEvent")
    plugin.subscribe(PlayerChatEvent, lambda event: logger.info(
        "{} says {}", event.getPlayer().getName(), event.getMessage()))
```

Full documentation for both languages, including a facade reference and troubleshooting,
is in [docs/PLUGINS.md](docs/PLUGINS.md) — with runnable examples under
[examples/plugins/](examples/plugins/) and in each release's `plugin-examples.zip`.

## Documentation

- [Plugin API — Java & Python](docs/PLUGINS.md) · [نسخه فارسی](docs/PLUGINS.fa.md)
- [Changelog](CHANGELOG.md) — every release, evidence-backed
- [Benchmark methodology](https://github.com/AmirShafi6819/WaterbucketPE/tree/v1.4.0/benchmarks/METHODOLOGY.md) — the harness and its caveats
- [Compiling](COMPILING.md) · [Security policy](SECURITY.md)

## Roadmap

- **Fallback routing** — automatic player failover when a downstream dies, with configurable
  priorities and a reconnect flow that keeps the player in the world, not on an error screen.
  *Coming in the next feature release.*
- Further panel tooling: scheduled restarts and per-server console filters.

## Building from source

```bash
git clone https://github.com/AmirShafi6819/WaterbucketPE.git
cd WaterbucketPE
./gradlew shadowJar        # build/libs/Waterbucket.jar
./gradlew test             # 59 suites, 339 tests
```

JDK 17+ is required; the jar embeds a Python runtime, so no GraalPy install is needed.

## Contributing

Read [CONTRIBUTING.md](CONTRIBUTING.md) before opening a PR: every behavior fix ships with a
regression test, and backwards compatibility is a contract, not a wish. PR template included.

## Acknowledgments

Forked from [WaterdogPE](https://github.com/WaterdogPE/WaterdogPE) — the clean base that made
this fork possible. Built on [CloudburstMC protocol](https://github.com/CloudburstMC/Protocol),
Netty, and [GraalPy](https://github.com/oracle/graalpython).

## License

[GPL v2](LICENSE) — WaterBucketTEAM. Water Bucket is not affiliated with Mojang or Microsoft.
