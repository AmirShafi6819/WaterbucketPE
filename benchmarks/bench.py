#!/usr/bin/env python3
"""
WaterbucketPE A/B benchmark harness.

Runs a WaterbucketPE or WaterdogPE jar through identical, measurable workloads and
reports raw numbers (JSON) so charts can be generated from real data:

  1. cold startup time (process start -> first successful RakNet unconnected ping reply)
  2. idle RSS / idle CPU after a warm-up settle
  3. unconnected ping RTT distribution (median / p95 / p99) and single-socket reply rate
  4. concurrent ping throughput (8 sockets x N pings)
  5. RakNet session handshake (OR1+OR2) success rate and rate, from unique source ports
  6. RSS / CPU under that connection load

Everything is measured from outside the JVM with UDP packets and /proc, so both
proxy variants run under identical conditions. Repeat runs are stored raw so the
chart can show medians honestly.
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
ID_REQ1, ID_REP1 = 0x05, 0x06
ID_REQ2, ID_REP2 = 0x07, 0x08
ID_INCOMPAT, ID_ALREADY = 0x19, 0x1A
PORT = 19132
JAVA = os.environ.get("BENCH_JAVA", "/home/z/jdk17/bin/java")
JVM_FLAGS = ["-Xms128M", "-Xmx512M", "-XX:+UseG1GC", "-XX:MaxGCPauseMillis=200"]
MTU = 1400
GUID = 0x1122334455667788


def flip(b):
    return bytes(x ^ 0xFF for x in b)


def ping_packet(now_ns, little=False):
    fmt = "<q" if little else ">q"
    return struct.pack(">B", ID_PING) + struct.pack(fmt, now_ns) + MAGIC + struct.pack(fmt, GUID)


def req1_packet():
    datagram_size = MTU - 48  # matches the server's MTU derivation
    body = struct.pack(">BB", ID_REQ1, 11) + MAGIC
    return body + b"\x00" * (datagram_size - len(body))


def req2_packet(port):
    addr = struct.pack(">B", 4) + flip(socket.inet_aton("127.0.0.1")) + struct.pack(">H", port)
    return struct.pack(">B", ID_REQ2) + MAGIC + addr + struct.pack(">H", MTU) + struct.pack(">q", GUID)


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


def ping_once(sock, little=False):
    """One unconnected ping; returns RTT seconds or None."""
    sock.settimeout(1.0)
    t0 = time.perf_counter()
    sock.sendto(ping_packet(int(t0 * 1e9), little), ("127.0.0.1", PORT))
    reply = recv_reply(sock, (ID_PONG,), timeout=1.0)
    if reply is None:
        return None
    return time.perf_counter() - t0


def detect_endianness():
    """The lib reads times big-endian; verify once so the harness matches the wire."""
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    s.bind(("127.0.0.1", 0))
    if ping_once(s, little=False) is not None:
        s.close()
        return False
    s.close()
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    s.bind(("127.0.0.1", 0))
    ok = ping_once(s, little=True) is not None
    s.close()
    return True if ok else None


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


def run_ping_rtt(sock, count=2000):
    rtts = []
    lost = 0
    for _ in range(count):
        rtt = ping_once(sock)
        if rtt is None:
            lost += 1
        else:
            rtts.append(rtt)
    return rtts, lost


def run_concurrent_pings(threads=8, per_thread=500):
    total_ok = [0]
    lock = threading.Lock()
    elapsed = [0.0]

    def worker():
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.bind(("127.0.0.1", 0))
        ok = 0
        t0 = time.perf_counter()
        for _ in range(per_thread):
            if ping_once(s) is not None:
                ok += 1
        dt = time.perf_counter() - t0
        s.close()
        with lock:
            total_ok[0] += ok
            elapsed[0] = max(elapsed[0], dt)

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


def run_session_handshakes(count=250):
    """Complete OR1+OR2 from unique source ports; each OR2 allocates a server-side session."""
    ok = 0
    rtts = []
    for i in range(count):
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.bind(("127.0.0.1", 0))
        try:
            t0 = time.perf_counter()
            s.sendto(req1_packet(), ("127.0.0.1", PORT))
            rep1 = recv_reply(s, (ID_REP1, ID_INCOMPAT), timeout=1.0)
            if rep1 is None or rep1[0] != ID_REP1:
                s.close()
                continue
            s.sendto(req2_packet(PORT), ("127.0.0.1", PORT))
            rep2 = recv_reply(s, (ID_REP2, ID_ALREADY), timeout=1.0)
            if rep2 is not None and rep2[0] == ID_REP2:
                ok += 1
                rtts.append(time.perf_counter() - t0)
        finally:
            s.close()
    return {"attempts": count, "sessions_opened": ok,
            "success_pct": ok / count * 100.0,
            "handshake_rtt_median_ms": statistics.median(rtts) * 1000 if rtts else None}


def bench_jar(jar, name, runs=5):
    result = {"name": name, "jar": os.path.basename(jar), "jvm_flags": " ".join(JVM_FLAGS),
              "port": PORT, "startup": [], "idle_rss_kb": [], "idle_cpu_pct": []}
    little = detect_endianness()
    result["ping_time_little_endian"] = bool(little)

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

            time.sleep(20)  # JIT warm-up / settle
            result["idle_rss_kb"].append(rss_kb(pid))
            c0, t0 = cpu_seconds(pid), time.perf_counter()
            time.sleep(30)
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
        rtts, lost = run_ping_rtt(s, 2000)
        s.close()
        result["ping_rtt_ms"] = {
            "count": len(rtts), "lost": lost,
            "median": statistics.median(rtts) * 1000,
            "p95": statistics.quantiles(rtts, n=20)[18] * 1000 if len(rtts) > 20 else None,
            "p99": statistics.quantiles(rtts, n=100)[98] * 1000 if len(rtts) > 100 else None,
        }

        result["concurrent_ping"] = run_concurrent_pings()

        rss_before = rss_kb(pid)
        result["session_handshake"] = run_session_handshakes(250)
        time.sleep(1)
        result["rss_after_load_kb"] = rss_kb(pid) - rss_before

        c0, t0 = cpu_seconds(pid), time.perf_counter()
        load = run_concurrent_pings(threads=4, per_thread=250)
        hs = run_session_handshakes(100)
        wall = time.perf_counter() - t0
        result["mixed_load"] = {
            "wall_s": wall, "cpu_pct": (cpu_seconds(pid) - c0) / wall * 100.0,
            "ping_replies": load["replies"],
            "sessions_opened": hs["sessions_opened"],
            "ops_per_sec": (load["replies"] + hs["sessions_opened"]) / wall,
        }
        result["rss_after_mixed_kb"] = rss_kb(pid)
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
            "handshake_success_pct": r["session_handshake"]["success_pct"],
            "handshake_rtt_ms": r["session_handshake"]["handshake_rtt_median_ms"],
            "mixed_cpu_pct": r["mixed_load"]["cpu_pct"],
            "mixed_ops_per_sec": r["mixed_load"]["ops_per_sec"],
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
