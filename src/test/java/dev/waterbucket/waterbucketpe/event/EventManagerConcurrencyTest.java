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

package dev.waterbucket.waterbucketpe.event;

import dev.waterbucket.waterbucketpe.ProxyServer;
import dev.waterbucket.waterbucketpe.utils.config.proxy.ProxyConfig;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Events are called from every Netty event loop while plugins subscribe from their own threads,
 * so the event pipeline must stay consistent under concurrency: no lost subscriptions, no
 * ConcurrentModificationException, and every subscribed handler runs exactly once per call.
 */
class EventManagerConcurrencyTest {

    private static class PingEvent extends Event {
    }

    private static EventManager newManager() {
        ProxyServer proxy = mock(ProxyServer.class);
        ProxyConfig config = mock(ProxyConfig.class);
        when(proxy.getConfiguration()).thenReturn(config);
        when(config.getIdleThreads()).thenReturn(1);
        return new EventManager(proxy);
    }

    @Test
    void concurrentSubscriptionsAreAllRegisteredAndInvoked() throws Exception {
        EventManager manager = newManager();
        int subscribers = 32;
        AtomicInteger invocations = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(subscribers);
            for (int i = 0; i < subscribers; i++) {
                pool.execute(() -> {
                    try {
                        start.await();
                        manager.subscribe(PingEvent.class, event -> invocations.incrementAndGet());
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertTrue(done.await(10, TimeUnit.SECONDS), "all subscribers finished");
        } finally {
            pool.shutdownNow();
        }

        // A single call after all subscriptions must invoke every one of them exactly once
        manager.callEvent(new PingEvent());
        assertEquals(subscribers, invocations.get(), "every concurrent subscription must fire once");
    }

    @Test
    void duplicateSubscriptionOfSameHandlerRunsItOncePerCall() {
        EventManager manager = newManager();
        AtomicInteger calls = new AtomicInteger();
        Consumer<PingEvent> handler = event -> calls.incrementAndGet();
        manager.subscribe(PingEvent.class, handler);
        manager.subscribe(PingEvent.class, handler);

        manager.callEvent(new PingEvent());
        assertEquals(1, calls.get(), "duplicate subscription of the same consumer must not double-fire");
    }

    @Test
    void eventCallsWhileSubscribingNeverFailOrLoseHandlers() throws Exception {
        EventManager manager = newManager();
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            int rounds = 500;
            AtomicInteger handlerInvocations = new AtomicInteger();
            CountDownLatch done = new CountDownLatch(rounds);
            for (int i = 0; i < rounds; i++) {
                pool.execute(() -> {
                    manager.subscribe(PingEvent.class, event -> handlerInvocations.incrementAndGet());
                    done.countDown();
                });
            }
            // Fire events from the test thread while subscriptions race in
            for (int i = 0; i < 200; i++) {
                manager.callEvent(new PingEvent());
            }
            assertTrue(done.await(10, TimeUnit.SECONDS), "all subscriptions finished");

            int before = handlerInvocations.get();
            manager.callEvent(new PingEvent());
            assertEquals(rounds, handlerInvocations.get() - before, "no subscription may be lost");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void priorityOrderingIsPreserved() {
        EventManager manager = newManager();
        StringBuilder order = new StringBuilder();
        manager.subscribe(PingEvent.class, event -> order.append('N'), EventPriority.NORMAL);
        manager.subscribe(PingEvent.class, event -> order.append('L'), EventPriority.LOW);
        manager.subscribe(PingEvent.class, event -> order.append('H'), EventPriority.HIGH);

        manager.callEvent(new PingEvent());
        assertEquals("LNH", order.toString(), "LOW runs before NORMAL before HIGH");
    }

    @Test
    void brokenHandlerDoesNotBreakOthers() {
        EventManager manager = newManager();
        AtomicInteger after = new AtomicInteger();
        manager.subscribe(PingEvent.class, event -> {
            throw new IllegalStateException("boom");
        });
        manager.subscribe(PingEvent.class, event -> after.incrementAndGet());

        manager.callEvent(new PingEvent());
        assertEquals(1, after.get());
    }
}
