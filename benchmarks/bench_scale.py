#!/usr/bin/env python3
"""Progressive concurrent-session scale validation for WaterBucketPE.

Spec section 11 (500+ CONCURRENT PLAYER VALIDATION): drive the live proxy
through cumulative hold stages (50 -> 600 sessions), sampling proxy RSS/CPU
and control-plane RTT at each stage, then run an offline storm (open/close
waves) and a recovery probe. Any stage that leaves the proxy unable to
answer unconnected pings 5 times in a row is recorded as UNSAFE and the run
aborts - no capacity number is reported unless it was actually observed.

The session population is cumulative: one RakBenchClient per stage is
spawned with holdSeconds<0 (hold forever) and never closed, mirroring how
real players accumulate rather than connect/disconnect in lockstep.
"""
import json
import os
import re
import select
import signal
import socket
import statistics
import subprocess
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from bench import (  # noqa: E402
    JAVA, JVM_FLAGS, PORT, PING_PACE_S, ProxyProcess, ping_once, rss_kb,
    cpu_seconds, run_ping_rtt, CLIENT_DIR, CLIENT_CLASSPATH,
)

STAGES = [50, 100, 200, 300, 400, 500, 600]
STORM_ROUNDS = 5
STORM_SIZE = 200
RTT_COUNT = 400


def wait_holding(proc, expected, deadline=120.0):
    """Read client stdout until HOLDING <n> appears (n>=expected) or deadline."""
    t0 = time.perf_counter()
    buf = b""
    while time.perf_counter() - t0 < deadline:
        if proc.poll() is not None:
            return None, "client exited early rc=%s" % proc.returncode
        r, _, _ = select.select([proc.stdout], [], [], 1.0)
        if r:
            chunk = os.read(proc.stdout.fileno(), 4096)
            if not chunk:
                return None, "client stdout closed"
            buf += chunk
            m = re.search(rb"HOLDING (\d+)", buf)
            if m and int(m.group(1)) >= expected:
                return int(m.group(1)), None
            if b"FAIL" in buf and b"HOLDING" not in buf and len(buf) > 512:
                # keep waiting; early FAILs are normal while the wave ramps
                pass
    tail = buf[-800:].decode(errors="replace")
    return None, "timeout waiting for HOLDING>=%d; tail=%s" % (expected, tail)


def spawn_hold_client(n):
    cmd = [JAVA, "-cp", f"{CLIENT_DIR}:{CLIENT_CLASSPATH}", "RakBenchClient",
           "hold", "127.0.0.1", str(PORT), str(n), "-1"]
    return subprocess.Popen(cmd, stdout=subprocess.PIPE,
                            stderr=subprocess.DEVNULL, text=False)


def sample_rtt(sock, count=RTT_COUNT):
    rtts, lost = run_ping_rtt(sock, count=count)
    if not rtts:
        return None
    q = statistics.quantiles(rtts, n=20) if len(rtts) > 20 else [None] * 19
    return {
        "samples": len(rtts), "lost": lost,
        "median_ms": statistics.median(rtts) * 1e3,
        "p95_ms": (q[18] * 1e3) if q[18] is not None else None,
    }


def pct(nums, p):
    if not nums:
        return None
    vals = sorted(nums)
    k = max(0, min(len(vals) - 1, int(round(p / 100 * len(vals))) - 1))
    return vals[k]


def cpu_window(pid, seconds=5.0):
    c0 = cpu_seconds(pid)
    time.sleep(seconds)
    c1 = cpu_seconds(pid)
    return (c1 - c0) / seconds * 100.0 if (c1 is not None and c0 is not None) else None


def main():
    jar = os.path.abspath(sys.argv[1])
    out_path = os.path.abspath(sys.argv[2]) if len(sys.argv) > 2 else "results-scale.json"
    max_stage = int(sys.argv[sys.argv.index("--max-stage") + 1]) if "--max-stage" in sys.argv else None
    workdir = "/tmp/wb-scale"
    os.makedirs(workdir, exist_ok=True)
    stages = [s for s in STAGES if max_stage is None or s <= max_stage]

    result = {
        "jar": jar, "date_utc": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        "java": JAVA, "jvm_flags": " ".join(JVM_FLAGS),
        "stages_configured": stages, "storm_rounds": STORM_ROUNDS,
        "storm_size": STORM_SIZE, "stages": [], "status": "running",
    }

    proxy = ProxyProcess(jar, workdir)
    pid = proxy.start(os.path.join(workdir, "server.log"))
    listen = proxy.wait_listening()
    if listen is None:
        result["status"] = "PROXY_FAILED_TO_START"
        shutil_copy_log(workdir, out_path)
        json.dump(result, open(out_path, "w"), indent=2)
        print("PROXY FAILED TO START"); sys.exit(1)
    result["startup_seconds"] = listen
    print(f"proxy up in {listen:.1f}s pid={pid}")

    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.bind(("127.0.0.1", 0))
    sock.settimeout(1.0)

    prev_rss = None
    prev_total = 0
    clients = []
    try:
        for stage_n in stages:
            # cumulative population target: the new client opens only the
            # delta so the live session count equals the stage number
            delta = stage_n - prev_total
            print(f"=== stage {stage_n} total sessions (delta {delta}) ===", flush=True)
            c = spawn_hold_client(delta)
            held, err = wait_holding(c, delta)
            rec = {"stage": stage_n, "sessions_total": stage_n}
            if held is None:
                rec["error"] = err
                result["stages"].append(rec)
                result["status"] = "UNSAFE_STAGE_%d" % stage_n
                proxy_alive = proxy.proc.poll() is None
                rec["proxy_alive_at_abort"] = proxy_alive
                print("ABORT:", err, flush=True)
                break
            rec["opened_this_stage"] = held
            rec["rss_kb"] = rss_kb(pid)
            rec["rss_delta_kb_vs_prev"] = (rec["rss_kb"] - prev_rss) if prev_rss else None
            rec["kb_per_new_session"] = (rec["rss_delta_kb_vs_prev"] / held) if rec["rss_delta_kb_vs_prev"] else None
            rec["kb_per_session_cumulative"] = rec["rss_kb"] / stage_n
            rec["cpu_pct_rtt_window"] = cpu_window(pid, 3.0)
            # client JVM footprint is reported separately and honestly
            client_rss_total = 0
            for cc in clients + [c]:
                try:
                    client_rss_total += rss_kb(cc.pid) or 0
                except Exception:
                    pass
            rec["client_jvm_rss_mb_total"] = client_rss_total / 1024
            rtt = sample_rtt(sock)
            rec["rtt"] = rtt
            prev_rss = rec["rss_kb"]
            prev_total = stage_n
            result["stages"].append(rec)
            print(json.dumps(rec), flush=True)
            clients.append(c)

            # safety gate: 5 consecutive ping losses => unsafe
            lost_streak = 0
            for _ in range(5):
                if ping_once(sock) is None:
                    lost_streak += 1
            if lost_streak >= 5:
                rec["unsafe_ping_loss_streak"] = lost_streak
                result["status"] = "UNSAFE_STAGE_%d" % stage_n
                print("UNSAFE: ping loss streak at stage", stage_n, flush=True)
                break
        else:
            result["status"] = "OK_ALL_STAGES"

        # offline storm: open/close waves, measure RSS churn
        if result["status"] == "OK_ALL_STAGES":
            print("=== offline storm ===", flush=True)
            storm = []
            base_rss = rss_kb(pid)
            for r in range(STORM_ROUNDS):
                t0 = time.perf_counter()
                c = spawn_hold_client(STORM_SIZE)
                held, err = wait_holding(c, STORM_SIZE)
                if held is None:
                    storm.append({"round": r, "error": err})
                    break
                peak = rss_kb(pid)
                c.terminate()
                try:
                    c.wait(timeout=10)
                except Exception:
                    c.kill()
                time.sleep(2.0)
                after = rss_kb(pid)
                storm.append({
                    "round": r, "opened": held,
                    "open_seconds": time.perf_counter() - t0,
                    "rss_peak_kb": peak, "rss_after_close_kb": after,
                    "rss_delta_kb": peak - base_rss,
                })
                print(storm[-1], flush=True)
            result["storm"] = storm
            r2 = sample_rtt(sock, count=200)
            result["recovery_rtt"] = r2
            print("recovery:", json.dumps(r2), flush=True)
    finally:
        for c in clients:
            try:
                c.terminate(); c.wait(timeout=5)
            except Exception:
                try: c.kill()
                except Exception: pass
        rss_final = rss_kb(pid) if proxy.proc.poll() is None else None
        result["final_rss_kb"] = rss_final
        proxy.stop()
        sock.close()

    json.dump(result, open(out_path, "w"), indent=2)
    print("WROTE", out_path, "status:", result["status"])


def shutil_copy_log(workdir, out_path):
    import shutil
    dst = "/tmp/scale-abort-server.log"
    try:
        shutil.copy(os.path.join(workdir, "server.log"), dst)
        print("server.log copied to", dst)
    except Exception as e:
        print("log copy failed:", e)


if __name__ == "__main__":
    main()
