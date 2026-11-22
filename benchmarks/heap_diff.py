#!/usr/bin/env python3
"""
Heap-evidence helper for the per-session memory investigation.

Starts a proxy jar with the benchmark JVM flags plus -XX:+PrintClassHistogram, holds N
real RakNet sessions via RakBenchClient, then sends SIGQUIT so the JVM prints a class
histogram to stdout. Prints the top classes sorted by retained instance count delta
against a no-session baseline run of the same jar.

Usage: python3 scripts/heap_diff.py <jar> <name> <sessions>
"""
import json
import os
import re
import signal
import subprocess
import sys
import tempfile
import time

JAVA = "/home/z/jdk17/bin/java"
JVM_FLAGS = ["-Xms128M", "-Xmx512M", "-XX:+UseG1GC", "-XX:MaxGCPauseMillis=200"]
CONFIG = "network_settings:\n  connection_throttle: -1\n"
BENCH_DIR = "/home/z/my-project/WaterbucketPE"
CLIENT_DIR = "/tmp/wbtest7"


def start_proxy(jar, workdir, log_path):
    with open(os.path.join(workdir, "config.yml"), "w") as fh:
        fh.write(CONFIG)
    env = dict(os.environ)
    env["JAVA_HOME"] = "/home/z/jdk17"
    log = open(log_path, "wb")
    proc = subprocess.Popen(
        [JAVA, *JVM_FLAGS, "-XX:+PrintClassHistogram", "-jar", jar],
        cwd=workdir, stdout=log, stderr=subprocess.STDOUT, env=env, preexec_fn=os.setsid,
    )
    return proc


def wait_listening(proc, deadline=120.0):
    import socket, struct
    magic = bytes.fromhex("00ffff00fefefefefdfdfdfd12345678")
    pkt = struct.pack(">Bq", 0x01, time.perf_counter_ns()) + magic + struct.pack(">q", 0x1122334455667788)
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    s.bind(("127.0.0.1", 0))
    t0 = time.perf_counter()
    try:
        while time.perf_counter() - t0 < deadline:
            if proc.poll() is not None:
                return False
            s.sendto(pkt, ("127.0.0.1", 19132))
            s.settimeout(1.0)
            try:
                s.recvfrom(4096)
                return True
            except socket.timeout:
                time.sleep(0.05)
        return False
    finally:
        s.close()


def run_client(mode, sessions, hold_seconds=8):
    cp = f"{CLIENT_DIR}:{BENCH_DIR}/build/libs/Waterbucket.jar"
    cmd = [JAVA, "-cp", cp, "RakBenchClient", mode, "127.0.0.1", "19132", str(sessions), str(hold_seconds)]
    return subprocess.run(cmd, cwd=BENCH_DIR, capture_output=True, text=True, timeout=600)


def histogram(proc, log_path):
    os.killpg(proc.pid, signal.SIGQUIT)
    time.sleep(3.0)
    with open(log_path, "rb") as fh:
        out = fh.read().decode("utf-8", "replace")
    classes = {}
    for line in out.splitlines():
        m = re.match(r"\s*\d+:\s+(\d+)\s+(\d+)\s+(\S+)", line)
        if m:
            classes[m.group(3)] = int(m.group(1))
    return classes


def assert_ports_free():
    import socket as _s
    for kind, port in ((_s.SOCK_DGRAM, 19132), (_s.SOCK_STREAM, 8080)):
        try:
            t = _s.socket(_s.AF_INET, kind)
            t.bind(("0.0.0.0", port))
            t.close()
        except OSError as e:
            raise RuntimeError(f"port {port} is already in use ({e}); kill stale proxies first")


def main():
    jar, name, sessions = sys.argv[1], sys.argv[2], int(sys.argv[3])
    assert_ports_free()

    results = {}
    for label, hold in (("baseline", 0), ("held", sessions)):
        print(f"[heap_diff] phase {label} starting", flush=True)
        with tempfile.TemporaryDirectory() as workdir:
            log_path = os.path.join(workdir, "proxy.log")
            proc = start_proxy(jar, workdir, log_path)
            try:
                if not wait_listening(proc):
                    with open(log_path, "rb") as lfh:
                        tail = lfh.read().decode("utf-8", "replace")[-2000:]
                    raise RuntimeError(f"{name} failed to start ({label}); log tail:\n{tail}")
                time.sleep(3.0)  # settle
                if hold:
                    print(f"[heap_diff] holding {hold} sessions", flush=True)
                    client = run_client("hold", hold, hold_seconds=8)
                    print(f"[heap_diff] hold client rc={client.returncode} out={client.stdout[-200:]!r} err={client.stderr[-300:]!r}", flush=True)
                    time.sleep(1.0)
                print(f"[heap_diff] capturing histogram", flush=True)
                results[label] = histogram(proc, log_path)
                print(f"[heap_diff] histogram captured ({len(results[label])} classes)", flush=True)
            finally:
                try:
                    os.killpg(proc.pid, signal.SIGTERM)
                    proc.wait(timeout=15)
                except Exception:
                    try:
                        os.killpg(proc.pid, signal.SIGKILL)
                    except ProcessLookupError:
                        pass

    delta = []
    for cls, count in results["held"].items():
        base = results["baseline"].get(cls, 0)
        d = count - base
        if d > 0:
            delta.append((d, cls, count, base))
    delta.sort(reverse=True)
    print(f"=== {name}: top per-session classes ({sessions} held sessions) ===")
    for d, cls, count, base in delta[:25]:
        print(f"{d:>8}  {cls}  (held={count}, base={base})")


if __name__ == "__main__":
    main()
