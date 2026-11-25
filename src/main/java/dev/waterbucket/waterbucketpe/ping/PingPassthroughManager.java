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

package dev.waterbucket.waterbucketpe.ping;

import dev.waterbucket.waterbucketpe.ProxyServer;
import dev.waterbucket.waterbucketpe.event.defaults.ProxyPingEvent;
import dev.waterbucket.waterbucketpe.network.serverinfo.ServerInfo;
import dev.waterbucket.waterbucketpe.utils.config.proxy.ProxyConfig;
import lombok.extern.log4j.Log4j2;
import org.cloudburstmc.protocol.bedrock.BedrockPong;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Velocity-style server-list ping passthrough: the proxy keeps a background snapshot of the
 * server a player would join first (the first enabled entry of {@code listener.priorities})
 * and, per {@link ProxyConfig.PingPassthrough}, answers its own pings with that snapshot
 * instead of the proxy-configured MOTD.
 * <p>
 * The snapshot is refreshed on a repeating scheduler task, never on the ping path itself: a
 * client ping always answers instantly from cache, so passthrough can not add latency to the
 * server list and a slow backend can never stall the listener. While the backend is
 * unreachable the cache keeps its last-good value, so a short hiccup does not flap the server
 * list; the config comment promises exactly this fallback.
 * <p>
 * Modes (same names and semantics as Velocity's {@code ping-passthrough}):
 * <ul>
 *   <li>{@code disabled} - proxy MOTD only (default)</li>
 *   <li>{@code description} - the backend's motd/sub-motd replace the proxy's, player counts
 *       stay live</li>
 *   <li>{@code all} - description plus the backend's player counts and game type</li>
 * </ul>
 * Values are applied before {@link ProxyPingEvent} fires, so plugins can always override the
 * passthrough result exactly like on Velocity.
 */
@Log4j2
public class PingPassthroughManager {

    /**
     * How often the backend pong snapshot is refreshed, in scheduler ticks (20 ticks = 1s).
     * 5 seconds keeps the server list fresh without producing a constant probe stream towards
     * the backend.
     */
    public static final int REFRESH_PERIOD_TICKS = 100;

    /**
     * Single probe timeout. Deliberately shorter than the panel's 3s probes: this value feeds
     * the public server list, so a stale-but-present answer is better than a long wait.
     */
    private static final long PING_TIMEOUT_SECONDS = 2;

    private final ProxyServer proxy;
    private final AtomicBoolean refreshing = new AtomicBoolean(false);
    private volatile ServerSnapshot snapshot;

    public PingPassthroughManager(ProxyServer proxy) {
        this.proxy = proxy;
    }

    /**
     * Starts the background refresh loop. Called once during boot; a passthrough mode of
     * {@code disabled} starts no task at all, keeping the idle proxy exactly as quiet as
     * before.
     */
    public void start() {
        ProxyConfig.PingPassthrough mode = this.mode();
        if (mode == ProxyConfig.PingPassthrough.DISABLED) {
            return;
        }
        log.info("Ping passthrough enabled ({}): the server list shows {}'s real ping data",
                mode.name().toLowerCase(Locale.ROOT), this.resolvePassthroughServerName());
        // First snapshot right away so the server list never shows proxy defaults for the first
        // seconds after a start or restart.
        this.proxy.getScheduler().scheduleAsync(this::refresh);
        this.proxy.getScheduler().scheduleRepeating(this::refresh, REFRESH_PERIOD_TICKS, true);
    }

    /**
     * Applies the cached backend pong to the event according to the configured mode. Called on
     * the ping path before the event fires; never blocks.
     */
    public void apply(ProxyPingEvent event) {
        ProxyConfig.PingPassthrough mode = this.mode();
        if (mode == ProxyConfig.PingPassthrough.DISABLED) {
            return;
        }
        ServerSnapshot snapshot = this.snapshot;
        if (snapshot == null) {
            // Backend not reachable (yet): keep the proxy's own motd, exactly like the config
            // comment promises.
            return;
        }
        event.setMotd(snapshot.motd());
        event.setSubMotd(snapshot.subMotd());
        if (mode == ProxyConfig.PingPassthrough.ALL) {
            event.setPlayerCount(snapshot.playerCount());
            event.setMaximumPlayerCount(snapshot.maximumPlayerCount());
            event.setGameType(snapshot.gameType());
        }
    }

    /**
     * One bounded background refresh. Scheduled runs and manual triggers collapse into a
     * single in-flight probe through {@link #refreshing}, so a burst of client pings can never
     * fan out into a storm of backend pings.
     */
    public void refresh() {
        if (this.mode() == ProxyConfig.PingPassthrough.DISABLED) {
            return;
        }
        if (!this.refreshing.compareAndSet(false, true)) {
            return;
        }
        ServerInfo server = this.resolvePassthroughServer();
        if (server == null) {
            this.refreshing.set(false);
            return;
        }
        try {
            server.ping(PING_TIMEOUT_SECONDS, TimeUnit.SECONDS).addListener(future -> {
                try {
                    if (future.cause() == null && future.getNow() instanceof BedrockPong pong) {
                        this.setSnapshot(pong);
                        log.debug("Ping passthrough snapshot refreshed from {}", server.getServerName());
                    } else if (future.cause() != null) {
                        // Keep the last-good snapshot; a later successful refresh clears it.
                        log.debug("Ping passthrough probe to {} failed: {}",
                                server.getServerName(), String.valueOf(future.cause()));
                    }
                } finally {
                    this.refreshing.set(false);
                }
            });
        } catch (Throwable t) {
            log.debug("Ping passthrough probe to {} could not be scheduled: {}",
                    server.getServerName(), t.toString());
            this.refreshing.set(false);
        }
    }

    /**
     * Stores a backend pong as the served snapshot. Package-private so tests can seed the
     * cache without a live downstream.
     */
    void setSnapshot(BedrockPong pong) {
        this.snapshot = ServerSnapshot.of(pong);
    }

    /**
     * The server whose ping data is shown: the first entry of {@code listener.priorities} that
     * exists and accepts players, falling back to the first enabled registered server. Null
     * when the proxy has no servers configured.
     */
    ServerInfo resolvePassthroughServer() {
        List<String> priorities = this.proxy.getConfiguration().getPriorities();
        if (priorities != null) {
            for (String name : priorities) {
                ServerInfo server = this.proxy.getServerInfoMap().get(name);
                if (server != null && !server.isDisabled()) {
                    return server;
                }
            }
        }
        for (ServerInfo server : this.proxy.getServerInfoMap().values()) {
            if (!server.isDisabled()) {
                return server;
            }
        }
        return null;
    }

    private String resolvePassthroughServerName() {
        ServerInfo server = this.resolvePassthroughServer();
        return server != null ? server.getServerName() : "<no servers>";
    }

    private ProxyConfig.PingPassthrough mode() {
        return this.proxy.getConfiguration().getPingPassthrough();
    }

    /**
     * Immutable pong snapshot; only the fields the passthrough modes can serve.
     */
    private record ServerSnapshot(String motd, String subMotd, String gameType,
                                  int playerCount, int maximumPlayerCount) {

        static ServerSnapshot of(BedrockPong pong) {
            return new ServerSnapshot(
                    pong.motd() == null ? "" : pong.motd(),
                    pong.subMotd() == null ? "" : pong.subMotd(),
                    pong.gameType() == null ? "Survival" : pong.gameType(),
                    pong.playerCount(),
                    pong.maximumPlayerCount());
        }
    }
}
