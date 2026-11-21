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

package dev.waterbucket.waterbucketpe.transfer;

import dev.waterbucket.waterbucketpe.network.connection.codec.batch.BatchFlags;
import dev.waterbucket.waterbucketpe.network.connection.codec.server.PacketQueueHandler;
import dev.waterbucket.waterbucketpe.network.connection.peer.BedrockServerSession;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import org.cloudburstmc.protocol.bedrock.netty.BedrockBatchWrapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

public class PacketQueueHandlerTest {

    private BedrockServerSession session;
    private PacketQueueHandler handler;
    private EmbeddedChannel channel;

    @BeforeEach
    void setUp() {
        this.session = mock(BedrockServerSession.class);
        this.handler = new PacketQueueHandler(this.session);
        this.channel = new EmbeddedChannel();
        this.channel.pipeline().addLast(PacketQueueHandler.NAME, this.handler);
    }

    @Test
    void queuesBatchesInsteadOfWritingThem() {
        BedrockBatchWrapper batch = BedrockBatchWrapper.newInstance();
        this.channel.writeOutbound(batch);

        assertNull(this.channel.readOutbound(), "batch should be held in the queue");
        assertEquals(1, batch.refCnt());
    }

    @Test
    void flushesQueueOnRemovalWhileActive() {
        BedrockBatchWrapper batch = BedrockBatchWrapper.newInstance();
        this.channel.writeOutbound(batch);

        this.channel.pipeline().remove(PacketQueueHandler.NAME);

        assertSame(batch, this.channel.readOutbound(), "queued batch should be flushed to the client");
    }

    @Test
    void dropsQueueOnRemovalWhenDiscarded() {
        BedrockBatchWrapper batch = BedrockBatchWrapper.newInstance();
        this.channel.writeOutbound(batch);

        this.handler.dropQueued();
        this.channel.pipeline().remove(PacketQueueHandler.NAME);

        assertNull(this.channel.readOutbound(), "discarded batch must never reach the client");
        assertEquals(0, batch.refCnt(), "discarded batch should be released");
    }

    @Test
    void skipQueueFlagBypassesQueue() {
        BedrockBatchWrapper batch = BedrockBatchWrapper.newInstance();
        batch.setFlag(BatchFlags.SKIP_QUEUE);
        this.channel.writeOutbound(batch);

        assertSame(batch, this.channel.readOutbound(), "flagged batch should pass through immediately");
    }

    @Test
    void dropsQueueWhenChannelGoesInactive() {
        BedrockBatchWrapper batch = BedrockBatchWrapper.newInstance();
        this.channel.writeOutbound(batch);

        this.channel.close();

        assertNull(this.channel.readOutbound());
        assertEquals(0, batch.refCnt());
    }

    @Test
    void overflowDisconnectsSession() {
        // MAX_BATCHES is 256, but netty clamps MPSC queues to a minimum capacity of 2048.
        // The overflow now correctly FAILS the write promise, which EmbeddedChannel.writeOutbound
        // surfaces as an exception; the session must still be disconnected afterwards.
        for (int i = 0; i < 3000 && this.channel.pipeline().get(PacketQueueHandler.NAME) != null; i++) {
            try {
                this.channel.writeOutbound(BedrockBatchWrapper.newInstance());
            } catch (IllegalStateException expected) {
                // promise failed with "Transfer queue got too large"
            }
        }
        verify(this.session, atLeastOnce()).disconnect(any(CharSequence.class));
    }

    @Test
    void overflowReleasesTheRejectedBatchAndFailsItsPromise() {
        // Drives the offer-rejected overflow path: the batch that the queue refused was neither
        // queued nor written, so the old code leaked its pooled buffers and never completed the
        // write promise.
        List<BedrockBatchWrapper> batches = new ArrayList<>();
        List<ChannelPromise> promises = new ArrayList<>();
        int written = 0;
        while (written < 4000 && this.channel.pipeline().get(PacketQueueHandler.NAME) != null) {
            BedrockBatchWrapper batch = BedrockBatchWrapper.newInstance();
            ChannelPromise promise = this.channel.newPromise();
            this.channel.pipeline().write(batch, promise);
            batches.add(batch);
            promises.add(promise);
            written++;
        }

        int failed = 0;
        for (int i = 0; i < promises.size(); i++) {
            ChannelPromise promise = promises.get(i);
            if (!promise.isSuccess()) {
                failed++;
                assertEquals(0, batches.get(i).refCnt(),
                        "the rejected batch must be released, not leaked");
                assertTrue(promise.isDone(), "the failed write promise must be completed");
            }
        }
        assertTrue(failed >= 1, "the overflow must fail at least one write promise");
        verify(this.session, atLeastOnce()).disconnect(any(CharSequence.class));
    }
}