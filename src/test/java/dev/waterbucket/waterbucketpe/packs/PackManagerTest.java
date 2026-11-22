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

package dev.waterbucket.waterbucketpe.packs;

import dev.waterbucket.waterbucketpe.ProxyServer;
import dev.waterbucket.waterbucketpe.packs.types.PackedVersion;
import dev.waterbucket.waterbucketpe.packs.types.ResourcePack;
import org.cloudburstmc.protocol.bedrock.packet.ResourcePackChunkDataPacket;
import org.cloudburstmc.protocol.bedrock.packet.ResourcePackChunkRequestPacket;
import org.cloudburstmc.protocol.bedrock.packet.ResourcePackDataInfoPacket;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PackManagerTest {

    private static final int PACK_SIZE = 250_000;

    @Test
    void splitsPacksIntoBdsSizedChunks() {
        PackManager packManager = new PackManager(mock(ProxyServer.class, RETURNS_DEEP_STUBS));
        UUID id = UUID.randomUUID();
        ResourcePack pack = mock(ResourcePack.class, RETURNS_DEEP_STUBS);
        when(pack.getPackManifest().validate()).thenReturn(true);
        when(pack.getPackId()).thenReturn(id);
        when(pack.getVersion()).thenReturn(new PackedVersion(1, 0, 0));
        when(pack.getType()).thenReturn(ResourcePack.TYPE_RESOURCES);
        when(pack.getContentKey()).thenReturn("");
        when(pack.getPackSize()).thenReturn((long) PACK_SIZE);
        when(pack.getChunk(anyInt(), anyInt())).thenAnswer(inv ->
                new byte[Math.min(PACK_SIZE - inv.<Integer>getArgument(0), inv.<Integer>getArgument(1))]);
        packManager.registerPack(pack);

        ResourcePackDataInfoPacket info = packManager.packInfoFromIdVer(id + "_1.0.0");
        assertEquals(102_400, info.getMaxChunkSize());
        assertEquals(3, info.getChunkCount());

        ResourcePackChunkRequestPacket request = new ResourcePackChunkRequestPacket();
        request.setPackId(id);
        request.setPackVersion("1.0.0");
        request.setChunkIndex(2);
        ResourcePackChunkDataPacket last = packManager.packChunkDataPacket(id + "_1.0.0", request);
        assertEquals(204_800, last.getProgress());
        assertEquals(45_200, last.getData().readableBytes());
    }

    @Test
    void outOfRangeChunkIndexIsRejectedWithoutThrowing() {
        // A chunk index validated against a larger offered pack (mid-download reload) used to
        // produce a negative array size inside getChunk and kill the event loop.
        PackManager packManager = new PackManager(mock(ProxyServer.class, RETURNS_DEEP_STUBS));
        UUID id = UUID.randomUUID();
        ResourcePack pack = mock(ResourcePack.class, RETURNS_DEEP_STUBS);
        when(pack.getPackManifest().validate()).thenReturn(true);
        when(pack.getPackId()).thenReturn(id);
        when(pack.getVersion()).thenReturn(new PackedVersion(1, 0, 0));
        when(pack.getType()).thenReturn(ResourcePack.TYPE_RESOURCES);
        when(pack.getContentKey()).thenReturn("");
        when(pack.getPackSize()).thenReturn((long) PACK_SIZE);
        packManager.registerPack(pack);

        ResourcePackChunkRequestPacket request = new ResourcePackChunkRequestPacket();
        request.setPackId(id);
        request.setPackVersion("1.0.0");
        request.setChunkIndex(99);
        assertNull(packManager.packChunkDataPacket(id + "_1.0.0", request));

        request.setChunkIndex(-1);
        assertNull(packManager.packChunkDataPacket(id + "_1.0.0", request));
    }

    @Test
    void registerPackRejectsAMissingManifest() {
        // registerPack used to NPE inside getPackManifest().validate() for a pack whose
        // manifest never loaded; the documented answer is an IllegalArgumentException.
        PackManager packManager = new PackManager(mock(ProxyServer.class, RETURNS_DEEP_STUBS));
        ResourcePack pack = mock(ResourcePack.class);
        when(pack.getPackManifest()).thenReturn(null);

        assertThrows(IllegalArgumentException.class, () -> packManager.registerPack(pack));
    }
}
