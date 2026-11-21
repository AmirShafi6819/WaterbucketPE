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

package dev.waterbucket.waterbucketpe.network.protocol.rewrite;

import dev.waterbucket.waterbucketpe.transfer.TransferTestHarness;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.cloudburstmc.protocol.bedrock.packet.LevelChunkPacket;
import org.cloudburstmc.protocol.common.PacketSignal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Verifies the ByteBuf ownership of the legacy chunk rewriter: the packet's own data buffer
 * must stay owned by the packet on the rewrite-failure path, and swap cleanly to the rewritten
 * buffer on success.
 */
class BlockMapOwnershipTest {

    private TransferTestHarness harness;

    @BeforeEach
    void setUp() {
        this.harness = new TransferTestHarness();
    }

    @AfterEach
    void tearDown() {
        this.harness.close();
    }

    private BlockMap newBlockMap() {
        return new BlockMap(this.harness.player);
    }

    private LevelChunkPacket chunkPacket(byte[] payload) {
        LevelChunkPacket packet = new LevelChunkPacket();
        packet.setSubChunksLength(1); // one subchunk so the version byte is actually read
        packet.setData(Unpooled.wrappedBuffer(payload));
        return packet;
    }

    @Test
    void failurePathKeepsThePacketBufferOwnedByThePacket() {
        BlockMap blockMap = this.newBlockMap();
        LevelChunkPacket packet = this.chunkPacket(new byte[]{42, 0, 0}); // unknown subchunk format
        ByteBuf data = packet.getData();

        PacketSignal signal = blockMap.handle(packet);

        assertEquals(PacketSignal.UNHANDLED, signal, "an unknown subchunk format must be unhandled");
        assertEquals(1, data.refCnt(),
                "the packet's own buffer must remain owned by the packet when the rewrite failed");
        // The packet is still fully releasable (no dangling reference into freed memory)
        packet.release();
        assertEquals(0, data.refCnt());
    }

    @Test
    void successPathSwapsOwnershipCleanly() {
        BlockMap blockMap = this.newBlockMap();
        LevelChunkPacket packet = this.chunkPacket(new byte[]{0, 1, 2, 3, 4, 5, 6, 7}); // legacy format
        ByteBuf data = packet.getData();

        PacketSignal signal = blockMap.handle(packet);

        assertEquals(PacketSignal.HANDLED, signal);
        assertNotSame(data, packet.getData(), "the packet must reference the rewritten buffer");
        assertEquals(0, data.refCnt(), "the old buffer must be released once after the swap");
        assertEquals(1, packet.getData().refCnt(), "the packet owns exactly one reference to the new buffer");
        packet.release();
        assertEquals(0, packet.getData().refCnt());
    }

    @Test
    void doubleReleaseAfterFailureIsImpossible() {
        BlockMap blockMap = this.newBlockMap();
        LevelChunkPacket packet = this.chunkPacket(new byte[]{99});

        blockMap.handle(packet);
        packet.release();
        // A second release used to be possible through the dangling reference; releasing the
        // packet again must now throw instead of corrupting a buffer nobody owns.
        assertThrows(Throwable.class, packet::release);
    }
}
