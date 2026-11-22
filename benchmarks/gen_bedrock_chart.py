#!/usr/bin/env python3
"""Merge the Bedrock-level A/B runs into results-bedrock.json and render the chart.

Inputs: the JSON files bench_bedrock.py produced for each side and round
(bb-pre-2/3, bb-post-2/3 in this run). Outputs:
  benchmarks/results-bedrock.json   - raw per-run data + per-round medians
  benchmarks/bench_bedrock_chart.png - grouped bar chart, pre vs post
"""
import json
import os
import statistics

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt

HERE = os.path.dirname(os.path.abspath(__file__))

RUNS = {
    "pre": ["/tmp/bb-pre-2.json", "/tmp/bb-pre-3.json"],
    "post": ["/tmp/bb-post-2.json", "/tmp/bb-post-3.json"],
}

SCENARIOS = ["login", "rtt", "switch"]
FIELDS = ["median_ms", "p95_ms"]
LABELS = {
    "login": "Login (connect -> StartGame)",
    "rtt": "In-game text round-trip",
    "switch": "Fast transfer (server switch)",
}
COLORS = {"pre": "#c05a3e", "post": "#3e7ac0"}


def load_run(path):
    with open(path) as fh:
        data = json.load(fh)
    return data["results"][0]


def main():
    merged = {"pre": [], "post": []}
    for side, paths in RUNS.items():
        for path in paths:
            run = load_run(path)
            merged[side].append({
                "startup_s": run.get("startup_s"),
                "login": run.get("login"),
                "rtt": run.get("rtt"),
                "switch": run.get("switch"),
                "login_rss_delta_kb": run.get("login_rss_delta_kb"),
                "switch_rss_delta_kb": run.get("switch_rss_delta_kb"),
                "source": os.path.basename(path),
            })

    summary = {}
    for scenario in SCENARIOS:
        summary[scenario] = {}
        for field in FIELDS:
            summary[scenario][field] = {}
            for side in ("pre", "post"):
                vals = [r[scenario].get(field) for r in merged[side] if r[scenario].get(field) is not None]
                summary[scenario][field][side] = vals
        summary[scenario]["ok"] = {side: [r[scenario]["ok"] for r in merged[side]] for side in ("pre", "post")}
        summary[scenario]["failed"] = {side: [r[scenario]["failed"] for r in merged[side]] for side in ("pre", "post")}

    import hashlib
    jar_hashes = {}
    for name, path in [("pre", "/tmp/wb-pre/build/libs/Waterbucket.jar"),
                       ("post", os.path.join(HERE, "..", "build", "libs", "Waterbucket.jar"))]:
        h = hashlib.sha256()
        with open(path, "rb") as fh:
            for chunk in iter(lambda: fh.read(1 << 20), b""):
                h.update(chunk)
        jar_hashes[name] = h.hexdigest()

    out = {
        "environment": {
            "date": "2026-11-22",
            "java": "Temurin 17.0.21.1 (both proxy and bench tool)",
            "jvm_flags": "-Xms128M -Xmx512M -XX:+UseG1GC -XX:MaxGCPauseMillis=200",
            "harness": "benchmarks/bench_bedrock.py + benchmarks/BedrockBench.java (Bedrock protocol 560 / 1.19.50)",
            "pre_side": "this repository at 59c244f (v1.2.0, pre-Phase-2)",
            "post_side": "this repository at master (v1.3.0)",
            "jar_sha256": jar_hashes,
            "config": "online_mode off, login_throttle off, connection_throttle off; identical for both sides",
            "scenario_counts": {"login": 30, "rtt": 300, "switch": 30},
            "vm": "shared 2-core VM, same as the round-3 and Phase-2 A/B runs",
        },
        "runs": merged,
        "summary": summary,
    }
    out_path = os.path.join(HERE, "results-bedrock.json")
    with open(out_path, "w") as fh:
        json.dump(out, fh, indent=2)
    print("wrote", out_path)

    # chart: one subplot per scenario, medians with min-max whiskers over the rounds
    fig, axes = plt.subplots(1, 3, figsize=(12.5, 4.4), constrained_layout=True)
    for ax, scenario in zip(axes, SCENARIOS):
        positions = {"pre": 0, "post": 1}
        width = 0.35
        for side in ("pre", "post"):
            med = statistics.mean(summary[scenario]["median_ms"][side])
            lo = min(summary[scenario]["median_ms"][side])
            hi = max(summary[scenario]["median_ms"][side])
            p95 = statistics.mean(summary[scenario]["p95_ms"][side]) if any(
                v is not None for v in summary[scenario]["p95_ms"][side]) else None
            ax.bar(positions[side] - width / 2 if side == "pre" else positions[side] + width / 2,
                   med, width, yerr=[[med - lo], [hi - med]], capsize=4,
                   color=COLORS[side], label=side.upper() if scenario == "login" else None)
            if p95 is not None:
                ax.plot(positions[side] + (width / 2 if side == "post" else -width / 2), p95,
                        marker="D", color="#333333", markersize=5)
        ax.set_title(LABELS[scenario], fontsize=11)
        ax.set_ylabel("ms")
        ax.set_xticks([0, 1])
        ax.set_xticklabels(["PRE\n(v1.2.0)", "POST\n(v1.3.0)"])
        ax.grid(axis="y", alpha=0.3)
        if p95 is not None:
            ax.scatter([], [], marker="D", color="#333333", s=18,
                       label="p95 (mean over rounds)" if scenario == "login" else None)
    axes[0].legend(fontsize=9, loc="upper right")
    fig.suptitle("Bedrock-level A/B: pre-Phase-2 (59c244f) vs 1.3.0 - medians over two rounds, whiskers = round range",
                 fontsize=12)
    chart_path = os.path.join(HERE, "bench_bedrock_chart.png")
    fig.savefig(chart_path, dpi=150)
    print("wrote", chart_path)


if __name__ == "__main__":
    main()
