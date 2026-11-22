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

package dev.waterbucket.waterbucketpe.network.protocol.registry;

import org.cloudburstmc.protocol.bedrock.data.definitions.BlockDefinition;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Both legs of one player share the codec helper and therefore this registry; the plain
 * fastutil map was mutated concurrently by two event loops and raced its own resize.
 */
class FakeDefinitionRegistryConcurrencyTest {

    @Test
    void concurrentLookupsPopulateTheMapWithoutCorruption() throws Exception {
        FakeDefinitionRegistry<BlockDefinition> registry = FakeDefinitionRegistry.createBlockRegistry();
        int threads = 8;
        int lookupsPerThread = 5_000;
        int distinctIds = 64;

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (int t = 0; t < threads; t++) {
                futures.add(pool.submit(() -> {
                    try {
                        start.await();
                    } catch (InterruptedException ignored) {
                        return;
                    }
                    for (int i = 0; i < lookupsPerThread; i++) {
                        int runtimeId = (i * 31) % distinctIds;
                        BlockDefinition definition = registry.getDefinition(runtimeId);
                        assertSame(definition, registry.getDefinition(runtimeId),
                                "the same runtime id must map to one cached definition");
                        assertTrue(registry.isRegistered(definition));
                    }
                }));
            }
            start.countDown();
            for (Future<?> future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertEquals(distinctIds, registry.getRuntimeMap().size(),
                "every distinct id cached exactly once, no lost or duplicated entries");
    }
}
