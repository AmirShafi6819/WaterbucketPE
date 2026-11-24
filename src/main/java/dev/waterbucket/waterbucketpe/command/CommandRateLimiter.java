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

package dev.waterbucket.waterbucketpe.command;

import dev.waterbucket.waterbucketpe.ProxyServer;
import lombok.extern.log4j.Log4j2;

import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Velocity-style per-player command rate limiting ({@code listener.command_rate_limit}).
 * <p>
 * Every player gets a smooth token bucket: {@code rate} tokens per second, with a one-second
 * burst capacity, refilled continuously instead of in discrete windows, so typing commands at
 * a normal pace never trips the limiter while a chat-flood client is dropped at the configured
 * rate no matter how it paces its packets. Console and panel commands are never limited.
 * <p>
 * Buckets for players who have stopped commanding are swept periodically so the map can not
 * grow with the day's visitor count; the sweep keeps entries for {@link #IDLE_EXPIRE_MS} of
 * silence, which is far below the lifetime of any session that could still send a command.
 * A limit of {@code 0} in config disables the limiter entirely: no buckets, no sweep, zero
 * overhead.
 */
@Log4j2
public class CommandRateLimiter {

    /**
     * How often idle buckets are swept, in scheduler ticks (20 ticks = 1s).
     */
    public static final int SWEEP_PERIOD_TICKS = 12000;
    /**
     * A bucket unused for this long belongs to a long-gone player and is dropped by the sweep.
     */
    static final long IDLE_EXPIRE_MS = TimeUnit.MINUTES.toMillis(10);

    private final ProxyServer proxy;
    private final Map<UUID, TokenBucket> buckets = new ConcurrentHashMap<>();

    public CommandRateLimiter(ProxyServer proxy) {
        this.proxy = proxy;
    }

    /**
     * Starts the idle sweep when the limiter is enabled in config; a disabled limiter starts
     * no task and allocates nothing.
     */
    public void start() {
        if (this.limit() <= 0) {
            return;
        }
        this.proxy.getScheduler().scheduleRepeating(this::sweepIdleBuckets, SWEEP_PERIOD_TICKS, true);
    }

    /**
     * Tries to consume one command slot for the player.
     *
     * @param playerId the player's unique id
     * @return true when the command may run, false when the player is over the configured rate
     */
    public boolean tryAcquire(UUID playerId) {
        int limit = this.limit();
        if (limit <= 0) {
            return true;
        }
        return this.buckets
                .computeIfAbsent(playerId, id -> new TokenBucket(limit))
                .tryAcquire();
    }

    /**
     * Drops buckets that have been silent past {@link #IDLE_EXPIRE_MS}. Cheap no-op while the
     * map is empty, which is the steady state of a quiet proxy.
     */
    void sweepIdleBuckets() {
        if (this.buckets.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        int removed = 0;
        Iterator<Map.Entry<UUID, TokenBucket>> iterator = this.buckets.entrySet().iterator();
        while (iterator.hasNext()) {
            if (iterator.next().getValue().idleFor(now) > IDLE_EXPIRE_MS) {
                iterator.remove();
                removed++;
            }
        }
        if (removed > 0) {
            log.debug("Command rate limiter swept {} idle bucket(s)", removed);
        }
    }

    private int limit() {
        return this.proxy.getConfiguration().getCommandRateLimit();
    }

    /**
     * The bucket for a player, creating it if needed. Package-private so tests can inspect
     * and manipulate buckets without reflecting into private state.
     */
    TokenBucket bucket(UUID playerId) {
        int limit = this.limit();
        if (limit <= 0) {
            return null;
        }
        return this.buckets.computeIfAbsent(playerId, id -> new TokenBucket(limit));
    }

    /**
     * Whether a bucket currently exists for the player, without creating one. Package-private
     * test accessor for sweep assertions.
     */
    boolean tracks(UUID playerId) {
        return this.buckets.containsKey(playerId);
    }

    /**
     * Smooth token bucket. Refill and consumption are atomic per bucket; buckets are tiny
     * (two fields) and one per actively-commanding player.
     */
    static final class TokenBucket {

        private final int rate;
        double tokens;
        long lastRefillNanos;
        long lastUseMillis;

        TokenBucket(int rate) {
            this.rate = rate;
            // A fresh bucket starts full: the first command a player types is never rejected
            // because a previous session of the same id consumed the budget.
            this.tokens = rate;
            this.lastRefillNanos = System.nanoTime();
            this.lastUseMillis = System.currentTimeMillis();
        }

        synchronized boolean tryAcquire() {
            long now = System.nanoTime();
            double refill = (now - this.lastRefillNanos) / 1_000_000_000.0 * this.rate;
            if (refill > 0) {
                // One second of burst capacity, like a smooth Velocity RateLimiter.
                this.tokens = Math.min(this.rate, this.tokens + refill);
                this.lastRefillNanos = now;
            }
            this.lastUseMillis = System.currentTimeMillis();
            if (this.tokens >= 1.0) {
                this.tokens -= 1.0;
                return true;
            }
            return false;
        }

        long idleFor(long nowMillis) {
            return nowMillis - this.lastUseMillis;
        }
    }
}
