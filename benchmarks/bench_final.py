# WaterbucketPE final-phase benchmark driver
#
# Runs the same measurement primitives as bench.py (identical packet code, identical
# /proc sampling, identical JVM flags and config) with a bounded phase list so the full
# before/after pair fits into constrained execution windows. Every parameter below is
# applied IDENTICALLY to both jars; the scope difference vs. the round-3 harness is
# documented in METHODOLOGY.md.
#
# Usage: python3 benchmarks/bench_final.py <jar> <name> <out.json>

import importlib.util
import json
import os
import socket
import statistics
import sys
import tempfile
import time
import shutil

_SPEC = importlib.util.spec_from_file_location(
    "bench", os.path.join(os.path.dirname(os.path.abspath(__file__)), "bench.py"))
bench = importlib.util.module_from_spec(_SPEC)
_SPEC.loader.exec_module(bench)


def bench_jar_bounded(jar, name, runs=2, ping_count=400, churn_count=100, hold_count=120,
                      hold_seconds=6, leak_rounds=2):
    result = {"name": name, "jar": os.path.basename(jar), "jvm_flags": " ".join(bench.JVM_FLAGS),
              "port": bench.PORT, "params": {
                  "startup_runs": runs, "ping_count": ping_count, "churn_count": churn_count,
                  "hold_count": hold_count, "hold_seconds": hold_seconds, "leak_rounds": leak_rounds},
              "startup": [], "idle_rss_kb": [], "idle_cpu_pct": []}

    for run_idx in range(runs):
        workdir = tempfile.mkdtemp(prefix="wb-finbench-")
        pp = bench.ProxyProcess(jar, workdir)
        log_path = os.path.join(workdir, "server.log")
        try:
            pid = pp.start(log_path)
            startup = pp.wait_listening()
            if startup is None:
                # The workdir is removed below; surface the boot log tail in the error,
                # otherwise a startup failure is undiagnosable.
                try:
                    with open(log_path, "r", errors="replace") as fh:
                        tail = "".join(fh.readlines()[-25:])
                except OSError:
                    tail = "<log unavailable>"
                raise RuntimeError(f"{name} failed to start (log tail:\n{tail})")
            result["startup"].append(startup)
            time.sleep(15)  # JIT warm-up / settle (same as bench.py)
            result["idle_rss_kb"].append(bench.rss_kb(pid))
            c0, t0 = bench.cpu_seconds(pid), time.perf_counter()
            time.sleep(10)
            cpu = (bench.cpu_seconds(pid) - c0) / (time.perf_counter() - t0) * 100.0
            result["idle_cpu_pct"].append(cpu)
        finally:
            pp.stop()
            shutil.rmtree(workdir, ignore_errors=True)

    # Warm, long-lived process for the traffic phases (same shape as bench.py)
    workdir = tempfile.mkdtemp(prefix="wb-finbench-traffic")
    pp = bench.ProxyProcess(jar, workdir)
    try:
        pid = pp.start(os.path.join(workdir, "server.log"))
        assert pp.wait_listening() is not None
        time.sleep(10)

        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.bind(("127.0.0.1", 0))
        rtts, lost = bench.run_ping_rtt(s, ping_count)
        s.close()
        result["ping_rtt_ms"] = {
            "count": len(rtts), "lost": lost,
            "median": statistics.median(rtts) * 1000,
            "p95": statistics.quantiles(rtts, n=20)[18] * 1000 if len(rtts) > 20 else None,
        }

        result["concurrent_ping"] = bench.run_concurrent_pings(threads=4, per_thread=50)

        c0, t0 = bench.cpu_seconds(pid), time.perf_counter()
        churn = bench.run_java_client("handshake", churn_count)
        churn_wall = time.perf_counter() - t0
        result["handshake_churn"] = bench.summarize_client(churn, {
            "wall_s": churn_wall,
            "cpu_pct": (bench.cpu_seconds(pid) - c0) / churn_wall * 100.0,
            "connects_per_sec": churn.get("ok", 0) / churn_wall,
        })

        rss_before = bench.rss_kb(pid)
        c0, t0 = bench.cpu_seconds(pid), time.perf_counter()
        hold = bench.run_java_client("hold", hold_count, hold_seconds=hold_seconds)
        hold_wall = time.perf_counter() - t0
        result["live_sessions"] = bench.summarize_client(hold, {
            "held": hold.get("ok", 0),
            "rss_delta_kb": bench.rss_kb(pid) - rss_before,
            "kb_per_session": (bench.rss_kb(pid) - rss_before) / max(1, hold.get("ok", 1)),
            "cpu_pct": (bench.cpu_seconds(pid) - c0) / hold_wall * 100.0,
        })

        rss_base = bench.rss_kb(pid)
        growth = []
        for _round in range(leak_rounds):
            bench.run_java_client("handshake", 80)
            time.sleep(4)
            growth.append(round((bench.rss_kb(pid) - rss_base) / 1024.0, 2))
        result["churn_growth_mb"] = growth
    finally:
        pp.stop()
        shutil.rmtree(workdir, ignore_errors=True)

    return result


if __name__ == "__main__":
    jar, name, out_path = sys.argv[1], sys.argv[2], sys.argv[3]
    # The proxy runs with cwd=tempdir; a relative jar path would resolve there and fail.
    jar = os.path.abspath(jar)
    result = bench_jar_bounded(jar, name)
    with open(out_path, "w") as fh:
        json.dump({"results": [result]}, fh, indent=2)
    print(json.dumps({
        "startup_s_median": statistics.median(result["startup"]),
        "idle_rss_mb": statistics.median(result["idle_rss_kb"]) / 1024,
        "idle_cpu_pct": statistics.median(result["idle_cpu_pct"]),
        "ping_rtt_ms_median": result["ping_rtt_ms"]["median"],
        "handshake_median_ms": result["handshake_churn"].get("connect_ms_median"),
        "handshake_cpu_pct": result["handshake_churn"].get("cpu_pct"),
        "sessions_held": result["live_sessions"].get("held"),
        "kb_per_session": result["live_sessions"].get("kb_per_session"),
        "churn_growth_mb": result["churn_growth_mb"],
    }, indent=2))
