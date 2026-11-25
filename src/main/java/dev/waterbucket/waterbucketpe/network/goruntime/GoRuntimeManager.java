/*
 * Copyright 2026 WaterbucketTEAM
 * Licensed under the GNU General Public License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.gnu.org/licenses/old-licenses/gpl-2.0.html
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.waterbucket.waterbucketpe.network.goruntime;

import dev.waterbucket.waterbucketpe.ProxyServer;
import dev.waterbucket.waterbucketpe.player.ProxiedPlayer;
import dev.waterbucket.waterbucketpe.utils.config.proxy.WorldViewSettings;
import lombok.extern.log4j.Log4j2;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.io.OutputStream;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Owns the Go runtime sidecar lifecycle and the bridge socket: extracts the
 * embedded binary (or takes one from the data folder), spawns it, shakes
 * hands, streams world deltas and events, answers its RPC calls and restarts
 * it when it dies. Every blocking detail stays off the Netty event loops.
 */
@Log4j2
public final class GoRuntimeManager {

    private static final int MAX_QUEUE = 16384;
    private static final int MAX_RPC_PENDING = 256;

    private final ProxyServer proxy;
    private final WorldViewSettings settings;
    private final ProxyApiFacade facade;

    private ServerSocketChannel listener;
    private Path socketPath;
    private int tcpPort = -1;
    private volatile SocketChannel channel;
    private final ReentrantWriteBarrier writeBarrier = new ReentrantWriteBarrier();

    private Process process;
    private Thread readerThread;
    private Thread writerThread;
    private Thread watchdogThread;
    private final LinkedBlockingQueue<byte[]> outbound = new LinkedBlockingQueue<>(MAX_QUEUE);
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean connected = new AtomicBoolean(false);
    private final AtomicBoolean goPluginsPresent = new AtomicBoolean(false);
    private final AtomicLong droppedDeltas = new AtomicLong();
    private final AtomicLong rpcCounter = new AtomicLong();
    private final AtomicLong queryCounter = new AtomicLong();
    private final Map<Long, CompletableFuture<BridgeCodec.Reader>> rpcPending = new ConcurrentHashMap<>();
    private final Map<Long, CompletableFuture<BridgeCodec.Reader>> queryPending = new ConcurrentHashMap<>();
    private volatile BridgeCodec.HelloAck helloAck;
    private volatile List<BridgeCodec.PluginStatus> pluginStatuses = List.of();
    private int restartBudget = 5;
    private long lastRestart = 0;

    public GoRuntimeManager(ProxyServer proxy, WorldViewSettings settings) {
        this.proxy = proxy;
        this.settings = settings;
        this.facade = new ProxyApiFacade(proxy);
    }

    public boolean isConnected() {
        return this.connected.get();
    }

    public boolean hasGoPlugins() {
        return this.goPluginsPresent.get();
    }

    public BridgeCodec.HelloAck helloAck() {
        return this.helloAck;
    }

    public List<BridgeCodec.PluginStatus> pluginStatuses() {
        return this.pluginStatuses;
    }

    public long droppedDeltas() {
        return this.droppedDeltas.get();
    }

    /**
     * Starts the sidecar: extract, listen, spawn, handshake. Failures degrade
     * to "world queries unavailable" without touching proxy networking.
     */
    public synchronized void start() {
        if (this.running.get()) {
            return;
        }
        if (!this.settings.enable() || !this.settings.goRuntime()) {
            log.info("[GoRuntime] disabled by configuration; world queries answer unloaded and only proxy-native events fire");
            return;
        }
        this.running.set(true);
        try {
            Path binary = extractBinary();
            listen();
            spawn(binary);
            this.writerThread = new Thread(this::writerLoop, "GoRuntime-Writer");
            this.writerThread.setDaemon(true);
            this.writerThread.start();
            this.watchdogThread = new Thread(this::watchdogLoop, "GoRuntime-Watchdog");
            this.watchdogThread.setDaemon(true);
            this.watchdogThread.start();
            log.info("[GoRuntime] sidecar started ({}); world view active", this.socketPath != null ? this.socketPath.getFileName() : "tcp:" + this.tcpPort);
        } catch (Throwable t) {
            this.running.set(false);
            log.warn("[GoRuntime] could not start: {}. World queries answer unloaded; the proxy itself is unaffected.", t.getMessage());
        }
    }

    /**
     * Stops the sidecar cleanly.
     */
    public synchronized void shutdown() {
        this.running.set(false);
        this.connected.set(false);
        try {
            if (this.channel != null && this.channel.isOpen()) {
                BridgeCodec.Writer w = new BridgeCodec.Writer(4);
                w.u8(BridgeCodec.OP_SHUTDOWN);
                this.writeBarrier.write(w.toArray());
            }
        } catch (IOException ignored) {
        }
        if (this.process != null && this.process.isAlive()) {
            this.process.destroyForcibly();
        }
        if (this.listener != null && this.listener.isOpen()) {
            try {
                this.listener.close();
            } catch (IOException ignored) {
            }
        }
        if (this.socketPath != null) {
            try {
                Files.deleteIfExists(this.socketPath);
            } catch (IOException ignored) {
            }
        }
        log.info("[GoRuntime] sidecar stopped");
    }

    /**
     * Extracts the platform binary from the jar resources; falls back to an
     * operator-provided binary in the data folder.
     */
    private Path extractBinary() throws IOException {
        String os = System.getProperty("os.name").toLowerCase();
        String arch = System.getProperty("os.arch").toLowerCase();
        String platform;
        if (os.contains("win")) {
            platform = "windows-amd64.exe";
        } else if (os.contains("aarch64") || arch.contains("aarch64")) {
            platform = "linux-arm64";
        } else if (os.contains("mac")) {
            platform = "linux-arm64"; // no darwin build yet: arm64 linux binary works under Rosetta-less setups only; mac users run linux builds in containers
        } else {
            platform = "linux-amd64";
        }
        Path runtimeDir = this.proxy.getDataPath().resolve("go-runtime");
        Files.createDirectories(runtimeDir);
        Path target = runtimeDir.resolve("waterbucket-go-runtime-" + platform);
        if (!Files.exists(target)) {
            String resource = "/runtime/go/waterbucket-go-runtime-" + platform;
            try (InputStream in = GoRuntimeManager.class.getResourceAsStream(resource)) {
                if (in == null) {
                    // No embedded binary: maybe the operator dropped one next to the jar.
                    if (Files.exists(target)) {
                        return target;
                    }
                    throw new IOException("no Go runtime binary available (missing resource " + resource + ")");
                }
                Files.copy(in, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            if (!os.contains("win")) {
                try {
                    java.util.Set<java.nio.file.attribute.PosixFilePermission> perms =
                            java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-xr-x");
                    Files.setPosixFilePermissions(target, perms);
                } catch (UnsupportedOperationException ignored) {
                }
            }
        }
        return target;
    }

    /**
     * Listens on a Unix domain socket (fast, no port juggling) when the
     * platform supports it, else on loopback TCP with an ephemeral port.
     */
    private void listen() throws IOException {
        try {
            this.socketPath = this.proxy.getDataPath().resolve("go-runtime.sock");
            Files.deleteIfExists(this.socketPath);
            this.listener = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
            this.listener.bind(UnixDomainSocketAddress.of(this.socketPath));
        } catch (IOException | UnsupportedOperationException unixFailure) {
            this.socketPath = null;
            this.listener = ServerSocketChannel.open();
            this.listener.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
            this.tcpPort = ((InetSocketAddress) this.listener.getLocalAddress()).getPort();
        }
        Thread acceptor = new Thread(this::acceptLoop, "GoRuntime-Acceptor");
        acceptor.setDaemon(true);
        acceptor.start();
    }

    private void acceptLoop() {
        while (this.running.get()) {
            try {
                SocketChannel accepted = this.listener.accept();
                if (accepted != null) {
                    onSidecarConnected(accepted);
                }
            } catch (IOException e) {
                if (this.running.get()) {
                    log.warn("[GoRuntime] accept failed: {}", e.getMessage());
                }
                return;
            }
        }
    }

    private void spawn(Path binary) throws IOException {
        String bridgeArg = this.socketPath != null
                ? "-bridge=unix:" + this.socketPath
                : "-bridge=tcp:127.0.0.1:" + this.tcpPort;
        Path pluginsDir = this.proxy.getDataPath().resolve(this.settings.goPluginsDir());
        Files.createDirectories(pluginsDir);
        ProcessBuilder builder = new ProcessBuilder(
                binary.toAbsolutePath().toString(),
                bridgeArg,
                "-plugins=" + pluginsDir.toAbsolutePath());
        builder.environment().put("WATERBUCKET_HOME", this.proxy.getDataPath().toString());
        builder.redirectErrorStream(true);
        this.process = builder.start();
        Thread io = new Thread(() -> {
            try (var reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(this.process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.contains("[ERROR]")) {
                        log.warn("[GoRuntime/sidecar] {}", line);
                    } else {
                        log.info("[GoRuntime/sidecar] {}", line);
                    }
                }
            } catch (IOException ignored) {
            }
        }, "GoRuntime-Stdio");
        io.setDaemon(true);
        io.start();
    }

    private void onSidecarConnected(SocketChannel accepted) {
        SocketChannel old = this.channel;
        if (old != null && old.isOpen()) {
            try {
                old.close();
            } catch (IOException ignored) {
            }
        }
        this.channel = accepted;
        try {
            accepted.setOption(java.net.StandardSocketOptions.TCP_NODELAY, true);
        } catch (IOException | UnsupportedOperationException ignored) {
            // Unix domain sockets have no TCP options; loopback latency is tiny anyway.
        }
        // Attach the write barrier BEFORE the handshake: the HELLO itself
        // travels through it.
        this.writeBarrier.attach(accepted);
        try {
            // Handshake: HELLO first, then the sidecar's ack tells us who it is.
            BridgeCodec.Writer w = new BridgeCodec.Writer(128);
            w.hello(dev.waterbucket.waterbucketpe.WaterbucketPE.version().baseVersion(),
                    this.settings.enable(), this.settings.retentionSeconds(),
                    this.settings.maxChunksPerWorld(), this.settings.eventSyncTimeoutMs());
            this.writeBarrier.write(w.toArray());
            InputStream in = new BufferedInputStream(Channels_input(accepted), 256 * 1024);
            byte[] frame = BridgeCodec.readFrame(in);
            BridgeCodec.Reader reader = new BridgeCodec.Reader(frame);
            int op = reader.u8();
            if (op != BridgeCodec.OP_HELLO_ACK) {
                throw new IOException("expected HELLO_ACK, got opcode " + op);
            }
            BridgeCodec.HelloAck ack = reader.helloAck();
            this.helloAck = ack;
            this.connected.set(true);
            log.info("[GoRuntime] sidecar connected: {} (plugin api {}); plugins: {}",
                    ack.goVersion(), ack.apiVersion(), ack.plugins().isEmpty() ? "none" : String.join(", ", ack.plugins()));
            this.readerThread = new Thread(() -> readerLoop(in, accepted), "GoRuntime-Reader");
            this.readerThread.setDaemon(true);
            this.readerThread.start();
        } catch (IOException e) {
            log.warn("[GoRuntime] handshake failed: {}", e.getMessage());
            try {
                accepted.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static InputStream Channels_input(SocketChannel channel) {
        return new SocketInputStream(channel);
    }

    /**
     * Blocking InputStream over a SocketChannel (channel streams are fine but
     * need a buffer; this keeps readFrame simple).
     */
    /**
     * Blocking OutputStream over a SocketChannel for the write side.
     */
    private static final class SocketOutputStream extends java.io.OutputStream {

        private final SocketChannel channel;
        private final byte[] single = new byte[1];

        private SocketOutputStream(SocketChannel channel) {
            this.channel = channel;
        }

        @Override
        public void write(int b) throws IOException {
            this.single[0] = (byte) b;
            this.write(this.single, 0, 1);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            java.nio.ByteBuffer buffer = java.nio.ByteBuffer.wrap(b, off, len);
            while (buffer.hasRemaining()) {
                int n = this.channel.write(buffer);
                if (n < 0) {
                    throw new IOException("bridge socket closed");
                }
            }
        }
    }

    private static final class SocketInputStream extends InputStream {

        private final SocketChannel channel;
        private final byte[] single = new byte[1];

        private SocketInputStream(SocketChannel channel) {
            this.channel = channel;
        }

        @Override
        public int read() throws IOException {
            int n = this.channel.read(java.nio.ByteBuffer.wrap(this.single));
            return n < 0 ? -1 : (this.single[0] & 0xFF);
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            return this.channel.read(java.nio.ByteBuffer.wrap(b, off, len));
        }
    }

    private void readerLoop(InputStream in, SocketChannel channel) {
        try {
            while (this.running.get() && channel.isOpen()) {
                byte[] frame = BridgeCodec.readFrame(in);
                handleFrame(frame);
            }
        } catch (IOException e) {
            if (this.running.get()) {
                log.warn("[GoRuntime] connection lost: {}", e.getMessage());
            }
        }
        this.writeBarrier.detach();
        this.connected.set(false);
        this.goPluginsPresent.set(false);
        failPending(this.rpcPending);
        failPending(this.queryPending);
    }

    private void failPending(Map<Long, CompletableFuture<BridgeCodec.Reader>> pending) {
        for (Map.Entry<Long, CompletableFuture<BridgeCodec.Reader>> entry : pending.entrySet()) {
            entry.getValue().completeExceptionally(new IOException("bridge closed"));
        }
        pending.clear();
    }

    private void handleFrame(byte[] frame) {
        try {
            BridgeCodec.Reader r = new BridgeCodec.Reader(frame);
            int op = r.u8();
            switch (op) {
                case BridgeCodec.OP_PING -> {
                    BridgeCodec.Writer w = new BridgeCodec.Writer(2);
                    w.u8(BridgeCodec.OP_PONG);
                    this.writeBarrier.write(w.toArray());
                }
                case BridgeCodec.OP_PONG -> {
                }
                case BridgeCodec.OP_EVENT_RESULT -> {
                    long callId = r.i32();
                    CompletableFuture<BridgeCodec.Reader> pending = this.rpcPending.remove(callId);
                    if (pending != null) {
                        pending.complete(r);
                    }
                }
                case BridgeCodec.OP_QUERY_RESULT -> {
                    long callId = r.i32();
                    CompletableFuture<BridgeCodec.Reader> pending = this.queryPending.remove(callId);
                    if (pending != null) {
                        pending.complete(r);
                    }
                }
                case BridgeCodec.OP_RPC_CALL -> handleRpcCall(r);
                case BridgeCodec.OP_LOG -> {
                    int level = r.u8();
                    String msg = r.string();
                    switch (level) {
                        case 0 -> log.debug("[GoRuntime] {}", msg);
                        case 2 -> log.warn("[GoRuntime] {}", msg);
                        case 3 -> log.error("[GoRuntime] {}", msg);
                        default -> log.info("[GoRuntime] {}", msg);
                    }
                }
                case BridgeCodec.OP_PLUGIN_STATUS -> {
                    this.pluginStatuses = r.pluginStatus();
                    boolean enabled = false;
                    for (BridgeCodec.PluginStatus status : this.pluginStatuses) {
                        if (status.state() == BridgeCodec.PLUGIN_ENABLED) {
                            enabled = true;
                        }
                        switch (status.state()) {
                            case BridgeCodec.PLUGIN_ENABLED -> log.info("[GoRuntime] plugin {} {} (api {}) enabled",
                                    status.name(), status.version(), status.apiVersion());
                            case BridgeCodec.PLUGIN_FAILED -> log.warn("[GoRuntime] plugin {} failed: {}",
                                    status.name(), status.error());
                            default -> log.info("[GoRuntime] plugin {} disabled", status.name());
                        }
                    }
                    this.goPluginsPresent.set(enabled);
                }
                case BridgeCodec.OP_SHUTDOWN -> {
                    log.info("[GoRuntime] sidecar requested shutdown");
                    this.connected.set(false);
                }
                default -> {
                }
            }
        } catch (IOException e) {
            log.warn("[GoRuntime] bad frame from sidecar: {}", e.getMessage());
        }
    }

    private void handleRpcCall(BridgeCodec.Reader r) throws IOException {
        long callId = r.i32();
        String obj = r.string();
        String method = r.string();
        List<BridgeCodec.Field> args = r.remainingFields();
        RpcResult result = this.facade.dispatch(obj, method, args);
        BridgeCodec.Writer w = new BridgeCodec.Writer(256);
        w.rpcResult(callId, result.ok(), result.fields(), result.error());
        try {
            this.writeBarrier.write(w.toArray());
        } catch (IOException e) {
            log.warn("[GoRuntime] rpc result write failed: {}", e.getMessage());
        }
    }

    public record RpcResult(boolean ok, List<BridgeCodec.Field> fields, String error) {

        public static RpcResult ok(List<BridgeCodec.Field> fields) {
            return new RpcResult(true, fields, null);
        }

        public static RpcResult fail(String error) {
            return new RpcResult(false, List.of(), error);
        }
    }

    private void writerLoop() {
        while (this.running.get()) {
            try {
                byte[] frame = this.outbound.poll(500, TimeUnit.MILLISECONDS);
                if (frame == null) {
                    continue;
                }
                // Drain the whole batch before flushing: one syscall sweep.
                do {
                    this.writeBarrier.write(frame);
                    frame = this.outbound.poll();
                } while (frame != null && this.running.get());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (IOException e) {
                if (this.running.get()) {
                    log.warn("[GoRuntime] writer stopped: {}", e.getMessage());
                }
                this.connected.set(false);
            }
        }
    }

    private void watchdogLoop() {
        while (this.running.get()) {
            try {
                Thread.sleep(5000);
            } catch (InterruptedException e) {
                return;
            }
            if (!this.settings.enable() || !this.settings.goRuntime()) {
                continue;
            }
            boolean processDead = this.process == null || !this.process.isAlive();
            boolean socketDead = !this.connected.get();
            long now = System.currentTimeMillis();
            if (processDead && now - this.lastRestart > 15000) {
                if (this.restartBudget <= 0) {
                    log.warn("[GoRuntime] sidecar keeps dying; giving up for now (world queries unavailable)");
                    this.lastRestart = now;
                    continue;
                }
                this.restartBudget--;
                this.lastRestart = now;
                log.info("[GoRuntime] restarting sidecar ({} attempts left)", this.restartBudget);
                try {
                    Path binary = extractBinary();
                    spawn(binary);
                } catch (IOException e) {
                    log.warn("[GoRuntime] restart failed: {}", e.getMessage());
                }
            } else if (socketDead && processDead) {
                // both sides gone: nothing to do until the restart above lands
            }
        }
    }

    /**
     * Queues one frame for asynchronous delivery (deltas, async events).
     * Never blocks the caller: when the queue is full the frame is dropped
     * and counted, so a slow sidecar can never stall a Netty event loop.
     */
    public void sendAsync(byte[] frame) {
        if (!this.connected.get()) {
            return;
        }
        if (!this.outbound.offer(frame)) {
            this.droppedDeltas.incrementAndGet();
        }
    }

    /**
     * Sends a cancellable event and waits (bounded) for the verdict. Called
     * on the player's event loop; the timeout keeps the worst case small.
     */
    public boolean isEventCancelled(int eventId, List<BridgeCodec.Field> fields, long timeoutMs) {
        if (!this.connected.get() || !this.hasGoPlugins()) {
            return false;
        }
        long callId = this.rpcCounter.incrementAndGet();
        CompletableFuture<BridgeCodec.Reader> future = new CompletableFuture<>();
        this.rpcPending.put(callId, future);
        if (this.rpcPending.size() > MAX_RPC_PENDING) {
            // Overflow safety: drop the oldest expectations.
            List<Long> stale = new ArrayList<>();
            this.rpcPending.forEach((id, f) -> {
                if (stale.size() < 16) {
                    stale.add(id);
                }
            });
            for (Long id : stale) {
                CompletableFuture<BridgeCodec.Reader> removed = this.rpcPending.remove(id);
                if (removed != null) {
                    removed.complete(new BridgeCodec.Reader(new byte[]{BridgeCodec.QUERY_OK, 0}));
                }
            }
        }
        try {
            BridgeCodec.Writer w = new BridgeCodec.Writer(fields.size() * 32 + 32);
            w.eventSync(callId, eventId, fields);
            this.writeBarrier.write(w.toArray());
            BridgeCodec.Reader result = future.get(Math.max(1, timeoutMs), TimeUnit.MILLISECONDS);
            // The verdict frame is just [cancelled bool] after the call id.
            return result.bool();
        } catch (Exception e) {
            return false; // fail-open: a slow sidecar never blocks gameplay
        } finally {
            this.rpcPending.remove(callId);
        }
    }

    /**
     * Sends a world query and waits (bounded) for the answer. Returns null
     * when the runtime is unavailable, so callers can degrade gracefully.
     */
    public BridgeCodec.Reader query(byte queryType, List<BridgeCodec.Field> fields, long timeoutMs) throws IOException {
        if (!this.connected.get()) {
            throw new IOException("Go runtime is not connected");
        }
        long callId = this.queryCounter.incrementAndGet();
        CompletableFuture<BridgeCodec.Reader> future = new CompletableFuture<>();
        this.queryPending.put(callId, future);
        try {
            BridgeCodec.Writer w = new BridgeCodec.Writer(64 + fields.size() * 16);
            w.query(callId, queryType, fields);
            this.writeBarrier.write(w.toArray());
            BridgeCodec.Reader result = future.get(timeoutMs, TimeUnit.MILLISECONDS);
            int status = result.u8();
            if (status != BridgeCodec.QUERY_OK) {
                throw new QueryException(status);
            }
            return result;
        } catch (java.util.concurrent.TimeoutException | InterruptedException | java.util.concurrent.ExecutionException e) {
            throw new IOException("world query timed out or failed", e);
        } finally {
            this.queryPending.remove(callId);
        }
    }

    public static final class QueryException extends IOException {

        private final int status;

        public QueryException(int status) {
            super(status == BridgeCodec.QUERY_NOT_FOUND ? "not found" : "unavailable");
            this.status = status;
        }

        public int status() {
            return this.status;
        }

        public boolean notFound() {
            return this.status == BridgeCodec.QUERY_NOT_FOUND;
        }
    }

    /**
     * Serializes direct writes (sync events, queries) against the writer
     * thread; short critical sections, no waiting inside them.
     */
    private static final class ReentrantWriteBarrier {

        private final ReentrantLock lock = new ReentrantLock();
        private OutputStream stream;

        void attach(SocketChannel channel) {
            this.lock.lock();
            try {
                this.stream = new BufferedOutputStream(new SocketOutputStream(channel), 128 * 1024);
            } finally {
                this.lock.unlock();
            }
        }

        void detach() {
            this.lock.lock();
            try {
                this.stream = null;
            } finally {
                this.lock.unlock();
            }
        }

        void write(byte[] frame) throws IOException {
            this.lock.lock();
            try {
                if (this.stream == null) {
                    throw new IOException("bridge not connected");
                }
                BridgeCodec.writeFrame(this.stream, frame);
            } finally {
                this.lock.unlock();
            }
        }
    }

    /**
     * True when the recorder should stream deltas (any subscriber of the
     * world model: Go plugins, or Java/Python/PHP plugins that queried the
     * world at least once this session).
     */
    public boolean shouldStreamWorld() {
        return this.settings.enable() && this.connected.get();
    }

    public ProxyServer proxy() {
        return this.proxy;
    }

    public WorldViewSettings settings() {
        return this.settings;
    }

    /**
     * Resolves an online player by name (used by the RPC facade).
     */
    ProxiedPlayer player(String name) {
        return this.proxy.getPlayerManager().getPlayer(name);
    }

    public ProxyApiFacade facade() {
        return this.facade;
    }
}
