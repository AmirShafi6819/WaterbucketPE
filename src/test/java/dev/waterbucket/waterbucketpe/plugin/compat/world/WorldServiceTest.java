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

package dev.waterbucket.waterbucketpe.plugin.compat.world;

import dev.waterbucket.waterbucketpe.network.goruntime.BridgeCodec;
import dev.waterbucket.waterbucketpe.network.goruntime.GoRuntimeManager;
import dev.waterbucket.waterbucketpe.utils.config.proxy.WorldViewSettings;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The world query surface must degrade, never explode: with the Go runtime
 * absent every query answers "unloaded"/empty and plugins keep working.
 */
class WorldServiceTest {

    @TempDir
    Path tempDir;

    private GoRuntimeManager offlineRuntime() {
        WorldViewSettings settings = new WorldViewSettings();
        settings.enable(false);
        settings.goRuntime(false);
        settings.goPluginsDir(tempDir.resolve("plugins-go").toString());
        GoRuntimeManager runtime = new GoRuntimeManager(null, settings);
        assertFalse(runtime.isConnected());
        return runtime;
    }

    @Test
    void offlineBlockQueryAnswersUnloaded() {
        WorldService service = new WorldService(offlineRuntime());
        BlockView block = service.getBlock("lobby", 0, new BlockPos(1, 64, 2));
        assertNotNull(block);
        assertFalse(block.isLoaded());
        assertEquals("minecraft:unloaded", block.name());
    }

    @Test
    void offlineQueriesStayEmpty() {
        WorldService service = new WorldService(offlineRuntime());
        assertFalse(service.isChunkLoaded("lobby", 0, 0, 0));
        assertTrue(service.listWorlds().isEmpty());
        assertNull(service.worldMeta("lobby"));
        assertNull(service.getEntity("lobby", 1));
        assertNull(service.getPlayer("lobby", 1));
        assertNull(service.getPlayerByName("Steve"));
        assertTrue(service.getEntitiesNear("lobby", 0, 0, 0, 0, 10, 10).isEmpty());
        assertEquals(0, service.getInventory("lobby", 1, 12).size());
        assertTrue(service.getChunk("lobby", 0, 0, 0).blockEntities().isEmpty());
    }

    @Test
    void playerTrackingFeedsLookups() {
        WorldService service = new WorldService(offlineRuntime());
        service.trackPlayer("lobby", 42, "Steve");
        WorldService.TrackedPlayer tracked = service.trackedPlayer("Steve");
        assertNotNull(tracked);
        assertEquals("lobby", tracked.serverKey());
        assertEquals(42L, tracked.eid());
        service.untrackPlayer("Steve");
        assertNull(service.trackedPlayer("Steve"));
    }

    @Test
    void itemViewsParseNbtTrees() {
        ItemView item = new ItemView("minecraft:diamond_sword", 0, 1, 5L,
                java.util.Map.of("display", java.util.Map.of("Name", "§bBlade")));
        assertEquals("§bBlade", item.customName());
        assertFalse(item.isAir());
        assertEquals("minecraft:diamond_sword", item.name());
        assertTrue(ItemView.AIR.isAir());
        assertEquals(0, item.enchantments().size());
    }

    @Test
    void signTextReadsFrontAndBackSides() {
        BlockEntityView sign = new BlockEntityView("minecraft:sign", new BlockPos(0, 64, 0),
                java.util.Map.of(
                        "FrontText", java.util.Map.of("Text", "hello"),
                        "BackText", java.util.Map.of("Text", "bye")));
        assertEquals(java.util.List.of("hello", "bye"), sign.signText());

        BlockEntityView legacy = new BlockEntityView("minecraft:sign", new BlockPos(0, 64, 0),
                java.util.Map.of("Text", "old"));
        assertEquals(java.util.List.of("old"), legacy.signText());
    }

    @Test
    void chunkViewPacksPositionsStable() {
        long packed = ChunkView.packPos(-5, 64, 300);
        assertEquals(packed, ChunkView.packPos(-5, 64, 300));
        assertEquals(BridgeCodec.QUERY_GET_BLOCK, 1);
    }
}
