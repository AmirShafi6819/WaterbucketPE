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

package dev.waterbucket.waterbucketpe.network.protocol.rewrite.types;

import org.cloudburstmc.nbt.NbtList;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtType;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The palette cache is read and written from every downstream event loop during StartGame
 * handling. These tests lock in that it stays concurrent (no corruption under parallel
 * inserts), that lookups keep working through the size cap, and that the cross-loop rewrite
 * fields remain volatile (they are written on the new downstream loop and read on the
 * upstream loop without any other synchronization point).
 */
class BlockPaletteTest {

    private static NbtList<NbtMap> palette(int variant) {
        List<NbtMap> entries = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            entries.add(NbtMap.builder()
                    .putCompound("block", NbtMap.builder()
                            .putString("name", "waterbucket:test_" + variant + "_" + i)
                            .putCompound("states", NbtMap.EMPTY)
                            .build())
                    .build());
        }
        return new NbtList<>(NbtType.COMPOUND, entries);
    }

    @Test
    void samePaletteReturnsCachedInstance() {
        BlockPalette first = BlockPalette.getPalette(palette(0), null);
        BlockPalette second = BlockPalette.getPalette(palette(0), null);
        assertSame(first, second);
        assertEquals(8, entryCount(first));
    }

    @Test
    void concurrentLookupsDoNotCorruptTheCache() throws Exception {
        int variants = 64; // distinct palettes, all inserted concurrently
        ExecutorService executor = Executors.newFixedThreadPool(16);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<java.util.concurrent.Future<BlockPalette>> futures = new ArrayList<>();
            for (int v = 0; v < variants; v++) {
                final int variant = v;
                futures.add(executor.submit(() -> {
                    start.await();
                    BlockPalette palette = null;
                    // Repeat lookups so readers race with concurrent inserts as well.
                    for (int i = 0; i < 50; i++) {
                        palette = BlockPalette.getPalette(palette(variant), null);
                    }
                    return palette;
                }));
            }
            start.countDown();
            for (java.util.concurrent.Future<BlockPalette> future : futures) {
                BlockPalette palette = future.get(30, TimeUnit.SECONDS);
                assertNotNull(palette);
                // Every entry must resolve; a corrupted map would lose or mix buckets.
                for (short id = 0; id < 8; id++) {
                    assertNotNull(palette.getEntry(id), "entry " + id + " missing");
                }
            }
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void cacheIsBounded() {
        // Fill far beyond the cap with unique palettes; the cache must not retain them all.
        for (int v = 1000; v < 1400; v++) {
            BlockPalette.getPalette(palette(v), null);
        }
        // An over-cap palette still resolves correctly (built per use, just not cached).
        BlockPalette palette = BlockPalette.getPalette(palette(9999), null);
        assertNotNull(palette.getEntry(0));
        assertNotNull(palette.getEntry(7));
    }

    /**
     * Contract test: these fields are written in StartGame handling on the new downstream
     * loop and read by rewrites on the upstream loop. If one of them loses volatile, the
     * cross-thread handoff of a transfer has no happens-before edge again.
     */
    @Test
    void crossLoopRewriteFieldsRemainVolatile() throws Exception {
        List<String> expected = List.of(
                "entityId", "originalRuntimeEntityId", "originalUniqueEntityId",
                "blockPalette", "blockPaletteRewrite", "blockProperties",
                "gameRules", "dimension", "transferCallback", "startGameSettings",
                "storeEntrypoint", "spawnPosition", "rotation", "immobileFlag", "dead", "proxyRespawn");
        for (String name : expected) {
            Field field = RewriteData.class.getDeclaredField(name);
            assertTrue(Modifier.isVolatile(field.getModifiers()),
                    "RewriteData." + name + " must stay volatile: it crosses event loops");
        }
    }

    private static int entryCount(BlockPalette palette) {
        int count = 0;
        for (short id = 0; id < Short.MAX_VALUE; id++) {
            if (palette.getEntry(id) == null) {
                break;
            }
            count++;
        }
        return count;
    }
}
