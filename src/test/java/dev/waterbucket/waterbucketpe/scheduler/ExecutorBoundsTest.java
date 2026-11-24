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
import dev.waterbucket.waterbucketpe.event.EventManager;
import dev.waterbucket.waterbucketpe.logger.MainLogger;
import dev.waterbucket.waterbucketpe.utils.config.proxy.ProxyConfig;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ThreadPoolExecutor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The stability contract behind the bounded executors: a fixed resident core, a small bounded
 * elastic head-room and a large bounded queue - instead of the old unbounded-per-burst pools
 * whose thread count (and therefore RAM) bounced up and down with every burst.
 */
class ExecutorBoundsTest {

    @Test
    void schedulerExecutorRecipeIsBounded() {
        ThreadPoolExecutor executor = WaterbucketScheduler.buildBoundedExecutor("ExecutorBoundsTest - #%d", 4);
        try {
            assertEquals(4, executor.getCorePoolSize(), "resident threads = idleThreads");
            assertEquals(8, executor.getMaximumPoolSize(), "elastic head-room = 2x core, never Integer.MAX_VALUE");
            assertTrue(executor.getQueue().remainingCapacity() >= 8192,
                    "bursts queue up instead of spawning unbounded threads");
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void eventManagerExecutorFollowsTheSameRecipe() {
        ProxyServer proxy = mock(ProxyServer.class);
        ProxyConfig config = mock(ProxyConfig.class);
        when(proxy.getConfiguration()).thenReturn(config);
        when(config.getIdleThreads()).thenReturn(4);
        EventManager manager = new EventManager(proxy);

        java.util.concurrent.ExecutorService executor = manager.getThreadedExecutor();
        assertTrue(executor instanceof ThreadPoolExecutor, "event dispatch must use a ThreadPoolExecutor");
        ThreadPoolExecutor pool = (ThreadPoolExecutor) executor;
        assertEquals(4, pool.getCorePoolSize());
        assertEquals(8, pool.getMaximumPoolSize());
    }
}
