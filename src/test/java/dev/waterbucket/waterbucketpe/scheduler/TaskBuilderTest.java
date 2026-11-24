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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Covers the Velocity-style time-based scheduling surface: TaskBuilder delay/repeat semantics,
 * cancellation, exception isolation (a throwing task must not kill a repeating schedule) and
 * plugin ownership (cancelTasks(plugin) also detaches time-based tasks).
 */
class TaskBuilderTest {

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
        when(this.plugin.getName()).thenReturn("TaskBuilderTestPlugin");
    }

    @AfterEach
    void tearDown() throws Exception {
        if (this.scheduler != null) {
            this.scheduler.shutdown();
        }
        singletonField.set(null, null);
    }

    @Test
    void delayedTaskRunsOnceAfterTheDelay() throws Exception {
        CountDownLatch ran = new CountDownLatch(1);
        ScheduledTask task = this.scheduler.buildTask(ran::countDown)
                .delay(120, TimeUnit.MILLISECONDS)
                .schedule();

        assertFalse(task.isCancelled());
        // Not yet: the delay is respected in real time
        assertFalse(ran.await(50, TimeUnit.MILLISECONDS), "task must not run before its delay");
        assertTrue(ran.await(2, TimeUnit.SECONDS), "task must run after its delay");
        task.cancel(); // one-shot already done; cancel is a harmless no-op
        assertFalse(task.cancel(), "cancelling a finished one-shot returns false");
    }

    @Test
    void repeatingTaskKeepsRunningUntilCancelled() throws Exception {
        AtomicInteger runs = new AtomicInteger();
        ScheduledTask task = this.scheduler.buildTask(runs::incrementAndGet)
                .delay(0, TimeUnit.MILLISECONDS)
                .repeat(60, TimeUnit.MILLISECONDS)
                .schedule();

        assertTrue(runs.get() >= 1 || awaitAtLeast(runs, 1, 2), "first run happens without delay");
        awaitAtLeast(runs, 3, 5);

        task.cancel();
        assertTrue(task.isCancelled(), "cancel marks the handle");
        int afterCancel = runs.get();
        Thread.sleep(200);
        assertEquals(afterCancel, runs.get(), "no further runs after cancel");
    }

    @Test
    void slowTaskDoesNotOverlapItself() throws Exception {
        AtomicInteger concurrent = new AtomicInteger();
        AtomicInteger maxConcurrent = new AtomicInteger();
        ScheduledTask task = this.scheduler.buildTask(() -> {
            int now = concurrent.incrementAndGet();
            maxConcurrent.accumulateAndGet(now, Math::max);
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                concurrent.decrementAndGet();
            }
        }).repeat(20, TimeUnit.MILLISECONDS).schedule();

        awaitAtLeast(new AtomicInteger(0), 1, 1); // just to structure the wait below
        Thread.sleep(400);
        task.cancel();
        assertEquals(1, maxConcurrent.get(), "fixed-delay semantics never overlap a slow task");
    }

    @Test
    void throwingTaskDoesNotKillTheRepeatingSchedule() throws Exception {
        AtomicInteger runs = new AtomicInteger();
        ScheduledTask task = this.scheduler.buildTask(() -> {
            runs.incrementAndGet();
            if (runs.get() == 1) {
                throw new IllegalStateException("first run explodes");
            }
        }).repeat(50, TimeUnit.MILLISECONDS).schedule();

        assertTrue(awaitAtLeast(runs, 2, 5), "schedule survives the throwing first run");
        task.cancel();
    }

    @Test
    void cancelBeforeFirstRunPreventsExecution() throws Exception {
        CountDownLatch ran = new CountDownLatch(1);
        ScheduledTask task = this.scheduler.buildTask(ran::countDown)
                .delay(500, TimeUnit.MILLISECONDS)
                .schedule();
        assertTrue(task.cancel(), "cancel of a pending task reports success");
        assertFalse(ran.await(300, TimeUnit.MILLISECONDS), "cancelled task must never run");
    }

    @Test
    void cancelTasksPluginAlsoDetachesTimeTasks() throws Exception {
        CountDownLatch ran = new CountDownLatch(1);
        ScheduledTask task = this.scheduler.buildTask(this.plugin, ran::countDown)
                .delay(2, TimeUnit.SECONDS)
                .schedule();

        this.scheduler.cancelTasks(this.plugin);
        assertTrue(task.isCancelled(), "plugin-owned time task is cancelled with its owner");
        assertFalse(ran.await(200, TimeUnit.MILLISECONDS), "cancelled task must never run");
    }

    @Test
    void clearDelayAndClearRepeatResetTheBuilder() throws Exception {
        CountDownLatch ran = new CountDownLatch(1);
        // delay + repeat set, then cleared: must behave as an immediate one-shot
        ScheduledTask task = this.scheduler.buildTask(ran::countDown)
                .delay(10, TimeUnit.SECONDS)
                .repeat(10, TimeUnit.SECONDS)
                .clearDelay()
                .clearRepeat()
                .schedule();

        assertTrue(ran.await(2, TimeUnit.SECONDS), "cleared builder schedules an immediate one-shot");
        task.cancel();
    }

    @Test
    void negativeArgumentsAreRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> this.scheduler.buildTask(() -> {
                }).delay(-1, TimeUnit.MILLISECONDS));
        assertThrows(IllegalArgumentException.class,
                () -> this.scheduler.buildTask(() -> {
                }).repeat(-1, TimeUnit.SECONDS));
        assertThrows(IllegalArgumentException.class,
                () -> this.scheduler.buildTask(() -> {
                }).delay(1, null));
    }

    /** Waits until {@code counter} reaches {@code atLeast}, with a generous timeout. */
    private static boolean awaitAtLeast(AtomicInteger counter, int atLeast, int seconds) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (System.nanoTime() < deadline) {
            if (counter.get() >= atLeast) {
                return true;
            }
            Thread.sleep(20);
        }
        return counter.get() >= atLeast;
    }
}
