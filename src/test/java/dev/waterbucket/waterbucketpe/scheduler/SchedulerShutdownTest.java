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
import dev.waterbucket.waterbucketpe.logger.MainLogger;
import dev.waterbucket.waterbucketpe.utils.ThreadFactoryBuilder;
import dev.waterbucket.waterbucketpe.utils.config.proxy.ProxyConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Regression tests for the shutdown behavior of the scheduler: a hung async task must be
 * interrupted after the grace period, the task registries must be drained, and the internal
 * threads must be daemons so they can never block JVM exit on their own.
 */
class SchedulerShutdownTest {

    private static Field singletonField;

    private ProxyServer proxy;
    private WaterbucketScheduler scheduler;

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
    }

    @AfterEach
    void tearDown() throws Exception {
        if (this.scheduler != null) {
            this.scheduler.shutdown();
        }
        singletonField.set(null, null);
    }

    @Test
    void shutdownInterruptsHungAsyncTasksAndReturns() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch observedInterrupt = new CountDownLatch(1);
        AtomicReference<Throwable> surprise = new AtomicReference<>();
        // A task that blocks forever unless interrupted by shutdownNow()
        this.scheduler.scheduleAsync(() -> {
            started.countDown();
            try {
                new CountDownLatch(1).await();
            } catch (InterruptedException e) {
                observedInterrupt.countDown();
            } catch (Throwable t) {
                surprise.set(t);
            }
        });
        // Async tasks are tick-driven: dispatch them from a manual tick
        this.scheduler.onTick(0);
        assertTrue(started.await(5, TimeUnit.SECONDS), "task must start before shutdown");

        long start = System.nanoTime();
        assertDoesNotThrow(() -> this.scheduler.shutdown());
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        // Grace period is 25 x 100 ms; shutdown must return shortly after it, not hang forever
        assertTrue(elapsedMs < 10_000, "shutdown must not hang: took " + elapsedMs + " ms");
        assertTrue(observedInterrupt.await(5, TimeUnit.SECONDS),
                "a hung task must be interrupted by shutdownNow after the grace period");
        assertEquals(null, surprise.get(), "task must fail only with InterruptedException");
    }

    @Test
    void shutdownDrainsTaskRegistriesAndStopsTasks() {
        AtomicInteger runs = new AtomicInteger();
        this.scheduler.scheduleRepeating(runs::incrementAndGet, 1, false);
        TaskHandler<?> delayed = this.scheduler.scheduleDelayed(() -> { }, 50, false);

        // Drive the tick loop manually: the repeating task runs and reschedules
        this.scheduler.onTick(1);
        assertEquals(1, runs.get(), "repeating task must run on its tick");
        this.scheduler.onTick(2);
        assertEquals(2, runs.get(), "repeating task must run again");

        this.scheduler.shutdown();

        assertEquals(null, this.scheduler.taskHandlerFor(delayed.getTaskId()),
                "the task registry must be drained on shutdown");
        this.scheduler.onTick(3);
        assertEquals(2, runs.get(), "no task may run anymore after shutdown");
    }

    @Test
    void internalExecutorThreadsAreDaemons() throws Exception {
        CountDownLatch spawned = new CountDownLatch(1);
        AtomicReference<Boolean> isDaemon = new AtomicReference<>();
        this.scheduler.scheduleAsync(() -> {
            isDaemon.set(Thread.currentThread().isDaemon());
            spawned.countDown();
        });
        this.scheduler.onTick(0);
        assertTrue(spawned.await(5, TimeUnit.SECONDS), "async task must run");
        assertTrue(Boolean.TRUE.equals(isDaemon.get()), "scheduler executor threads must be daemons");
    }

    @Test
    void threadFactoryDefaultsToNonDaemon() {
        ThreadFactoryBuilder builder = ThreadFactoryBuilder.builder().build();
        Thread thread = builder.newThread(() -> { });
        assertFalse(thread.isDaemon(), "an unconfigured builder must keep producing non-daemon threads");
    }
}
