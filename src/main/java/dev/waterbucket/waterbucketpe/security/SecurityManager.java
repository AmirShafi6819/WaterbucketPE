/*
 * Copyright 2023 WaterbucketTEAM
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

package dev.waterbucket.waterbucketpe.security;

import dev.waterbucket.waterbucketpe.ProxyServer;
import dev.waterbucket.waterbucketpe.network.NetworkMetrics;
import dev.waterbucket.waterbucketpe.network.NetworkMetrics.ConnectionError;
import dev.waterbucket.waterbucketpe.network.NetworkMetrics.LoginFailure;
import dev.waterbucket.waterbucketpe.network.protocol.user.HandshakeEntry;
import dev.waterbucket.waterbucketpe.utils.config.proxy.NetworkSettings;
import io.netty.handler.codec.DecoderException;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.log4j.Log4j2;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

@Log4j2
public class SecurityManager {
    private final ProxyServer proxy;
    @Getter
    private final ConnectionThrottle connectionThrottle;
    @Getter
    private final ConnectionThrottle loginThrottle;

    private final Map<InetAddress, Long> blockedConnections = new ConcurrentHashMap<>();

    /**
     * Failed-login penalty: an address that keeps failing the crypto-heavy login chain is
     * consuming ECDSA/JWT CPU per attempt (and plugin listener time). The login throttle
     * already bounds attempts when enabled, but an operator can set it to 0, and blocked
     * or throttled-rejected attempts never penalized the address at all. After
     * {@link #LOGIN_FAILURE_LIMIT} failures inside the window the address is blocked for the
     * normal error timeout, reusing the swept {@link #blockedConnections} structure.
     */
    static final int LOGIN_FAILURE_LIMIT = 32;
    static final long LOGIN_FAILURE_WINDOW_MS = 60_000;

    private final Map<InetAddress, LoginStreak> failedLogins = new ConcurrentHashMap<>();

    /** Count + window start for one address. Approximate under races by design - throttling. */
    private static final class LoginStreak {
        final java.util.concurrent.atomic.AtomicLong count = new java.util.concurrent.atomic.AtomicLong();
        volatile long windowStart;
    }

    @Getter
    @Setter
    private SecurityListener listener;

    public SecurityManager(ProxyServer proxy) {
        this.proxy = proxy;

        NetworkSettings settings = proxy.getNetworkSettings();
        int time = settings.getConnectionThrottleTime();
        if (time < 50) {
            throw new IllegalArgumentException("For performance reasons connection throttle time should be 50ms or more.");
        }

        this.connectionThrottle = settings.getConnectionThrottle() > 0 ? new ConnectionThrottle(settings.getConnectionThrottle(), time) : null;
        this.loginThrottle = settings.getLoginThrottle() > 0 ? new ConnectionThrottle(settings.getLoginThrottle(), time) : null;
        proxy.getScheduler().scheduleRepeating(this::onBlockedTick, 1);
    }

    private void onBlockedTick() {
        long currTime = System.currentTimeMillis();

        Iterator<Map.Entry<InetAddress, Long>> iterator = this.blockedConnections.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<InetAddress, Long> entry = iterator.next();
            if (entry.getValue() != 0 && currTime > entry.getValue()) {
                iterator.remove();
                this.proxy.getLogger().info("Unblocked address {}", entry.getKey());
            }
        }

        // Drop stale failure counters so the map stays bounded by the active window.
        this.failedLogins.values().removeIf(streak -> currTime - streak.windowStart > LOGIN_FAILURE_WINDOW_MS);
    }

    public void blockAddress(InetAddress address, long time, TimeUnit unit) {
        long millis = unit.toMillis(time);
        this.blockedConnections.put(address, System.currentTimeMillis() + millis);
        this.proxy.getLogger().info("Connection from {} is blocked for {}ms", address, millis);
    }

    public void unblockAddress(InetAddress address) {
        if (this.blockedConnections.remove(address) != null) {
            this.proxy.getLogger().info("Unblocked address {}", address);
        }
    }

    public boolean isAddressBlocked(InetAddress address) {
        return this.blockedConnections.containsKey(address);
    }

    public boolean onConnectionCreated(SocketAddress address) {
        return this.admit(address, this.connectionThrottle, LoginFailure.CONNECTION_THROTTLED,
                listener -> listener.onConnectionCreated(address));
    }

    public boolean onLoginAttempt(SocketAddress address) {
        return this.admit(address, this.loginThrottle, LoginFailure.LOGIN_THROTTLED,
                listener -> listener.onLoginAttempt(address));
    }

    private boolean admit(SocketAddress address, ConnectionThrottle throttle, LoginFailure throttled,
                          Predicate<SecurityListener> allowed) {
        boolean success = true;
        if (throttle != null && address instanceof InetSocketAddress addr) {
            success = throttle.throttle(addr.getAddress());
            if (!success) {
                this.loginFailed(throttled);
                if (this.listener != null) {
                    try {
                        this.listener.onThrottleReached(addr.getAddress(), throttle);
                    } catch (Throwable t) {
                        // A throwing listener plugin runs inside initChannel/packet handling;
                        // fail open instead of killing the connection it was meant to gate.
                        this.proxy.getLogger().warning("SecurityListener.onThrottleReached threw", t);
                    }
                }
            }
        }

        if (this.listener != null) {
            boolean allowedByListener;
            try {
                allowedByListener = allowed.test(this.listener);
            } catch (Throwable t) {
                this.proxy.getLogger().warning("SecurityListener threw while checking a connection; allowing it", t);
                allowedByListener = true;
            }
            if (!allowedByListener) {
                if (success) {
                    this.loginFailed(LoginFailure.LISTENER);
                }
                success = false;
            }
        }
        return success;
    }

    public String onLoginFailed(SocketAddress address, HandshakeEntry handshakeEntry, Throwable throwable, String reason) {
        this.loginFailed(this.loginFailure(handshakeEntry, throwable));
        if (address instanceof InetSocketAddress inet) {
            this.recordLoginFailure(inet.getAddress());
        }
        if (this.listener != null) {
            try {
                return this.listener.onLoginFailed(address, handshakeEntry, throwable, reason);
            } catch (Throwable t) {
                this.proxy.getLogger().warning("SecurityListener.onLoginFailed threw; using the default disconnect reason", t);
            }
        }
        return reason;
    }

    /**
     * Counts a failed login for the source address and blocks it once the streak exceeds the
     * limit. Called per failed login only, so the steady-state cost for healthy traffic is a
     * single map lookup.
     */
    private void recordLoginFailure(InetAddress address) {
        int timeout = this.proxy.getNetworkSettings().errorTimeout();
        if (timeout == 0) {
            return;
        }
        long now = System.currentTimeMillis();
        LoginStreak streak = this.failedLogins.compute(address, (addr, existing) -> {
            if (existing == null || now - existing.windowStart > LOGIN_FAILURE_WINDOW_MS) {
                existing = new LoginStreak();
                existing.windowStart = now;
            }
            return existing;
        });
        if (streak.count.incrementAndGet() == LOGIN_FAILURE_LIMIT) {
            this.blockedConnections.put(address, now + TimeUnit.SECONDS.toMillis(timeout));
            this.failedLogins.remove(address);
            this.proxy.getLogger().warning("Blocked address {} after {} failed logins within {}s",
                    address, LOGIN_FAILURE_LIMIT, LOGIN_FAILURE_WINDOW_MS / 1000);
        }
    }

    /**
     * LoginUpstreamHandler fails a login that threw, one whose chain the auth service did not sign, which
     * only online mode refuses, and otherwise one whose key the transport identity does not hold.
     */
    private LoginFailure loginFailure(HandshakeEntry entry, Throwable throwable) {
        if (throwable != null || entry == null) {
            return LoginFailure.ERROR;
        }
        if (!entry.isXboxAuthed() && this.proxy.getConfiguration().isOnlineMode()) {
            return LoginFailure.XBOX_AUTH;
        }
        return LoginFailure.IDENTITY_KEY;
    }

    private void loginFailed(LoginFailure reason) {
        NetworkMetrics metrics = this.proxy.getNetworkMetrics();
        if (metrics != null) {
            metrics.loginFailed(reason);
        }
    }

    /**
     * Called when a client did not complete the login handshake in time.
     */
    public void onLoginTimeout(SocketAddress address) {
        this.onConnectionError(address, null, ConnectionError.TIMEOUT);
    }

    public void onConnectionError(SocketAddress address, Throwable cause) {
        this.onConnectionError(address, cause, connectionError(cause));
    }

    private static ConnectionError connectionError(Throwable cause) {
        if (cause == null) {
            return ConnectionError.PROTOCOL;
        }
        if (cause instanceof DecoderException) {
            return ConnectionError.DECODE;
        }
        return cause instanceof IOException ? ConnectionError.IO : ConnectionError.OTHER;
    }

    private void onConnectionError(SocketAddress address, Throwable cause, ConnectionError kind) {
        if (cause != null) {
            log.error("{} Exception caught in bedrock connection", address, cause);
        }

        NetworkMetrics metrics = this.proxy.getNetworkMetrics();
        if (metrics != null) {
            metrics.connectionError(kind);
        }

        int timeout = this.proxy.getNetworkSettings().errorTimeout();
        if (timeout == 0) {
            return;
        }

        if (address instanceof InetSocketAddress inet) {
            this.proxy.getSecurityManager().blockAddress(inet.getAddress(), timeout, TimeUnit.SECONDS);
        }
    }
}
