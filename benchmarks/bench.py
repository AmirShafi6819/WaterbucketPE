#!/usr/bin/env python3
"""
WaterbucketPE A/B benchmark harness.

Runs a WaterbucketPE or WaterdogPE jar through identical, measurable workloads and
reports raw numbers (JSON) so charts can be generated from real data:

  1. cold startup time (process start -> first successful RakNet unconnected ping reply)
  2. idle RSS / idle CPU after a warm-up settle
  3. unconnected ping RTT distribution (median / p95 / p99), paced under the proxy's
     per-address packet limit
  4. concurrent ping throughput (8 sockets, paced)
  5. RakNet session handshakes driven by RakBenchClient (the same CloudburstMC RakNet
     library the proxy uses, i.e. byte-for-byte what real connections perform):
     success rate, per-handshake latency, connect rate
  6. RSS / CPU with 200 live sessions held open

Both variants run with identical JVM flags and an identical config.yml
(connection_throttle disabled so the harness measures handshake performance, not the
DoS protection). Everything is measured from outside the JVM (UDP packets, /proc),
and repeat runs are stored raw so the chart can show medians honestly.
"""
import json
import os
import shutil
import signal
import socket
import statistics
import struct
import subprocess
import sys
import tempfile
import threading
import time

MAGIC = bytes.fromhex("00ffff00fefefefefdfdfdfd12345678")
ID_PING, ID_PONG = 0x01, 0x1C
PORT = 19132
JAVA = os.environ.get("BENCH_JAVA", "/home/z/jdk17/bin/java")
JVM_FLAGS = ["-Xms128M", "-Xmx512M", "-XX:+UseG1GC", "-XX:MaxGCPauseMillis=200"]
PING_PACE_S = 0.015  # RakNet rate limits packets per source address; stay well under it
GUID = 0x1122334455667788

BENCH_CONFIG = """network_settings:
  # The harness measures handshake performance, not the DoS protection,
  # so the per-address connection throttle is disabled for both variants alike.
  connection_throttle: -1
"""

CLIENT_CLASSPATH = os.environ.get("BENCH_CLIENT_JAR", "/home/z/my-project/WaterbucketPE/build/libs/Waterbucket.jar")
CLIENT_DIR = os.environ.get("BENCH_CLIENT_CLASSES", "/tmp/wbtest7")


def ping_packet(now_ns):
    return struct.pack(">Bq", ID_PING, now_ns) + MAGIC + struct.pack(">q", GUID)


def recv_reply(sock, want_ids, timeout=1.0):
    sock.settimeout(timeout)
    end = time.perf_counter()
    while time.perf_counter() - end < timeout:
        try:
            data, _ = sock.recvfrom(65535)
        except socket.timeout:
            return None
        if data and data[0] in want_ids:
            return data
    return None


def ping_once(sock):
    """One unconnected ping; returns RTT seconds or None."""
    sock.settimeout(0.5)
    t0 = time.perf_counter()
    sock.sendto(ping_packet(int(t0 * 1e9)), ("127.0.0.1", PORT))
    reply = recv_reply(sock, (ID_PONG,), timeout=0.5)
    if reply is None:
        return None
    return time.perf_counter() - t0


def rss_kb(pid):
    with open(f"/proc/{pid}/status") as fh:
        for line in fh:
            if line.startswith("VmRSS:"):
                return int(line.split()[1])
    return 0


def cpu_seconds(pid):
    with open(f"/proc/{pid}/stat") as fh:
        fields = fh.read().split()
    utime, stime = int(fields[13]), int(fields[14])
    return (utime + stime) / os.sysconf("SC_CLK_TCK")


class ProxyProcess:
    def __init__(self, jar, workdir):
        self.jar = jar
        self.workdir = workdir
        self.proc = None

    def start(self, log_path):
        with open(os.path.join(self.workdir, "config.yml"), "w") as fh:
            fh.write(BENCH_CONFIG)
        env = dict(os.environ)
        env["JAVA_HOME"] = "/home/z/jdk17"
        self.log = open(log_path, "wb")
        self.proc = subprocess.Popen(
            [JAVA, *JVM_FLAGS, "-jar", self.jar],
            cwd=self.workdir, stdout=self.log, stderr=subprocess.STDOUT,
            env=env, preexec_fn=os.setsid,
        )
        return self.proc.pid

    def wait_listening(self, deadline=120.0):
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.bind(("127.0.0.1", 0))
        t0 = time.perf_counter()
        try:
            while time.perf_counter() - t0 < deadline:
                if self.proc.poll() is not None:
                    return None  # crashed during startup
                rtt = ping_once(s)
                if rtt is not None:
                    return time.perf_counter() - t0
                time.sleep(0.05)
            return None
        finally:
            s.close()

    def stop(self):
        if self.proc is None:
            return
        try:
            os.killpg(self.proc.pid, signal.SIGTERM)
            self.proc.wait(timeout=15)
        except Exception:
            try:
                os.killpg(self.proc.pid, signal.SIGKILL)
            except Exception:
                pass


def run_ping_rtt(sock, count=1500):
    """Sequential pings, paced under the proxy's per-address packet limit."""
    rtts = []
    lost = 0
    for _ in range(count):
        t0 = time.perf_counter()
        rtt = ping_once(sock)
        if rtt is None:
            lost += 1
        else:
            rtts.append(rtt)
        rest = PING_PACE_S - (time.perf_counter() - t0)
        if rest > 0:
            time.sleep(rest)
    return rtts, lost


def run_concurrent_pings(threads=8, per_thread=100):
    total_ok = [0]
    lock = threading.Lock()

    def worker():
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.bind(("127.0.0.1", 0))
        ok = 0
        for _ in range(per_thread):
            t1 = time.perf_counter()
            if ping_once(s) is not None:
                ok += 1
            rest = PING_PACE_S - (time.perf_counter() - t1)
            if rest > 0:
                time.sleep(rest)
        s.close()
        with lock:
            total_ok[0] += ok

    ts = [threading.Thread(target=worker) for _ in range(threads)]
    wall0 = time.perf_counter()
    for t in ts:
        t.start()
    for t in ts:
        t.join()
    wall = time.perf_counter() - wall0
    sent = threads * per_thread
    return {
        "sent": sent, "replies": total_ok[0],
        "replies_per_sec": total_ok[0] / wall,
        "loss_pct": (sent - total_ok[0]) / sent * 100.0,
    }


def run_java_client(mode, count, hold_seconds=8):
    """Run RakBenchClient (the real RakNet library) against the live proxy."""
    out = subprocess.run(
        [JAVA, "-cp", f"{CLIENT_DIR}:{CLIENT_CLASSPATH}", "RakBenchClient",
         mode, "127.0.0.1", str(PORT), str(count), str(hold_seconds)],
        capture_output=True, text=True, timeout=600,
    )
    result = {"mode": mode, "requested": count}
    for line in out.stdout.splitlines():
        if line.startswith("RESULT"):
            for part in line.replace("RESULT", "").split():
                key, _, value = part.partition("=")
                result[key] = int(value)
        elif line.startswith("LATENCIES_US="):
            raw = line.replace("LATENCIES_US=", "").rstrip(",")
            vals = [int(x) for x in raw.split(",") if x]
            result["connects_us"] = vals
    result["failures"] = out.stderr[-2000:] if out.returncode != 0 else ""
    return result


def summarize_client(client_result, hold_window_cpu=None):
    vals = client_result.get("connects_us", [])
    out = {
        "ok": client_result.get("ok", 0),
        "failed": client_result.get("failed", 0),
    }
    if vals:
        out["connect_ms_median"] = statistics.median(vals) / 1000
        out["connect_ms_p95"] = (statistics.quantiles(vals, n=20)[18] / 1000) if len(vals) > 20 else None
    if hold_window_cpu:
        out.update(hold_window_cpu)
    return out


def bench_jar(jar, name, runs=3):
    result = {"name": name, "jar": os.path.basename(jar), "jvm_flags": " ".join(JVM_FLAGS),
              "port": PORT, "startup": [], "idle_rss_kb": [], "idle_cpu_pct": []}

    for run_idx in range(runs):
        workdir = tempfile.mkdtemp(prefix="wb-bench-")
        pp = ProxyProcess(jar, workdir)
        try:
            pid = pp.start(os.path.join(workdir, "server.log"))
            startup = pp.wait_listening()
            if startup is None:
                shutil.copy(os.path.join(workdir, "server.log"), f"/tmp/{name}-crash-{run_idx}.log")
                raise RuntimeError(f"{name} failed to start (see /tmp/{name}-crash-{run_idx}.log)")
            result["startup"].append(startup)

            time.sleep(15)  # JIT warm-up / settle
            result["idle_rss_kb"].append(rss_kb(pid))
            c0, t0 = cpu_seconds(pid), time.perf_counter()
            time.sleep(20)
            cpu = (cpu_seconds(pid) - c0) / (time.perf_counter() - t0) * 100.0
            result["idle_cpu_pct"].append(cpu)
        finally:
            pp.stop()
            shutil.rmtree(workdir, ignore_errors=True)

    # Warm, long-lived process for traffic phases
    workdir = tempfile.mkdtemp(prefix="wb-bench-traffic")
    pp = ProxyProcess(jar, workdir)
    try:
        pid = pp.start(os.path.join(workdir, "server.log"))
        assert pp.wait_listening() is not None
        time.sleep(10)

        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.bind(("127.0.0.1", 0))
        rtts, lost = run_ping_rtt(s, 1500)
        s.close()
        result["ping_rtt_ms"] = {
            "count": len(rtts), "lost": lost,
            "median": statistics.median(rtts) * 1000,
            "p95": statistics.quantiles(rtts, n=20)[18] * 1000 if len(rtts) > 20 else None,
            "p99": statistics.quantiles(rtts, n=100)[98] * 1000 if len(rtts) > 100 else None,
        }

        result["concurrent_ping"] = run_concurrent_pings(threads=8, per_thread=100)

        # Session churn: 300 sequential real-library handshakes
        c0, t0 = cpu_seconds(pid), time.perf_counter()
        churn = run_java_client("handshake", 300)
        churn_wall = time.perf_counter() - t0
        result["handshake_churn"] = summarize_client(churn, {
            "wall_s": churn_wall,
            "cpu_pct": (cpu_seconds(pid) - c0) / churn_wall * 100.0,
            "connects_per_sec": churn.get("ok", 0) / churn_wall,
        })

        # 200 live sessions: RSS delta + CPU while held
        rss_before = rss_kb(pid)
        c0, t0 = cpu_seconds(pid), time.perf_counter()
        hold = run_java_client("hold", 200, hold_seconds=8)
        hold_wall = time.perf_counter() - t0
        result["live_sessions"] = summarize_client(hold, {
            "held": hold.get("ok", 0),
            "rss_delta_kb": rss_kb(pid) - rss_before,
            "kb_per_session": (rss_kb(pid) - rss_before) / max(1, hold.get("ok", 1)),
            "cpu_pct": (cpu_seconds(pid) - c0) / hold_wall * 100.0,
        })
    finally:
        pp.stop()
        shutil.rmtree(workdir, ignore_errors=True)

    return result


def summarize(results):
    out = {}
    for r in results:
        out[r["name"]] = {
            "startup_s_median": statistics.median(r["startup"]),
            "idle_rss_mb_median": statistics.median(r["idle_rss_kb"]) / 1024,
            "idle_cpu_pct_median": statistics.median(r["idle_cpu_pct"]),
            "ping_rtt_ms_median": r["ping_rtt_ms"]["median"],
            "ping_rtt_p95_ms": r["ping_rtt_ms"]["p95"],
            "ping_replies_per_sec": r["concurrent_ping"]["replies_per_sec"],
            "ping_loss_pct": r["concurrent_ping"]["loss_pct"],
            "handshake_ok": r["handshake_churn"]["ok"],
            "handshake_failed": r["handshake_churn"]["failed"],
            "handshake_connect_ms_median": r["handshake_churn"].get("connect_ms_median"),
            "handshake_connects_per_sec": r["handshake_churn"].get("connects_per_sec"),
            "handshake_cpu_pct": r["handshake_churn"].get("cpu_pct"),
            "sessions_held": r["live_sessions"]["held"],
            "kb_per_session": r["live_sessions"].get("kb_per_session"),
            "sessions_cpu_pct": r["live_sessions"].get("cpu_pct"),
        }
    return out


if __name__ == "__main__":
    if len(sys.argv) == 4:
        # single-target mode: bench.py <jar> <name> <out.json>
        jar, name, out_path = sys.argv[1], sys.argv[2], sys.argv[3]
        all_results = [bench_jar(jar, name)]
        with open(out_path, "w") as fh:
            json.dump({"results": all_results, "summary": summarize(all_results)}, fh, indent=2)
        print(json.dumps(summarize(all_results), indent=2))
        sys.exit(0)

    targets = [(sys.argv[1], sys.argv[2]), (sys.argv[3], sys.argv[4])]
    out_path = sys.argv[5]
    all_results = []
    for jar, name in targets:
        print(f"=== benchmarking {name} ({jar}) ===", flush=True)
        all_results.append(bench_jar(jar, name))
    with open(out_path, "w") as fh:
        json.dump({"results": all_results, "summary": summarize(all_results)}, fh, indent=2)
    print(json.dumps(summarize(all_results), indent=2))
