#!/usr/bin/env python3
"""Generate the round-2 benchmark chart from measured results (benchmarks/results.json).

All numbers come from bench.py: real UDP packets, real RakNet handshakes via the same
CloudburstMC library the proxy uses, and /proc measurements - no synthetic values.
"""
import json
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
import numpy as np

WD = "#5B6B8C"   # WaterdogPE (slate)
WB = "#1BA9E0"   # WaterbucketPE (water blue)
GRID = "#D8DEE9"
TXT = "#2E3440"

with open("/home/z/my-project/WaterbucketPE/benchmarks/results.json") as f:
    data = json.load(f)
summary = data["summary"]
wd = summary["WaterdogPE"]
wb = summary["WaterbucketPE"]

fig, axes = plt.subplots(2, 3, figsize=(14.5, 8.0), constrained_layout=True)
fig.suptitle("WaterbucketPE 1.2.0 vs WaterdogPE 2.0.4 - round 3 (same upstream commit, same machine, same JVM flags)",
             fontsize=13, fontweight="bold", color=TXT)


def honest_delta(wd_val, wb_val):
    """Signed percentage change of WaterbucketPE vs WaterdogPE; negative = Waterbucket lower."""
    return (wb_val / wd_val - 1.0) * 100.0


def grouped(ax, title, labels, wd_vals, wb_vals, unit="", fmt="{:.2f}", lower_is_better=None, ylabel=None):
    x = np.arange(len(labels))
    w = 0.36
    b1 = ax.bar(x - w / 2, [max(0.0, v) for v in wd_vals], w, label="WaterdogPE 2.0.4", color=WD, zorder=3)
    b2 = ax.bar(x + w / 2, [max(0.0, v) for v in wb_vals], w, label="WaterbucketPE 1.2.0", color=WB, zorder=3)
    for bars in (b1, b2):
        for rect, raw in zip(bars, (wd_vals if bars is b1 else wb_vals)):
            ax.annotate(fmt.format(raw) + unit,
                        (rect.get_x() + rect.get_width() / 2, rect.get_height()),
                        textcoords="offset points", xytext=(0, 3), ha="center", fontsize=8, color=TXT)
    qualifier = " - lower is better" if lower_is_better is True else " - higher is better" if lower_is_better is False else ""
    ax.set_title(title + qualifier, fontsize=10, color=TXT, fontweight="bold")
    ax.set_xticks(x, labels, fontsize=9, color=TXT)
    ax.tick_params(axis="y", labelsize=8, colors=TXT)
    if ylabel:
        ax.set_ylabel(ylabel, fontsize=9, color=TXT)
    ax.grid(axis="y", color=GRID, zorder=0)
    ax.set_axisbelow(True)
    for spine in ("top", "right"):
        ax.spines[spine].set_visible(False)
    ax.legend(fontsize=8, frameon=False)


def annotate_winner(ax, text):
    ax.text(0.02, 0.94, text, transform=ax.transAxes, fontsize=9, color="#1BA9E0",
            fontweight="bold", va="top", bbox=dict(facecolor="#EAF6FD", edgecolor="none", boxstyle="round,pad=0.3"))


def annotate_note(ax, text):
    ax.text(0.02, 0.94, text, transform=ax.transAxes, fontsize=8, color="#5B6B8C",
            fontweight="bold", va="top", bbox=dict(facecolor="#EEF1F7", edgecolor="none", boxstyle="round,pad=0.3"))


# 1. Startup (median of 3 runs)
grouped(axes[0][0], "Cold startup", ["median of 3 runs"],
        [wd["startup_s_median"]], [wb["startup_s_median"]], unit=" s", fmt="{:.2f}", ylabel="seconds")

# 2. Idle RSS
grouped(axes[0][1], "Idle memory (RSS)", ["after 15s settle"],
        [wd["idle_rss_mb_median"]], [wb["idle_rss_mb_median"]], unit=" MB", fmt="{:.0f}", ylabel="MB")
annotate_winner(axes[0][1], "-{:.0f}%".format((1 - wb["idle_rss_mb_median"] / wd["idle_rss_mb_median"]) * 100))

# 3. Ping latency (median/p95/p99, per proxy, from that proxy's own result record)
wd_rtt = data["results"][0]["ping_rtt_ms"]
wb_rtt = data["results"][1]["ping_rtt_ms"]
grouped(axes[0][2], "RakNet ping latency", ["median", "p95", "p99"],
        [wd_rtt["median"], wd_rtt["p95"], wd_rtt["p99"]],
        [wb_rtt["median"], wb_rtt["p95"], wb_rtt["p99"]],
        unit=" ms", fmt="{:.2f}", lower_is_better=True, ylabel="ms")
delta = honest_delta(wd_rtt["median"], wb_rtt["median"])
if delta <= 0:
    annotate_winner(axes[0][2], "{:+.0f}% median".format(delta))
else:
    annotate_note(axes[0][2], "{:+.0f}% median".format(delta))

# 4. Per-session memory at 200 live sessions (RSS delta during the hold window; a noisy
#    page-level metric - the class-histogram diff in METHODOLOGY.md is the object-level view)
grouped(axes[1][0], "Memory per live session (200 held)", ["RSS delta / session"],
        [wd["kb_per_session"]], [wb["kb_per_session"]], unit=" KB", fmt="{:.1f}", ylabel="KB")
delta = honest_delta(wd["kb_per_session"], wb["kb_per_session"])
if delta <= 0:
    annotate_winner(axes[1][0], "{:+.0f}%".format(delta))
else:
    annotate_note(axes[1][0], "{:+.0f}% (RSS delta, noisy)".format(delta))

# 5. Handshake churn: connect rate + latency
grouped(axes[1][1], "Handshake churn (300 real connects)", ["connects/s", "median connect ms"],
        [wd["handshake_connects_per_sec"], wd["handshake_connect_ms_median"]],
        [wb["handshake_connects_per_sec"], wb["handshake_connect_ms_median"]],
        fmt="{:.1f}", lower_is_better=None)
axes[1][1].annotate("connects/s higher is better; connect ms lower is better",
                    (0.5, 0.94), xycoords="axes fraction", ha="center", fontsize=8, color=TXT)

# 6. Scale test: 500 concurrent sessions
grouped(axes[1][2], "Scale: 500 concurrent sessions", ["held sessions", "KB / session"],
        [wd["scale_held"], max(0.0, wd["scale_kb_per_session"])],
        [wb["scale_held"], max(0.0, wb["scale_kb_per_session"])],
        fmt="{:.0f}", lower_is_better=None)
axes[1][2].annotate("held: Waterdog {}/500, Waterbucket {}/500; KB/session measured during the hold".format(
                        int(wd["scale_held"]), int(wb["scale_held"])),
                    (0.5, 0.94), xycoords="axes fraction", ha="center", fontsize=7.5, color=TXT)

out = "/home/z/my-project/WaterbucketPE/benchmarks/benchmark.png"
fig.savefig(out, dpi=160, facecolor="white")
print("saved", out)

# honest note printed for the release notes
print(json.dumps({
    "idle_rss_mb": {"waterdog": wd["idle_rss_mb_median"], "waterbucket": wb["idle_rss_mb_median"]},
    "kb_per_session_200": {"waterdog": wd["kb_per_session"], "waterbucket": wb["kb_per_session"]},
    "startup_s": {"waterdog": wd["startup_s_median"], "waterbucket": wb["startup_s_median"]},
    "churn_growth_mb": {"waterdog": wd["churn_growth_mb"], "waterbucket": wb["churn_growth_mb"]},
}, indent=2))
