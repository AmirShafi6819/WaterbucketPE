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
import dev.waterbucket.waterbucketpe.utils.config.proxy.ProxyConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Regression test for the task finalization path: after a non-repeating task completes, the
 * scheduler used to call {@code cancel()} on it, which fired every {@link CallbackTask}
 * callback a second time - with null instead of the produced result.
 */
class SchedulerFinalizationTest {

    private static Field singletonField;

    private WaterbucketScheduler scheduler;

    @BeforeEach
    void setUp() throws Exception {
        if (singletonField == null) {
            singletonField = WaterbucketScheduler.class.getDeclaredField("instance");
            singletonField.setAccessible(true);
        }
        singletonField.set(null, null);

        ProxyServer proxy = mock(ProxyServer.class);
        ProxyConfig config = mock(ProxyConfig.class);
        when(config.getIdleThreads()).thenReturn(1);
        when(proxy.getConfiguration()).thenReturn(config);
        when(proxy.getLogger()).thenReturn(mock(MainLogger.class));
        this.scheduler = new WaterbucketScheduler(proxy);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (this.scheduler != null) {
            this.scheduler.shutdown();
        }
        singletonField.set(null, null);
    }

    @Test
    void completedCallbackTaskFiresItsCallbackExactlyOnceWithTheResult() {
        List<Object> callbackResults = new CopyOnWriteArrayList<>();
        // scheduleDelayed on the sync path so the task runs inside the manual onTick call
        this.scheduler.scheduleDelayed(new CallbackTask<>(() -> "result", callbackResults::add), 0, false);

        this.scheduler.onTick(1); // dispatch + completion + finalization in one tick

        assertEquals(List.of("result"), callbackResults,
                "callback must fire exactly once, with the produced result");

        // The finalization must not re-deliver anything on later ticks either
        this.scheduler.onTick(2);
        assertEquals(List.of("result"), callbackResults, "finalization must not re-fire the callback");
    }

    @Test
    void cancelledCallbackTaskFiresItsCallbackOnlyThroughTheCancelPath() {
        List<Object> callbackResults = new CopyOnWriteArrayList<>();
        TaskHandler<Task> handler = this.scheduler.scheduleDelayed(
                new CallbackTask<>(() -> "result", callbackResults::add), 5, false);

        // Cancel before the task ever runs: onCancel fires the callback once (with null),
        // and the task must not run afterwards
        handler.cancel();
        for (int tick = 1; tick <= 10; tick++) {
            this.scheduler.onTick(tick);
        }

        assertEquals(java.util.Collections.singletonList(null), callbackResults,
                "a cancelled-before-run task must fire the callback exactly once, with null");
        assertEquals(null, this.scheduler.taskHandlerFor(handler.getTaskId()),
                "a cancelled task must be unregistered");
    }
}
