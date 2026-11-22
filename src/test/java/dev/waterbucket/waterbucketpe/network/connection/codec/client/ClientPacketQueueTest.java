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

package dev.waterbucket.waterbucketpe.network.connection.codec.client;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.embedded.EmbeddedChannel;
import org.cloudburstmc.protocol.bedrock.netty.BedrockBatchWrapper;
import org.cloudburstmc.protocol.bedrock.netty.BedrockPacketWrapper;
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket;
import org.cloudburstmc.protocol.bedrock.packet.SetTitlePacket;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The queue replaces the fixed 50 ms flush timer with a short adaptive drain: single packets
 * must leave within the short drain delay (transfers used to pay up to 50 ms per handshake
 * packet), a burst that arrives together must still leave as one batch, queued singles must
 * precede a directly written batch, and the queue must be bounded.
 */
class ClientPacketQueueTest {

    /**
     * Past the new 5 ms drain delay but well before the old fixed 50 ms tick, so these tests
     * distinguish the adaptive drain from the previous behavior.
     */
    private static final long DRAIN_PASSED_MS = 6;

    private static BedrockPacket packet() {
        SetTitlePacket packet = new SetTitlePacket();
        packet.setText("test");
        return packet;
    }

    @Test
    void singlePacketFlushesWithinTheShortDrainDelay() {
        EmbeddedChannel channel = new EmbeddedChannel(new ClientPacketQueue());

        channel.writeOutbound(packet());
        // The old fixed 50 ms timer would not be due yet at 6 ms.
        channel.advanceTimeBy(DRAIN_PASSED_MS, TimeUnit.MILLISECONDS);
        channel.runScheduledPendingTasks();

        Object out = channel.readOutbound();
        assertTrue(out instanceof BedrockBatchWrapper,
                "the queued packet must flush within the short drain delay");
        assertEquals(1, ((BedrockBatchWrapper) out).getPackets().size());
        assertTrue(channel.isOpen());
        channel.finish();
    }

    @Test
    void burstWithinTheDrainDelayLeavesAsOneBatch() {
        EmbeddedChannel channel = new EmbeddedChannel(new ClientPacketQueue());

        channel.writeOutbound(packet());
        channel.writeOutbound(packet());
        channel.writeOutbound(packet());

        channel.advanceTimeBy(DRAIN_PASSED_MS, TimeUnit.MILLISECONDS);
        channel.runScheduledPendingTasks();
        BedrockBatchWrapper batch = (BedrockBatchWrapper) channel.readOutbound();
        assertEquals(3, batch.getPackets().size(), "packets written together must batch together");

        // A second drain must not duplicate anything.
        channel.advanceTimeBy(DRAIN_PASSED_MS, TimeUnit.MILLISECONDS);
        channel.runScheduledPendingTasks();
        assertNull(channel.readOutbound());
        channel.finish();
    }

    @Test
    void queuedSinglesLeaveBeforeADirectlyWrittenBatch() {
        EmbeddedChannel channel = new EmbeddedChannel(new ClientPacketQueue());

        channel.writeOutbound(packet());

        BedrockBatchWrapper direct = BedrockBatchWrapper.newInstance();
        BedrockPacketWrapper wrapper = BedrockPacketWrapper.create();
        wrapper.setPacketBuffer(Unpooled.buffer(4).writeZero(4));
        direct.addPacket(wrapper);
        channel.writeOutbound(direct);

        channel.advanceTimeBy(DRAIN_PASSED_MS, TimeUnit.MILLISECONDS);
        channel.runScheduledPendingTasks();
        BedrockBatchWrapper first = (BedrockBatchWrapper) channel.readOutbound();
        assertEquals(1, first.getPackets().size(), "the queued single must leave first");
        BedrockBatchWrapper second = (BedrockBatchWrapper) channel.readOutbound();
        assertEquals(1, second.getPackets().size());
        assertTrue(second.getPackets().get(0) == wrapper, "the direct batch must follow untouched");
        channel.finish();
    }

    @Test
    void queueOverflowsInsteadOfGrowingWithoutBound() {
        // Suppress the time-based drain so the queue can actually reach its bound regardless
        // of wall-clock pauses; the bound and overflow behavior are what this test inspects.
        ClientPacketQueue handler = new ClientPacketQueue() {
            @Override
            void scheduleDrain(ChannelHandlerContext ctx) {
            }
        };
        EmbeddedChannel channel = new EmbeddedChannel(handler);

        boolean rejected = false;
        for (int i = 0; i < 6000 && !rejected; i++) {
            try {
                channel.writeOutbound(packet());
                rejected = !channel.isOpen();
            } catch (Exception e) {
                // The overflow failure surfaced through the outbound promise.
                rejected = true;
            }
        }
        assertTrue(rejected, "the queue must reject instead of growing forever");

        // Whatever was queued before the rejection must still be releasable.
        int total = 0;
        Object out;
        while ((out = channel.readOutbound()) != null) {
            BedrockBatchWrapper batch = (BedrockBatchWrapper) out;
            total += batch.getPackets().size();
            batch.release();
        }
        assertTrue(total <= 4096, "at most the capped queue may be buffered, saw " + total);
        channel.finish();
    }

    @Test
    void closeReleasesQueuedPacketsWithoutWritingThem() throws Exception {
        ClientPacketQueue handler = new ClientPacketQueue();
        EmbeddedChannel channel = new EmbeddedChannel(handler);
        channel.writeOneOutbound(packet()).awaitUninterruptibly();

        handler.channelInactive(channel.pipeline().firstContext());
        assertNull(channel.readOutbound(), "nothing may be written after the channel went inactive");
        channel.finish();
    }
}
