#!/usr/bin/env python3
"""
Bedrock-protocol A/B benchmark: login latency, in-game RTT and fast-transfer latency.

Extends the RakNet-level harness (bench.py) with the scenarios that need full Bedrock
sessions, so the transfer-path improvements can be measured end-to-end instead of argued
from design:

  1. login  - sequential full logins (RakNet connect -> encryption -> resource packs ->
     StartGame), measuring connect->StartGame latency per client
  2. rtt    - one login, then sequential TextPacket round-trips through proxy and backend
  3. switch - one login, then alternating fast transfers A->B->A..., measuring the time
     from the backend's TransferPacket to the client's next StartGamePacket

The fake backends and the clients live in BedrockBench.java (compiled against the proxy
jar itself, like RakBenchClient), so both sides of the A/B run identical byte-for-byte
traffic. Everything else (proxy lifecycle, RSS) matches bench.py conventions.
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
import time

MAGIC = bytes.fromhex("00ffff00fefefefefdfdfdfd12345678")
ID_PING, ID_PONG = 0x01, 0x1C
GUID = 0x1122334455667788
JAVA = os.environ.get("BENCH_JAVA", "/home/z/my-project/jdks/jdk-17.0.21.1+2/bin/java")
JVM_FLAGS = ["-Xms128M", "-Xmx512M", "-XX:+UseG1GC", "-XX:MaxGCPauseMillis=200"]
CLIENT_JAR = os.environ.get("BENCH_CLIENT_JAR", "")
CLIENT_CLASSES = os.environ.get("BENCH_CLIENT_CLASSES", "/tmp/wbtest8")
PROXY_PORT = int(os.environ.get("BENCH_PROXY_PORT", "19132"))
BACKEND_A_PORT = int(os.environ.get("BENCH_BACKEND_A_PORT", "19133"))
BACKEND_B_PORT = int(os.environ.get("BENCH_BACKEND_B_PORT", "19134"))

# scenario counts: modest on purpose - the shared 2-core VM makes long runs noisy anyway
LOGIN_COUNT = int(os.environ.get("BENCH_LOGIN_COUNT", "30"))
RTT_COUNT = int(os.environ.get("BENCH_RTT_COUNT", "300"))
SWITCH_COUNT = int(os.environ.get("BENCH_SWITCH_COUNT", "30"))

BENCH_CONFIG = """online_mode: false
listener:
  priorities: [backend-a]
network_settings:
  connection_throttle: -1
  login_throttle: -1
servers:
  backend-a:
    address: "127.0.0.1:{port_a}"
    server_type: raknet
  backend-b:
    address: "127.0.0.1:{port_b}"
    server_type: raknet
"""


def ping_packet(now_ns):
    return struct.pack(">Bq", ID_PING, now_ns) + MAGIC + struct.pack(">q", GUID)


def ping_once(sock, port=PROXY_PORT):
    sock.settimeout(0.5)
    t0 = time.perf_counter()
    sock.sendto(ping_packet(int(t0 * 1e9)), ("127.0.0.1", port))
    end = time.perf_counter() + 0.5
    while time.perf_counter() < end:
        try:
            data, _ = sock.recvfrom(65535)
        except socket.timeout:
            return None
        if data and data[0] == ID_PONG:
            return time.perf_counter() - t0
    return None


def rss_kb(pid):
    try:
        with open(f"/proc/{pid}/status") as fh:
            for line in fh:
                if line.startswith("VmRSS:"):
                    return int(line.split()[1])
    except FileNotFoundError:
        pass
    return 0


def free_port(preferred):
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.bind(("127.0.0.1", preferred))
        return preferred
    except OSError:
        s.close()
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.bind(("127.0.0.1", 0))
        port = s.getsockname()[1]
        s.close()
        return port


class ProxyProcess:
    def __init__(self, jar, workdir):
        self.jar = jar
        self.workdir = workdir
        self.proc = None

    def start(self, log_path):
        config = BENCH_CONFIG.format(
            proxy_port=PROXY_PORT, port_a=BACKEND_A_PORT, port_b=BACKEND_B_PORT
        )
        with open(os.path.join(self.workdir, "config.yml"), "w") as fh:
            fh.write(config)
        env = dict(os.environ)
        env["JAVA_HOME"] = os.path.dirname(os.path.dirname(JAVA))
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
                    return None
                if ping_once(s) is not None:
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


def run_bedrock_client(mode, count):
    """Run BedrockBench (real Bedrock protocol) against the live proxy."""
    out = subprocess.run(
        [JAVA, "-cp", f"{CLIENT_CLASSES}:{CLIENT_JAR}", "BedrockBench",
         mode, "127.0.0.1", str(PROXY_PORT), str(count),
         str(BACKEND_A_PORT), str(BACKEND_B_PORT)],
        capture_output=True, text=True, timeout=900,
    )
    result = {"mode": mode, "requested": count, "stderr_tail": out.stderr[-3000:]}
    for line in out.stdout.splitlines():
        if line.startswith("RESULT"):
            for part in line.replace("RESULT", "").split():
                key, _, value = part.partition("=")
                result[key] = int(value)
        elif line.startswith("LATENCIES_US="):
            raw = line.replace("LATENCIES_US=", "").rstrip(",")
            result["latencies_us"] = [int(x) for x in raw.split(",") if x]
    if out.returncode != 0:
        result["failed"] = result.get("failed", count)
    return result


def summarize(name, run):
    vals = run.get("latencies_us", [])
    out = {
        "ok": run.get("ok", 0),
        "failed": run.get("failed", 0),
    }
    if vals:
        out["median_ms"] = statistics.median(vals) / 1000
        out["p95_ms"] = (statistics.quantiles(vals, n=20)[18] / 1000) if len(vals) >= 20 else None
        out["p99_ms"] = (statistics.quantiles(vals, n=100)[98] / 1000) if len(vals) >= 100 else None
    return out


def bench_jar(jar, name):
    result = {
        "name": name,
        "jar": os.path.basename(jar),
        "jvm_flags": " ".join(JVM_FLAGS),
        "proxy_port": PROXY_PORT,
        "backend_a_port": BACKEND_A_PORT,
        "backend_b_port": BACKEND_B_PORT,
    }
    workdir = tempfile.mkdtemp(prefix="wb-bench-bedrock-")
    pp = ProxyProcess(jar, workdir)
    try:
        pid = pp.start(os.path.join(workdir, "server.log"))
        startup = pp.wait_listening()
        if startup is None:
            shutil.copy(os.path.join(workdir, "server.log"), f"/tmp/{name}-crash.log")
            raise RuntimeError(f"{name} failed to start (see /tmp/{name}-crash.log)")
        result["startup_s"] = startup
        time.sleep(10)  # JIT warm-up / settle

        rss_before = rss_kb(pid)
        login = run_bedrock_client("login", LOGIN_COUNT)
        result["login"] = summarize("login", login)
        result["login_rss_delta_kb"] = rss_kb(pid) - rss_before

        rtt = run_bedrock_client("rtt", RTT_COUNT)
        result["rtt"] = summarize("rtt", rtt)

        c0 = rss_kb(pid)
        switch = run_bedrock_client("switch", SWITCH_COUNT)
        result["switch"] = summarize("switch", switch)
        result["switch_rss_delta_kb"] = rss_kb(pid) - c0
    finally:
        pp.stop()
        shutil.rmtree(workdir, ignore_errors=True)
    return result


if __name__ == "__main__":
    if len(sys.argv) < 3:
        print(__doc__)
        print("usage: bench_bedrock.py <proxy.jar> <out.json> [<mode> ...]")
        sys.exit(1)
    jar, out_path = sys.argv[1], sys.argv[2]
    modes = sys.argv[3:] if len(sys.argv) > 3 else ["login", "rtt", "switch"]

    full = bench_jar(jar, "proxy")
    result = {"environment": {
        "java": JAVA,
        "jvm_flags": " ".join(JVM_FLAGS),
        "date": time.strftime("%Y-%m-%d"),
        "protocol": 560,
        "scenario_counts": {"login": LOGIN_COUNT, "rtt": RTT_COUNT, "switch": SWITCH_COUNT},
    }, "results": [full]}
    with open(out_path, "w") as fh:
        json.dump(result, fh, indent=2)
    print(json.dumps(full, indent=2))
