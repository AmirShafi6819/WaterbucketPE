/*
 * Copyright 2026 WaterbucketTEAM
 * Licensed under the GNU General Public License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.gnu.org/licenses/old-licenses/gpl-2.0.html
 *
 * Unless required by applicable law or agreed to in writing, software
 * distribution under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.waterbucket.waterbucketpe.event;

import dev.waterbucket.waterbucketpe.ProxyServer;
import dev.waterbucket.waterbucketpe.utils.config.proxy.ProxyConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests the async-event dispatch contract, including the zero-subscriber fast path: an
 * async event with no listeners completes inline (no executor round-trip) while still
 * producing a completed future for the pipeline, and with listeners it dispatches normally.
 */
class AsyncEventFastPathTest {

    @AsyncEvent
    private static class JoinEvent extends Event {
        private boolean touched;

        public boolean isTouched() {
            return this.touched;
        }

        public void setTouched(boolean touched) {
            this.touched = touched;
        }
    }

    private EventManager events;

    @BeforeEach
    void setUp() {
        ProxyServer proxy = mock(ProxyServer.class);
        ProxyConfig config = mock(ProxyConfig.class);
        when(proxy.getConfiguration()).thenReturn(config);
        when(config.getIdleThreads()).thenReturn(1);
        this.events = new EventManager(proxy);
    }

    @Test
    void asyncEventWithoutSubscribersCompletesInline() {
        CompletableFuture<JoinEvent> future = this.events.callEvent(new JoinEvent());

        assertNotNull(future, "async events always return a future");
        // Inline completion: the future is already done when callEvent returns, and the
        // dependent (whenComplete) runs on the calling thread - no executor hop, no queue.
        AtomicBoolean inlineRan = new AtomicBoolean();
        future.whenComplete((event, error) -> inlineRan.set(true));
        assertTrue(inlineRan.get(), "dependents of an already-done future run inline");
        assertTrue(future.isDone(), "a subscriber-free async event must complete inline");
        assertFalse(future.isCompletedExceptionally());
    }

    @Test
    void asyncEventWithSubscriberStillDispatches() throws Exception {
        AtomicBoolean invoked = new AtomicBoolean();
        this.events.subscribe(JoinEvent.class, event -> {
            event.setTouched(true);
            invoked.set(true);
        });

        CompletableFuture<JoinEvent> future = this.events.callEvent(new JoinEvent());
        JoinEvent result = future.get(5, TimeUnit.SECONDS);
        assertTrue(invoked.get(), "subscribed handlers must run");
        assertTrue(result.isTouched(), "handlers must be able to mutate the event");
        assertEquals(1, this.events.subscriberCount(JoinEvent.class));
    }

    @Test
    void subscriberRegisteredAfterDispatchStillSeesTheNextEvent() throws Exception {
        // No subscribers: inline fast path
        CompletableFuture<JoinEvent> first = this.events.callEvent(new JoinEvent());
        assertTrue(first.isDone(), "inline fast path on the first event");

        // Subscribe between events: the next dispatch must run the handler
        AtomicBoolean invoked = new AtomicBoolean();
        this.events.subscribe(JoinEvent.class, event -> invoked.set(true));
        CompletableFuture<JoinEvent> second = this.events.callEvent(new JoinEvent());
        second.get(5, TimeUnit.SECONDS);
        assertTrue(invoked.get(), "a handler registered after the fast path must still be invoked");
    }

    @Test
    void cancellableAsyncEventFastPathStaysUncancelled() {
        CancellableJoin event = new CancellableJoin();
        CompletableFuture<CancellableJoin> future = this.events.callEvent(event);
        assertTrue(future.isDone(), "no subscribers = inline completion");
        assertFalse(event.isCancelled(), "the fast path must not cancel the event");
    }

    @AsyncEvent
    private static class CancellableJoin extends Event implements CancellableEvent {
        private boolean cancelled;

        @Override
        public boolean isCancelled() {
            return this.cancelled;
        }

        @Override
        public void setCancelled(boolean cancelled) {
            this.cancelled = cancelled;
        }

        @Override
        public void setCancelled() {
            this.cancelled = true;
        }
    }
}
