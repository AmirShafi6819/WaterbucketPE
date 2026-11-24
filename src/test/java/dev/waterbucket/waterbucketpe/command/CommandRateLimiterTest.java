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
import dev.waterbucket.waterbucketpe.utils.config.proxy.ProxyConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests the Velocity-style per-player command rate limiting: the burst budget, the smooth
 * refill, per-player isolation, the disabled (0) mode and the idle-bucket sweep.
 */
class CommandRateLimiterTest {

    private ProxyServer proxy;
    private ProxyConfig config;
    private CommandRateLimiter limiter;

    @BeforeEach
    void setUp() {
        this.proxy = mock(ProxyServer.class);
        this.config = mock(ProxyConfig.class);
        when(this.proxy.getConfiguration()).thenReturn(this.config);
        this.limiter = new CommandRateLimiter(this.proxy);
    }

    @Test
    void allowsExactlyOneSecondOfBurstThenDrops() {
        when(this.config.getCommandRateLimit()).thenReturn(5);
        UUID player = UUID.randomUUID();

        for (int i = 0; i < 5; i++) {
            assertTrue(this.limiter.tryAcquire(player),
                    "command " + (i + 1) + " of the 5/s budget must pass");
        }
        assertFalse(this.limiter.tryAcquire(player), "the 6th command inside one second must drop");
    }

    @Test
    void bucketsAreIndependentPerPlayer() {
        when(this.config.getCommandRateLimit()).thenReturn(2);
        UUID spammer = UUID.randomUUID();
        UUID normal = UUID.randomUUID();

        for (int i = 0; i < 2; i++) {
            assertTrue(this.limiter.tryAcquire(spammer));
        }
        assertFalse(this.limiter.tryAcquire(spammer), "the spamming player is over budget");

        assertTrue(this.limiter.tryAcquire(normal),
                "another player's exhausted budget must never block a different player");
    }

    @Test
    void tokensRefillOverTime() {
        when(this.config.getCommandRateLimit()).thenReturn(2);
        UUID player = UUID.randomUUID();

        assertTrue(this.limiter.tryAcquire(player));
        assertTrue(this.limiter.tryAcquire(player));
        assertFalse(this.limiter.tryAcquire(player), "budget exhausted");

        // Simulate time passing: backdate the last refill by two seconds
        CommandRateLimiter.TokenBucket bucket = this.limiter.bucket(player);
        bucket.lastRefillNanos = System.nanoTime() - 2_000_000_000L;

        assertTrue(this.limiter.tryAcquire(player), "a refilled bucket must accept again");
    }

    @Test
    void refillIsCappedAtOneSecondOfBurst() {
        when(this.config.getCommandRateLimit()).thenReturn(2);
        UUID player = UUID.randomUUID();

        CommandRateLimiter.TokenBucket bucket = this.limiter.bucket(player);
        // Drain the bucket completely, then pretend a minute passed
        bucket.tokens = 0;
        bucket.lastRefillNanos = System.nanoTime() - 60_000_000_000L;

        this.limiter.tryAcquire(player);
        assertEquals(1.0, bucket.tokens, 1e-9,
                "a capped refill (2) minus one consumed command must leave exactly one token");
    }

    @Test
    void zeroLimitDisablesTheLimiter() {
        when(this.config.getCommandRateLimit()).thenReturn(0);
        UUID player = UUID.randomUUID();

        for (int i = 0; i < 500; i++) {
            assertTrue(this.limiter.tryAcquire(player), "rate_limit=0 must never drop commands");
        }
        assertNull(this.limiter.bucket(player), "a disabled limiter allocates no buckets");
    }

    @Test
    void sweepRemovesOnlyIdleBuckets() {
        when(this.config.getCommandRateLimit()).thenReturn(1);
        UUID stale = UUID.randomUUID();
        UUID fresh = UUID.randomUUID();

        assertTrue(this.limiter.tryAcquire(stale));
        assertTrue(this.limiter.tryAcquire(fresh));

        // Backdate only the stale player's last use
        this.limiter.bucket(stale).lastUseMillis = System.currentTimeMillis()
                - (CommandRateLimiter.IDLE_EXPIRE_MS + 60_000);

        this.limiter.sweepIdleBuckets();
        assertFalse(this.limiter.tracks(stale), "a long-idle bucket must be swept");
        assertTrue(this.limiter.tracks(fresh), "an active player's bucket must survive the sweep");
        assertFalse(this.limiter.tryAcquire(fresh),
                "the fresh player still has no budget left; the sweep must not refill it");
    }

    @Test
    void sweepOnEmptyMapIsANoOp() {
        when(this.config.getCommandRateLimit()).thenReturn(1);
        this.limiter.sweepIdleBuckets();
        assertFalse(this.limiter.tracks(UUID.randomUUID()),
                "sweeping an empty map must not create or keep buckets");
    }
}
