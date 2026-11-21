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

package dev.waterbucket.waterbucketpe.security;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The RakNet listener binds one channel per core, so concurrent login attempts from one IP hit
 * the throttle from several event loops at once. Concurrent first-hits used to create separate
 * counters (get-then-put race), under-counting exactly during a flood.
 */
class ConnectionThrottleTest {

    @Test
    void concurrentFirstHitsShareOneCounter() throws Exception {
        ConnectionThrottle throttle = new ConnectionThrottle(50, 60_000);
        InetAddress address = InetAddress.getByName("203.0.113.24");

        int threads = 16;
        int attemptsPerThread = 10; // 160 attempts total against a limit of 50
        AtomicInteger admitted = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(threads);
            for (int t = 0; t < threads; t++) {
                pool.execute(() -> {
                    try {
                        start.await();
                        for (int i = 0; i < attemptsPerThread; i++) {
                            if (throttle.throttle(address)) {
                                admitted.incrementAndGet();
                            } else {
                                rejected.incrementAndGet();
                            }
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertTrue(done.await(10, TimeUnit.SECONDS), "all throttle attempts finished");
        } finally {
            pool.shutdownNow();
        }

        assertEquals(160, admitted.get() + rejected.get(), "every attempt must be counted exactly once");
        assertEquals(50, admitted.get(), "the per-IP limit must hold exactly under concurrent first-hits");
        assertEquals(110, rejected.get());
    }

    @Test
    void separateAddressesDoNotShareCounters() throws Exception {
        ConnectionThrottle throttle = new ConnectionThrottle(2, 60_000);
        assertTrue(throttle.throttle(InetAddress.getByName("203.0.113.1")));
        assertTrue(throttle.throttle(InetAddress.getByName("203.0.113.1")));
        assertFalse(throttle.throttle(InetAddress.getByName("203.0.113.1")));
        assertTrue(throttle.throttle(InetAddress.getByName("203.0.113.2")),
                "a different address must have its own budget");
    }

    @Test
    void resetClearsTheBudget() throws Exception {
        ConnectionThrottle throttle = new ConnectionThrottle(1, 60_000);
        InetAddress address = InetAddress.getByName("203.0.113.5");
        assertTrue(throttle.throttle(address));
        assertFalse(throttle.throttle(address));
        throttle.reset(address);
        assertTrue(throttle.throttle(address), "reset must restore the budget");
    }
}
