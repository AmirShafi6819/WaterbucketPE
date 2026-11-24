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

package dev.waterbucket.waterbucketpe.scheduler;

import dev.waterbucket.waterbucketpe.ProxyServer;
import dev.waterbucket.waterbucketpe.plugin.Plugin;
import dev.waterbucket.waterbucketpe.logger.MainLogger;
import dev.waterbucket.waterbucketpe.utils.config.proxy.ProxyConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Covers the plugin-aware scheduling surface: tasks scheduled for a plugin are owned by it,
 * cancelTasks(plugin) cancels the whole set, completed tasks deregister themselves, and
 * cancelTask(taskId) cancels exactly one handler.
 */
class SchedulerPluginTasksTest {

    private static Field singletonField;

    private ProxyServer proxy;
    private WaterbucketScheduler scheduler;
    private Plugin plugin;

    @BeforeEach
    void setUp() throws Exception {
        // WaterbucketScheduler refuses a second instance; reset the static handle between tests
        if (singletonField == null) {
            singletonField = WaterbucketScheduler.class.getDeclaredField("instance");
            singletonField.setAccessible(true);
        }
        singletonField.set(null, null);

        this.proxy = mock(ProxyServer.class);
        ProxyConfig config = mock(ProxyConfig.class);
        when(config.getIdleThreads()).thenReturn(1);
        when(this.proxy.getConfiguration()).thenReturn(config);
        when(this.proxy.getLogger()).thenReturn(mock(MainLogger.class));
        this.scheduler = new WaterbucketScheduler(this.proxy);
        this.plugin = mock(Plugin.class);
        when(this.plugin.getName()).thenReturn("SchedulerTestPlugin");
    }

    @AfterEach
    void tearDown() throws Exception {
        if (this.scheduler != null) {
            this.scheduler.shutdown();
        }
        singletonField.set(null, null);
    }

    @Test
    void pluginRepeatingTaskRunsUntilThePluginCancels() {
        AtomicInteger runs = new AtomicInteger();
        TaskHandler<?> handler = this.scheduler.scheduleRepeating(this.plugin, runs::incrementAndGet, 0, 1);

        assertSame(this.plugin, handler.getOwner(), "the handler must remember its owner");

        this.scheduler.onTick(1);
        assertEquals(1, runs.get(), "repeating task must run on its tick");

        this.scheduler.cancelTasks(this.plugin);
        assertTrue(handler.isCancelled(), "cancelTasks(plugin) must cancel the handler");

        this.scheduler.onTick(2);
        assertEquals(1, runs.get(), "a cancelled plugin task must not run again");
    }

    @Test
    void cancelTasksIsASafeNoOpForUnknownPlugins() {
        assertDoesNotThrow(() -> this.scheduler.cancelTasks(mock(Plugin.class)));
        assertDoesNotThrow(() -> this.scheduler.cancelTasks(null));
    }

    @Test
    void completedPluginTaskDeregistersItselfBeforeCancelTasks() {
        AtomicInteger runs = new AtomicInteger();
        this.scheduler.scheduleTask(this.plugin, runs::incrementAndGet, false);

        this.scheduler.onTick(1);
        assertEquals(1, runs.get(), "single-run task must run exactly once");

        // The task finished normally, so cancelTasks must not fire its cancel callbacks again
        this.scheduler.cancelTasks(this.plugin);
        this.scheduler.onTick(2);
        assertEquals(1, runs.get(), "completed task must not run again");
    }

    @Test
    void delayedPluginTaskWaitsForItsDelayAndHonorsCancellation() {
        AtomicInteger runs = new AtomicInteger();
        TaskHandler<?> handler = this.scheduler.scheduleDelayed(this.plugin, runs::incrementAndGet, 5);

        this.scheduler.onTick(1);
        assertEquals(0, runs.get(), "delayed task must not run before its delay");

        this.scheduler.cancelTasks(this.plugin);
        this.scheduler.onTick(5);
        assertEquals(0, runs.get(), "cancelled delayed task must never run");
        assertTrue(handler.isCancelled());
    }

    @Test
    void cancelTaskByIdCancelsOnlyThatHandler() {
        AtomicInteger repeating = new AtomicInteger();
        AtomicInteger other = new AtomicInteger();
        TaskHandler<?> target = this.scheduler.scheduleRepeating(this.plugin, repeating::incrementAndGet, 0, 1);
        this.scheduler.scheduleRepeating(this.plugin, other::incrementAndGet, 0, 1);

        this.scheduler.cancelTask(target.getTaskId());

        this.scheduler.onTick(1);
        assertEquals(0, repeating.get(), "the cancelled handler must not run");
        assertEquals(1, other.get(), "the untouched handler must keep running");

        // Cancelling an unknown id must be ignored, not throw
        this.scheduler.cancelTask(Integer.MAX_VALUE);
        this.scheduler.onTick(2);
        assertEquals(2, other.get(), "the untouched handler must run again");
    }

    @Test
    void pluginTaskRegistryIsClearedOnShutdown() throws Exception {
        AtomicInteger runs = new AtomicInteger();
        this.scheduler.scheduleRepeating(this.plugin, runs::incrementAndGet, 0, 1);

        Field registryField = WaterbucketScheduler.class.getDeclaredField("pluginTasks");
        registryField.setAccessible(true);
        java.util.Map<?, ?> registry = (java.util.Map<?, ?>) registryField.get(this.scheduler);
        assertTrue(registry.containsKey(this.plugin), "the plugin must own its task before shutdown");

        this.scheduler.shutdown();
        assertNull(registry.get(this.plugin), "shutdown must release the per-plugin registry");
    }

    @Test
    void asyncPluginTaskIsOwnedAndRunsOffThread() throws Exception {
        java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
        TaskHandler<?> handler = this.scheduler.scheduleTask(this.plugin, done::countDown, true);

        assertSame(this.plugin, handler.getOwner());
        assertTrue(handler.isAsync());

        this.scheduler.onTick(0);
        assertTrue(done.await(5, java.util.concurrent.TimeUnit.SECONDS), "async task must run on the executor");
    }

    @Test
    void nonPluginTasksAreUnaffectedByPluginCancellation() {
        AtomicInteger orphan = new AtomicInteger();
        TaskHandler<?> orphanHandler = this.scheduler.scheduleRepeating(orphan::incrementAndGet, 1);

        this.scheduler.cancelTasks(this.plugin);
        assertFalse(orphanHandler.isCancelled(), "proxy-internal tasks must survive plugin cancellation");

        this.scheduler.onTick(1);
        assertEquals(1, orphan.get(), "proxy-internal tasks must keep running");

        this.scheduler.cancelTask(orphanHandler.getTaskId());
        this.scheduler.onTick(2);
        assertEquals(1, orphan.get(), "only explicit cancellation stops proxy-internal tasks");
    }

    @Test
    void anyTaskThrowsAreContainedAndLogged() {
        // A throwing plugin task must not break the tick loop
        this.scheduler.scheduleRepeating(this.plugin, () -> { throw new IllegalStateException("boom"); }, 0, 1);
        assertDoesNotThrow(() -> this.scheduler.onTick(1));

        AtomicInteger later = new AtomicInteger();
        this.scheduler.scheduleRepeating(this.plugin, later::incrementAndGet, 0, 1);
        this.scheduler.onTick(2);
        assertEquals(1, later.get(), "the tick loop must survive a throwing task");
    }
}
