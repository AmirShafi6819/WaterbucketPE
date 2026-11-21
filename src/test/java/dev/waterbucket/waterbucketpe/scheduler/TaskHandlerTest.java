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

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers task rescheduling decisions and the cancellation flag that async tasks
 * share between the tick thread and the executor threads.
 */
public class TaskHandlerTest {

    @Test
    void nonRepeatingTaskDoesNotReschedule() {
        TaskHandler<Runnable> handler = new TaskHandler<>(() -> { }, 1, false);
        handler.setPeriod(0);
        assertFalse(handler.calculateNextTick(100));
    }

    @Test
    void repeatingTaskReschedulesByItsPeriod() {
        TaskHandler<Runnable> handler = new TaskHandler<>(() -> { }, 2, false);
        handler.setPeriod(20);
        assertTrue(handler.calculateNextTick(100));
        assertEquals(120, handler.getNextRunTick());
    }

    @Test
    void cancelledTaskDoesNotReschedule() {
        TaskHandler<Runnable> handler = new TaskHandler<>(() -> { }, 3, false);
        handler.setPeriod(5);
        handler.cancel();
        assertFalse(handler.calculateNextTick(100));
    }

    @Test
    void cancelIsIdempotentAndVisibleAcrossThreads() throws InterruptedException {
        AtomicBoolean ran = new AtomicBoolean(false);
        TaskHandler<Runnable> handler = new TaskHandler<>(() -> ran.set(true), 4, true);

        Thread canceller = new Thread(handler::cancel);
        canceller.start();
        canceller.join();

        assertTrue(handler.isCancelled());
        handler.cancel(); // second cancel must not throw or re-run anything
        assertFalse(ran.get());
    }

    @Test
    void onRunExecutesTheTaskAndSwallowTaskExceptionsDoNotPropagate() {
        AtomicInteger counter = new AtomicInteger();
        TaskHandler<Runnable> handler = new TaskHandler<>(counter::incrementAndGet, 5, false);
        handler.onRun(1);
        assertEquals(1, counter.get());

        TaskHandler<Runnable> failing = new TaskHandler<>(() -> { throw new IllegalStateException("boom"); }, 6, false);
        failing.onRun(1); // must not throw
    }
}
