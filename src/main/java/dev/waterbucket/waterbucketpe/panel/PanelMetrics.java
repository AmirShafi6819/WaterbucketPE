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

package dev.waterbucket.waterbucketpe.panel;

import dev.waterbucket.waterbucketpe.ProxyServer;
import dev.waterbucket.waterbucketpe.network.serverinfo.ServerInfo;
import lombok.extern.log4j.Log4j2;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Samples proxy health for the web panel: a fixed-size ring of status snapshots (players,
 * memory, CPU, per-server counts) plus the tail of the console log. All data stays in memory;
 * nothing touches the disk.
 */
@Log4j2
public class PanelMetrics {

    /**
     * One sample every 5 seconds, so the buffer spans roughly 36 minutes.
     */
    static final int SAMPLE_PERIOD_TICKS = 20 * 5;
    private static final int HISTORY_CAP = 432;
    private static final int LOG_CAP = 400;

    private final ProxyServer proxy;
    private final MemoryMXBean memory = ManagementFactory.getMemoryMXBean();
    private final com.sun.management.OperatingSystemMXBean os =
            (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();

    private final ArrayDeque<Map<String, Object>> history = new ArrayDeque<>(HISTORY_CAP);
    private final ArrayDeque<String> logs = new ArrayDeque<>(LOG_CAP);
    private final Object historyLock = new Object();
    private final Object logLock = new Object();
    private final Map<String, ServerState> serverStates = new ConcurrentHashMap<>();

    private LogTailAppender appender;
    private volatile boolean running;
    private final long startedAt = System.currentTimeMillis();

    public PanelMetrics(ProxyServer proxy) {
        this.proxy = proxy;
    }

    public void start() {
        this.running = true;
        this.attachLogAppender();
        this.proxy.getScheduler().scheduleRepeating(this::sample, SAMPLE_PERIOD_TICKS, true);
        // Initial snapshot so the panel has data before the first period elapses
        this.sample();
    }

    public void stop() {
        this.running = false;
        this.detachLogAppender();
    }

    private void sample() {
        if (!this.running) {
            return;
        }
        try {
            Map<String, Object> point = new LinkedHashMap<>();
            point.put("time", System.currentTimeMillis());
            point.put("players", this.proxy.getPlayerManager().getPlayerCount());
            point.put("memoryUsed", this.memory.getHeapMemoryUsage().getUsed());
            point.put("memoryMax", this.memory.getHeapMemoryUsage().getMax() <= 0
                    ? this.memory.getHeapMemoryUsage().getCommitted() : this.memory.getHeapMemoryUsage().getMax());
            double load = this.os.getProcessCpuLoad();
            point.put("cpu", load < 0 ? 0 : Math.round(load * 1000.0) / 10.0);
            point.put("threads", Thread.activeCount());

            Map<String, Integer> perServer = new LinkedHashMap<>();
            for (ServerInfo server : this.proxy.getServerInfoMap().values()) {
                perServer.put(server.getServerName(), server.getPlayers().size());
                this.serverStates.computeIfAbsent(server.getServerName(), name -> new ServerState())
                        .players = server.getPlayers().size();
            }
            point.put("perServer", perServer);

            synchronized (this.historyLock) {
                this.history.addLast(point);
                while (this.history.size() > HISTORY_CAP) {
                    this.history.pollFirst();
                }
            }
        } catch (Throwable t) {
            // sampling must never break the scheduler
            log.debug("Panel sampling failed", t);
        }
    }

    private void attachLogAppender() {
        try {
            this.appender = new LogTailAppender();
            this.appender.start();
            LoggerContext context = (LoggerContext) LogManager.getContext(false);
            context.getConfiguration().getRootLogger().addAppender(this.appender, null, null);
            context.updateLoggers();
        } catch (Throwable t) {
            log.debug("Panel log tail unavailable", t);
        }
    }

    private void detachLogAppender() {
        if (this.appender == null) {
            return;
        }
        try {
            LoggerContext context = (LoggerContext) LogManager.getContext(false);
            context.getConfiguration().getRootLogger().removeAppender(this.appender.getName());
            context.updateLoggers();
            this.appender.stop();
        } catch (Throwable t) {
            log.debug("Panel log tail detach failed", t);
        }
        this.appender = null;
    }

    public Map<String, Object> status() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("time", System.currentTimeMillis());
        status.put("uptimeMs", System.currentTimeMillis() - this.startedAt);
        status.put("players", this.proxy.getPlayerManager().getPlayerCount());
        status.put("proxyName", this.proxy.getConfiguration().getName());
        status.put("proxyVersion", dev.waterbucket.waterbucketpe.WaterbucketPE.version().baseVersion());
        status.put("javaVersion", System.getProperty("java.version"));
        status.put("osName", System.getProperty("os.name"));
        status.put("osArch", System.getProperty("os.arch"));
        status.put("cores", Runtime.getRuntime().availableProcessors());
        status.put("memoryUsed", this.memory.getHeapMemoryUsage().getUsed());
        status.put("memoryMax", this.memory.getHeapMemoryUsage().getMax() <= 0
                ? this.memory.getHeapMemoryUsage().getCommitted() : this.memory.getHeapMemoryUsage().getMax());
        double load = this.os.getProcessCpuLoad();
        status.put("cpu", load < 0 ? 0 : Math.round(load * 1000.0) / 10.0);
        status.put("systemLoadAverage", this.os.getSystemLoadAverage());
        status.put("threads", Thread.activeCount());
        status.put("tick", this.proxy.getCurrentTick());

        List<Map<String, Object>> servers = new ArrayList<>();
        for (ServerInfo server : this.proxy.getServerInfoMap().values()) {
            ServerState state = this.serverStates.get(server.getServerName());
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", server.getServerName());
            entry.put("type", server.getServerType().toString());
            entry.put("address", server.getPublicAddress().getHostString() + ":" + server.getPublicAddress().getPort());
            entry.put("players", server.getPlayers().size());
            entry.put("pingMs", state == null ? -1 : state.lastPingMs);
            entry.put("online", state == null || state.lastPingMs >= 0);
            long upSince = state == null ? this.startedAt : Math.max(state.lastSuccessAt, this.startedAt);
            entry.put("upMs", state == null || state.lastPingMs >= 0 ? System.currentTimeMillis() - upSince : 0);
            servers.add(entry);
        }
        status.put("servers", servers);
        return status;
    }

    public List<Map<String, Object>> history() {
        synchronized (this.historyLock) {
            return new ArrayList<>(this.history);
        }
    }

    public Map<String, Object> serversSnapshot() {
        return this.status();
    }

    public List<String> recentLogs() {
        synchronized (this.logLock) {
            return new ArrayList<>(this.logs);
        }
    }

    public void recordPing(String server, boolean success, long latencyMs) {
        ServerState state = this.serverStates.computeIfAbsent(server, name -> new ServerState());
        if (success) {
            state.lastPingMs = latencyMs;
            state.lastSuccessAt = System.currentTimeMillis();
        } else {
            state.lastPingMs = -1;
        }
    }

    private static class ServerState {
        volatile long lastPingMs = -1;
        volatile long lastSuccessAt = System.currentTimeMillis();
        volatile int players;
    }

    private class LogTailAppender extends AbstractAppender {

        LogTailAppender() {
            super("WaterbucketPanelTail", null, null, true, Property.EMPTY_ARRAY);
        }

        @Override
        public void append(LogEvent event) {
            if (!PanelMetrics.this.running) {
                return;
            }
            long epochMs = event.getInstant().getEpochMillisecond();
            String line = new java.text.SimpleDateFormat("HH:mm:ss").format(new java.util.Date(epochMs))
                    + " [" + event.getLevel().name() + "] "
                    + event.getMessage().getFormattedMessage();
            Throwable thrown = event.getThrown();
            if (thrown != null) {
                line += " - " + thrown.getClass().getSimpleName() + ": " + thrown.getMessage();
            }
            synchronized (PanelMetrics.this.logLock) {
                PanelMetrics.this.logs.addLast(line);
                while (PanelMetrics.this.logs.size() > LOG_CAP) {
                    PanelMetrics.this.logs.pollFirst();
                }
            }
        }
    }
}
