/*
 * Copyright 2022 WaterbucketTEAM
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

package dev.waterbucket.waterbucketpe.network.serverinfo;

import dev.waterbucket.waterbucketpe.ProxyServer;
import dev.waterbucket.waterbucketpe.network.connection.client.ClientConnection;
import dev.waterbucket.waterbucketpe.player.ProxiedPlayer;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.Promise;
import lombok.Getter;
import lombok.ToString;
import org.cloudburstmc.protocol.bedrock.BedrockPong;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Base informative class for servers.
 * Every server registered to the Proxy has one instance of this class, holding its name, and it's address (ip&port)
 * Also holds a list of all ProxiedPlayers connected.
 */
@ToString(exclude = {"players"})
public abstract class ServerInfo {

    /**
     * How long a resolved downstream address is reused before a background re-resolve is triggered.
     * A miss only schedules an async refresh; callers always get the last-known-good address immediately.
     */
    private static final long DNS_CACHE_TTL_MS = TimeUnit.SECONDS.toMillis(30);

    /**
     * Single daemon thread that performs the (blocking) JDK name resolution OFF the Netty event loops.
     * Historically {@code connect()} was handed an unresolved InetSocketAddress, so Netty resolved it
     * inline on a worker event loop via blocking InetAddress.getByName, a slow/hung DNS lookup there
     * froze every player sharing that loop. All resolution now happens on this thread instead.
     */
    private static final ExecutorService DNS_RESOLVER = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "ServerInfo DNS Resolver");
        thread.setDaemon(true);
        return thread;
    });

    @Getter
    private final String serverName;
    @Getter
    private final InetSocketAddress address;
    @Getter
    private final InetSocketAddress publicAddress;

    // Last successfully resolved connect target. Never null: seeded with the configured address so behavior
    // never regresses below the old "resolve inline" path even if DNS is down at startup.
    private volatile InetSocketAddress resolvedAddress;
    private volatile long resolvedAt;
    private final AtomicBoolean resolving = new AtomicBoolean(false);

    private final Set<ClientConnection> connections = ConcurrentHashMap.newKeySet();
    private final Set<ProxiedPlayer> players = ConcurrentHashMap.newKeySet();

    /**
     * Operator-controlled availability flag (config {@code servers.<name>.disabled}, toggled
     * at runtime via {@code /wbserveroffline}). A disabled server reports itself as offline
     * (pings fail fast, so the panel shows it offline) and refuses new connections, while the
     * downstream process itself may still be running - the proxy just stops sending players
     * to it. Volatile: toggled from command threads, read on event loops.
     */
    @Getter
    private volatile boolean disabled = false;

    public ServerInfo(String serverName, InetSocketAddress address, InetSocketAddress publicAddress) {
        this.serverName = serverName;
        this.address = address;
        if (publicAddress == null) {
            publicAddress = address;
        }
        this.publicAddress = publicAddress;

        // Seed the cache. Done synchronously here (constructed at startup/config-load, never on an event loop)
        // so the very first connect already has a resolved address and Netty never resolves inline.
        this.resolvedAddress = address;
        this.refreshResolvedAddress();
    }

    /**
     * Returns a resolved (non-blocking to use) connect target for this server. Always returns immediately
     * with the last-known-good address; if the cache is older than {@link #DNS_CACHE_TTL_MS} a re-resolve is
     * scheduled on the {@link #DNS_RESOLVER} thread so the event loop never performs DNS itself.
     */
    public InetSocketAddress getResolvedAddress() {
        if (System.currentTimeMillis() - this.resolvedAt > DNS_CACHE_TTL_MS && this.resolving.compareAndSet(false, true)) {
            DNS_RESOLVER.execute(() -> {
                try {
                    this.refreshResolvedAddress();
                } finally {
                    this.resolving.set(false);
                }
            });
        }
        return this.resolvedAddress;
    }

    protected void refreshResolvedAddress() {
        InetSocketAddress configured = this.address;
        try {
            // getByName on an IP literal is purely local (no network), so IP-configured servers resolve cheaply
            // and still short-circuit Netty's resolver because the result is a resolved InetSocketAddress.
            InetAddress resolved = InetAddress.getByName(configured.getHostString());
            // Keeps the configured name on the address: HTTPS signaling validates the certificate against it
            this.resolvedAddress = new InetSocketAddress(
                    InetAddress.getByAddress(configured.getHostString(), resolved.getAddress()), configured.getPort());
        } catch (UnknownHostException e) {
            // Keep the last-known-good (or the raw configured) address; a transient DNS failure must not break
            // connects. The next access past the TTL will retry.
            if (ProxyServer.getInstance() != null) {
                ProxyServer.getInstance().getLogger().debug("Failed to resolve address for server {} ({}): {}",
                        this.serverName, configured.getHostString(), e.getMessage());
            }
        } finally {
            this.resolvedAt = System.currentTimeMillis();
        }
    }

    public abstract ServerInfoType getServerType();
    public abstract Future<ClientConnection> createConnection(ProxiedPlayer player);

    /**
     * Reachability probe used by the panel health tracker ("online", "ping", "up for").
     * The future completes with the pong when the server answered inside the timeout and
     * fails otherwise. The default implementation is a failed future: a custom ServerInfo
     * type without a transport-level probe stays flagged as offline rather than being
     * guessed at.
     */
    public Future<BedrockPong> ping(long timeout, TimeUnit unit) {
        Promise<BedrockPong> promise = io.netty.util.concurrent.GlobalEventExecutor.INSTANCE.newPromise();
        promise.setFailure(new UnsupportedOperationException(
                "Server type " + this.getServerType() + " cannot be probed"));
        return promise;
    }

    /**
     * Matches a host name/address and port against this server's public address.
     * <p>
     * This runs on Netty event loops (fast transfer handling), so it never performs DNS:
     * the previous implementation called {@link InetSocketAddress#getHostName()}, which can
     * trigger a blocking reverse lookup and stall every player sharing the loop. Loopback
     * aliases (127.x.x.x / localhost / ::1) are treated as one host so local multi-port setups
     * always match no matter how each side names the address, and literal IPs are compared
     * numerically against the cached {@link #resolvedAddress}.
     *
     * @param address host name or literal IP the transfer/target points at, may be null
     * @param port    port the transfer/target points at
     * @return true if this server is the one living at that address and port
     */
    public boolean matchAddress(String address, int port) {
        if (address == null || address.isEmpty() || port != this.publicAddress.getPort()) {
            return false;
        }
        String host = address.trim();
        if (host.isEmpty()) {
            return false;
        }
        if (this.publicAddress.getHostString().equalsIgnoreCase(host)) {
            return true;
        }
        // All of 127.0.0.0/8 and localhost are the same machine: a transfer to any loopback
        // spelling of this server's port is this server, without any name resolution.
        if (isLoopbackHost(host) && isLoopbackHost(this.publicAddress.getHostString())) {
            return true;
        }
        // Literal IP: compare bytes against the cached resolved connect target (no DNS).
        InetAddress literal = parseLiteral(host);
        if (literal != null) {
            InetAddress resolved = this.resolvedAddress.getAddress();
            return resolved != null && resolved.equals(literal);
        }
        // A host name that is neither the configured spelling nor a literal would need DNS to
        // verify; matching it would block the event loop, so it is not attempted here.
        return false;
    }

    /**
     * Parses a literal IP address without touching DNS. Returns null for anything that is not
     * a numeric address (host names), so callers never block.
     */
    private static InetAddress parseLiteral(String host) {
        String cleaned = host;
        if (cleaned.startsWith("[") && cleaned.endsWith("]")) {
            cleaned = cleaned.substring(1, cleaned.length() - 1);
        }
        if (cleaned.indexOf(':') >= 0) {
            // Only pure hex/dot/colon strings can be IPv6 literals; anything else (names, zone
            // IDs) is rejected so this method can never fall into a DNS lookup.
            for (int i = 0; i < cleaned.length(); i++) {
                char c = cleaned.charAt(i);
                boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
                if (!hex && c != ':' && c != '.') {
                    return null;
                }
            }
            try {
                return InetAddress.getByName(cleaned);
            } catch (UnknownHostException e) {
                return null;
            }
        }
        // IPv4 literal check without DNS: only digits and dots are accepted.
        for (int i = 0; i < cleaned.length(); i++) {
            char c = cleaned.charAt(i);
            if (c != '.' && (c < '0' || c > '9')) {
                return null;
            }
        }
        try {
            return InetAddress.getByName(cleaned);
        } catch (UnknownHostException e) {
            return null;
        }
    }

    /**
     * True for every spelling of the local machine: "localhost", any 127.0.0.0/8 literal and
     * the IPv6 loopback "::1". Name resolution is never attempted.
     */
    private static boolean isLoopbackHost(String host) {
        if (host == null || host.isEmpty()) {
            return false;
        }
        String cleaned = host.trim();
        if (cleaned.equalsIgnoreCase("localhost")) {
            return true;
        }
        if (cleaned.startsWith("[") && cleaned.endsWith("]")) {
            cleaned = cleaned.substring(1, cleaned.length() - 1);
        }
        if (cleaned.equals("::1") || cleaned.equals("0:0:0:0:0:0:0:1")) {
            return true;
        }
        if (cleaned.startsWith("127.")) {
            // 127.a.b.c with numeric octets only; anything else is a name, not a literal
            return parseLiteral(cleaned) != null;
        }
        return false;
    }

    public void addConnection(ClientConnection connection) {
        if (connection != null && connection.isConnected()) {
            this.players.add(connection.getPlayer());
            this.connections.add(connection);
        }
    }

    /**
     * Toggles this server's operator-controlled availability at runtime.
     *
     * @param disabled true to mark the server offline (joins refused, pings fail), false to
     *                 accept players again
     */
    public void setDisabled(boolean disabled) {
        this.disabled = disabled;
    }

    /**
     * A failed future with the shared "server is disabled" reason, so every entry point
     * (connection attempts, health probes) reports the same, accurate state.
     */
    protected <T> Future<T> disabledFuture() {
        Promise<T> promise = io.netty.util.concurrent.GlobalEventExecutor.INSTANCE.newPromise();
        promise.setFailure(new ServerDisabledException(this.serverName));
        return promise;
    }

    public void removeConnection(ClientConnection connection) {
        if (connection != null) {
            this.connections.remove(connection);
            this.players.remove(connection.getPlayer());
        }
    }

    public Set<ProxiedPlayer> getPlayers() {
        return Collections.unmodifiableSet(this.players);
    }

    public Set<ClientConnection> getConnections() {
        return Collections.unmodifiableSet(this.connections);
    }

}
