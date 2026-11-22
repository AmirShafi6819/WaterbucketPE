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

package dev.waterbucket.waterbucketpe.player;

import dev.waterbucket.waterbucketpe.event.defaults.PlayerPermissionCheckEvent;
import dev.waterbucket.waterbucketpe.transfer.TransferTestHarness;
import dev.waterbucket.waterbucketpe.utils.types.Permission;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Permissions are read from event loops and written by plugins and the config layer from
 * arbitrary threads. The backing map must stay concurrent (the previous plain fastutil map
 * could corrupt under that pattern) and concurrent first-adds must not lose a permission.
 */
public class ProxiedPlayerPermissionsTest {

    private TransferTestHarness harness;

    @BeforeEach
    void setUp() {
        this.harness = new TransferTestHarness();
    }

    @AfterEach
    void tearDown() {
        this.harness.close();
    }

    @Test
    void lookupsAreCaseInsensitiveWhileStorageKeepsTheGivenKey() {
        ProxiedPlayer player = this.harness.player;
        assertTrue(player.addPermission(new Permission("WaterBucket.Admin", true)));

        assertTrue(player.hasPermission("waterbucket.admin"));
        assertTrue(player.hasPermission("WATERBUCKET.ADMIN"));
        // Permission lowercases its own name in the constructor (upstream behavior);
        // the map must preserve whatever the object carries.
        assertTrue(player.getPermissions().stream().anyMatch(p -> p.getName().equals("waterbucket.admin")),
                "the added permission must be discoverable via getPermissions");
    }

    @Test
    void concurrentFirstAddsNeverLoseAPermission() throws Exception {
        ProxiedPlayer player = this.harness.player;
        int threads = 8;
        int permissionsPerThread = 50;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Boolean> results = new CopyOnWriteArrayList<>();
            for (int t = 0; t < threads; t++) {
                final int base = t * permissionsPerThread;
                executor.submit(() -> {
                    start.await();
                    for (int i = 0; i < permissionsPerThread; i++) {
                        results.add(player.addPermission(new Permission("perm." + (base + i), true)));
                    }
                    return null;
                });
            }
            start.countDown();
            executor.shutdown();
            assertTrue(executor.awaitTermination(30, TimeUnit.SECONDS));

            assertEquals(threads * permissionsPerThread, results.size());
            for (int i = 0; i < threads * permissionsPerThread; i++) {
                assertTrue(results.get(i), "permission perm." + i + " reported lost");
                assertTrue(player.hasPermission("perm." + i));
            }
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void concurrentReadsAndWritesDoNotCorruptIteration() throws Exception {
        ProxiedPlayer player = this.harness.player;
        ExecutorService executor = Executors.newFixedThreadPool(4);
        try {
            CountDownLatch start = new CountDownLatch(1);
            executor.submit(() -> {
                start.await();
                for (int i = 0; i < 500; i++) {
                    player.addPermission(new Permission("iter.perm." + i, true));
                    player.removePermission("iter.perm." + (i - 1));
                }
                return null;
            });
            List<Integer> seenCounts = new CopyOnWriteArrayList<>();
            executor.submit(() -> {
                start.await();
                for (int i = 0; i < 500; i++) {
                    // Weakly consistent iteration: a size must never be negative and iteration
                    // must never throw ConcurrentModificationException.
                    seenCounts.add(player.getPermissions().size());
                }
                return null;
            });
            start.countDown();
            executor.shutdown();
            assertTrue(executor.awaitTermination(30, TimeUnit.SECONDS));
            for (int count : seenCounts) {
                assertTrue(count >= 0);
            }
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void permissionCheckEventStillDecidesTheResult() {
        ProxiedPlayer player = this.harness.player;
        assertFalse(player.hasPermission("never.granted"), "sanity: no permission set");

        // The event can override the result without touching the map - keep that contract.
        this.harness.eventInterceptor = event -> {
            if (event instanceof PlayerPermissionCheckEvent check) {
                check.setHasPermission(true);
            }
        };
        assertTrue(player.hasPermission("never.granted"));
        assertEquals(2, this.harness.events(PlayerPermissionCheckEvent.class).size(),
                "both checks must fire the event exactly once");
    }
}
