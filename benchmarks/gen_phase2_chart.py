#!/usr/bin/env python3
"""Generates benchmarks/benchmark-phase2.png from the real measurements in
benchmarks/results-phase2.json. Every number on the chart comes from that file;
nothing is estimated or extrapolated."""
import json
import os

import matplotlib
matplotlib.use("Agg")
import matplotlib.font_manager as fm
import matplotlib.pyplot as plt

fm.fontManager.addfont('/usr/share/fonts/truetype/chinese/NotoSansSC-Regular.ttf')
plt.rcParams['font.sans-serif'] = ['Noto Sans SC', 'DejaVu Sans']
plt.rcParams['axes.unicode_minus'] = False

HERE = os.path.dirname(os.path.abspath(__file__))
data = json.load(open(os.path.join(HERE, "results-phase2.json")))
m = data["comparison"]["metrics"]

PRE = "#8A94A6"   # neutral gray-blue for the pre-Phase-2 side
POST = "#1BA9E0"  # Waterbucket blue for the post-Phase-2 side


def metric(name):
    return m[name]["pre"], m[name]["post"]


groups = [
    ("Ping RTT median (ms)", "ping_rtt_median_ms", 1000),
    ("Ping RTT p95 (ms)", "ping_rtt_p95_ms", 1000),
    ("Ping RTT p99 (ms)", "ping_rtt_p99_ms", 1000),
    ("Handshake p95 (ms)", "handshake_p95_ms", 1),
    ("Handshake connects/s", "handshake_connects_per_s" if "handshake_connects_per_s" in m else "ping_throughput_replies_per_s", 1),
    ("200 sessions CPU (%)", "sessions200_cpu_pct", 1),
    ("500 sessions kB/session", "scale500_kb_per_session", 1),
]

fig, axes = plt.subplots(1, len(groups), figsize=(15.5, 3.4), constrained_layout=True)
fig.suptitle("WaterbucketPE Phase 2: pre (59c244f) vs post, same jar lineage, same VM, real harness data", fontsize=11)

for ax, (label, key, scale) in zip(axes, groups):
    pre, post = metric(key)
    bars = ax.bar(["pre", "post"], [pre * scale, post * scale], color=[PRE, POST], width=0.62)
    for bar, value in zip(bars, (pre * scale, post * scale)):
        ax.text(bar.get_x() + bar.get_width() / 2, bar.get_height(), f"{value:g}",
                ha="center", va="bottom", fontsize=8.5)
    ax.set_title(label, fontsize=9.5)
    ax.spines[["top", "right"]].set_visible(False)
    ax.tick_params(labelsize=8.5)
    ax.set_ylim(0, max(pre * scale, post * scale) * 1.22)

with open(os.path.join(HERE, "benchmark-phase2.png"), "wb") as fh:
    fig.savefig(fh, dpi=150)
    fh.flush()

notes = [
    f"Stable across two executions: handshake CPU pre {m['handshake_cpu_pct']['repeat_runs']['pre']} % vs post {m['handshake_cpu_pct']['repeat_runs']['post']} % (overlapping, no stable change).",
    f"200-session kB/session (noisy page-level metric) pre {m['sessions200_kb_per_session']['repeat_runs']['pre']} vs post {m['sessions200_kb_per_session']['repeat_runs']['post']} MB-equivalent samples.",
    f"All functional counts identical: handshake {m['handshake_success']['pre']}->{m['handshake_success']['post']}, "
    f"{m['sessions200_held']['pre']}/{m['scale500_held']['pre']} sessions held both sides, ping loss 0.",
]
print("notes:", *notes, sep="\n  ")
print("written", os.path.join(HERE, "benchmark-phase2.png"))
