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
import dev.waterbucket.waterbucketpe.plugin.Plugin;
import dev.waterbucket.waterbucketpe.plugin.PluginYAML;
import dev.waterbucket.waterbucketpe.scheduler.WaterbucketScheduler;
import dev.waterbucket.waterbucketpe.utils.config.proxy.ProxyConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Covers the Velocity-style annotation listener surface: {@code @Subscribe} methods are
 * discovered, ordered by priority and isolated from each other's failures; ignoreCancelled
 * skips already-cancelled cancellable events; registrations are removable per listener object;
 * and everything registered for a plugin is detached when the plugin disables.
 */
class SubscribeListenerTest {

    private static class PingEvent extends Event {
    }

    private static class KickEvent extends Event implements CancellableEvent {
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
            this.setCancelled(true);
        }
    }

    /** Listener used in most cases: one plain, one high-priority, one ignoreCancelled method. */
    static class RecordingListener {
        final List<String> calls = new ArrayList<>();

        @Subscribe
        public void onPingNormal(PingEvent event) {
            this.calls.add("normal");
        }

        @Subscribe(priority = EventPriority.LOWEST)
        public void onPingFirst(PingEvent event) {
            this.calls.add("first");
        }

        @Subscribe(priority = EventPriority.HIGHEST)
        public void onPingLast(PingEvent event) {
            this.calls.add("last");
        }

        @Subscribe(ignoreCancelled = true)
        public void onKickSkipCancelled(KickEvent event) {
            this.calls.add("skip-cancelled");
        }

        @Subscribe
        public void onKickAlways(KickEvent event) {
            this.calls.add("always");
        }

        @Subscribe
        public void notAnEventListener(PingEvent event, int extra) {
            this.calls.add("wrong-signature");
        }

        public void unannotated(PingEvent event) {
            this.calls.add("unannotated");
        }
    }

    private EventManager manager;

    @BeforeEach
    void setUp() {
        this.manager = newManager();
    }

    private static EventManager newManager() {
        ProxyServer proxy = mock(ProxyServer.class);
        ProxyConfig config = mock(ProxyConfig.class);
        when(proxy.getConfiguration()).thenReturn(config);
        when(config.getIdleThreads()).thenReturn(2);
        return new EventManager(proxy);
    }

    @Test
    void annotatedMethodsReceiveEventsInPriorityOrder() {
        RecordingListener listener = new RecordingListener();
        this.manager.registerListener(listener);

        this.manager.callEvent(new PingEvent());

        // LOWEST runs before the default NORMAL, HIGHEST after
        assertEquals(List.of("first", "normal", "last"), listener.calls);
    }

    @Test
    void methodsWithWrongSignatureOrWithoutAnnotationAreIgnored() {
        RecordingListener listener = new RecordingListener();
        this.manager.registerListener(listener);

        KickEvent kick = new KickEvent();
        kick.setCancelled(true);
        this.manager.callEvent(kick);

        // wrong-signature (two parameters) and unannotated must never run
        assertEquals(List.of("always"), listener.calls);
    }

    @Test
    void ignoreCancelledSkipsButDoesNotBlockUninterestedHandlers() {
        RecordingListener listener = new RecordingListener();
        this.manager.registerListener(listener);

        KickEvent kick = new KickEvent();
        kick.setCancelled(true);
        this.manager.callEvent(kick);
        assertEquals(List.of("always"), listener.calls);

        kick.setCancelled(false);
        this.manager.callEvent(kick);
        // Relative order of two same-priority handlers of one listener is reflection order
        // (unspecified), so assert the multiset: the ignored handler now runs too.
        assertEquals(2, listener.calls.stream().filter("always"::equals).count());
        assertEquals(1, listener.calls.stream().filter("skip-cancelled"::equals).count());
    }

    @Test
    void handlerExceptionsDoNotBreakThePipeline() {
        class ThrowingListener {
            @Subscribe
            public void onPing(PingEvent event) {
                throw new IllegalStateException("broken handler");
            }
        }
        class FollowingListener {
            final List<String> calls = new ArrayList<>();

            @Subscribe
            public void onPing(PingEvent event) {
                this.calls.add("survived");
            }
        }
        ThrowingListener throwing = new ThrowingListener();
        FollowingListener following = new FollowingListener();
        this.manager.registerListener(throwing);
        this.manager.registerListener(following);

        this.manager.callEvent(new PingEvent());

        assertEquals(List.of("survived"), following.calls);
    }

    @Test
    void listenerCanBeUnregisteredAndReRegistered() {
        RecordingListener listener = new RecordingListener();
        this.manager.registerListener(listener);
        assertEquals(3, this.manager.subscriberCount(PingEvent.class));

        this.manager.unregisterListener(listener);
        assertEquals(0, this.manager.subscriberCount(PingEvent.class));

        // The double-registration guard releases after unregister, so re-registration works
        this.manager.registerListener(listener);
        assertEquals(3, this.manager.subscriberCount(PingEvent.class));
    }

    @Test
    void doubleRegistrationIsRejected() {
        RecordingListener listener = new RecordingListener();
        this.manager.registerListener(listener);

        assertThrows(IllegalArgumentException.class, () -> this.manager.registerListener(listener));
    }

    @Test
    void listenerWithoutSubscribeMethodsIsRejectedWithNothingRegistered() {
        class EmptyListener {
            @SuppressWarnings("unused")
            public void unrelatedMethod() {
            }
        }
        EmptyListener listener = new EmptyListener();
        this.manager.registerListener(listener);
        assertEquals(0, this.manager.subscriberCount(PingEvent.class));
    }

    @Test
    void unregisteringAnOwnerDetachesItsListeners() throws Exception {
        RecordingListener listener = new RecordingListener();
        Plugin owner = initializedPlugin(this.manager);
        this.manager.registerListener(owner, listener);
        assertEquals(3, this.manager.subscriberCount(PingEvent.class));

        this.manager.unregisterListeners(owner);
        assertEquals(0, this.manager.subscriberCount(PingEvent.class));
    }

    @Test
    void disablingThePluginDetachesEveryListenerAutomatically() throws Exception {
        Plugin plugin = initializedPlugin(this.manager);
        RecordingListener listener = new RecordingListener();

        plugin.setEnabled(true);
        this.manager.registerListener(plugin, listener);
        assertEquals(3, this.manager.subscriberCount(PingEvent.class));

        // The whole point of owner tracking: no plugin-side cleanup needed
        plugin.setEnabled(false);
        assertEquals(0, this.manager.subscriberCount(PingEvent.class));
        assertEquals(0, this.manager.subscriberCount(KickEvent.class));
    }

    /**
     * Builds a real plugin against the given manager's proxy, mirroring the
     * PluginLifecycleTest registration pattern (reflection init + proxy stubs).
     */
    static Plugin initializedPlugin(EventManager eventManager) throws Exception {
        ProxyServer proxy = mock(ProxyServer.class);
        when(proxy.getScheduler()).thenReturn(mock(WaterbucketScheduler.class));
        when(proxy.getEventManager()).thenReturn(eventManager);
        when(proxy.getLogger()).thenReturn(mock(dev.waterbucket.waterbucketpe.logger.MainLogger.class));

        Plugin plugin = new Plugin() {
            @Override
            public void onEnable() {
            }
        };
        PluginYAML descriptor = new PluginYAML();
        descriptor.name = "subscribe-listener-test";
        descriptor.version = "1.0.0";
        descriptor.main = "test";

        Method init = Plugin.class.getDeclaredMethod("init", PluginYAML.class, ProxyServer.class, File.class);
        init.setAccessible(true);
        init.invoke(plugin, descriptor, proxy, new File("build/tmp/subscribe-listener-test"));
        return plugin;
    }

    @Test
    void eventManagerExecutorIsBounded() {
        java.util.concurrent.ExecutorService executor = this.manager.getThreadedExecutor();
        assertTrue(executor instanceof java.util.concurrent.ThreadPoolExecutor, "event dispatch must use a ThreadPoolExecutor");
        java.util.concurrent.ThreadPoolExecutor pool = (java.util.concurrent.ThreadPoolExecutor) executor;
        assertEquals(2, pool.getCorePoolSize(), "core stays at idleThreads");
        assertEquals(4, pool.getMaximumPoolSize(), "elastic head-room is bounded (2x core)");
        assertFalse(pool.getQueue().remainingCapacity() <= 0, "bursts queue instead of spawning threads");
    }
}
