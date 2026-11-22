/*
 * Copyright 2023 WaterbucketTEAM
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

import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.internal.PlatformDependent;
import lombok.extern.log4j.Log4j2;
import org.cloudburstmc.protocol.bedrock.netty.BedrockBatchWrapper;
import org.cloudburstmc.protocol.bedrock.netty.BedrockPacketWrapper;
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket;

import java.util.Queue;
import java.util.concurrent.TimeUnit;

/**
 * Batches the many single packets the proxy sends during a downstream handshake and a
 * server transfer so they leave as one batch instead of one write per packet.
 * <p>
 * The drain is scheduled a few milliseconds after the first packet is queued instead of
 * running on a fixed 50 ms timer: the old fixed tick added up to 50 ms of latency to every
 * handshake packet (hundreds of milliseconds across a transfer) and woke every idle
 * downstream connection 20 times per second. Batching is preserved - packets that arrive
 * within the drain delay still leave as one batch - and idle connections are never woken.
 */
@Log4j2
public class ClientPacketQueue extends ChannelDuplexHandler {
    public static final String NAME = "client-packet-queue";

    /**
     * A batch travels as one message and most transfers queue a handful of packets; 4096
     * single packets can only be reached by a misbehaving plugin, and the previous unbounded
     * queue let that grow the proxy's memory without limit.
     */
    private static final int MAX_QUEUED_PACKETS = 4096;
    /**
     * How long a queued packet may wait for company before the batch goes out. Short enough
     * to keep transfers responsive, long enough to batch a read burst.
     */
    private static final long DRAIN_DELAY_MILLIS = 5;

    private final Queue<BedrockPacketWrapper> packetQueue = PlatformDependent.newMpscQueue(MAX_QUEUED_PACKETS);
    /**
     * Only touched on the channel's event loop: every write and every drain runs there
     * (off-loop writes are scheduled onto the loop by Netty before reaching this handler).
     */
    private boolean drainScheduled;
    private boolean closed;

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        this.closed = true;
        // Release queued wrappers (retained in write) so they don't leak on close.
        BedrockPacketWrapper packet;
        while ((packet = this.packetQueue.poll()) != null) {
            ReferenceCountUtil.safeRelease(packet);
        }
        super.channelInactive(ctx);
    }

    // Package-private so tests can suppress the time-based drain and inspect the bound.
    void scheduleDrain(ChannelHandlerContext ctx) {
        if (this.drainScheduled || this.closed) {
            return;
        }
        this.drainScheduled = true;
        ctx.channel().eventLoop().schedule(() -> this.drain(ctx), DRAIN_DELAY_MILLIS, TimeUnit.MILLISECONDS);
    }

    private void drain(ChannelHandlerContext ctx) {
        this.drainScheduled = false;
        if (this.closed || this.packetQueue.isEmpty()) {
            return;
        }
        this.flushQueue(ctx);
        // A packet enqueued while the batch was being built waits for the next short drain.
        if (!this.packetQueue.isEmpty()) {
            this.scheduleDrain(ctx);
        }
    }

    private void flushQueue(ChannelHandlerContext ctx) {
        BedrockBatchWrapper batch = BedrockBatchWrapper.newInstance();

        BedrockPacketWrapper packet;
        while ((packet = this.packetQueue.poll()) != null) {
            batch.getPackets().add(packet);
        }
        ctx.writeAndFlush(batch);
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
        if (this.closed) {
            // Queue won't drain again; let Netty fail+release rather than enqueue (would leak).
            ctx.write(msg, promise);
            return;
        }
        if (msg instanceof BedrockPacket packet) {
            if (this.packetQueue.offer(BedrockPacketWrapper.create(0, 0, 0, ReferenceCountUtil.retain(packet), null))) {
                this.scheduleDrain(ctx);
                promise.trySuccess();
            } else {
                this.onQueueOverflow(ctx, packet, promise);
            }
        } else if (msg instanceof BedrockPacketWrapper packet) {
            if (this.packetQueue.offer(ReferenceCountUtil.retain(packet))) {
                this.scheduleDrain(ctx);
                promise.trySuccess();
            } else {
                this.onQueueOverflow(ctx, packet, promise);
            }
        } else if (msg instanceof BedrockBatchWrapper) {
            // Keep ordering: whatever was queued ahead of this batch leaves first.
            this.flushQueue(ctx);
            ctx.write(msg, promise);
        } else {
            ctx.write(msg, promise);
        }
    }

    private void onQueueOverflow(ChannelHandlerContext ctx, Object packet, ChannelPromise promise) {
        ReferenceCountUtil.safeRelease(packet);
        log.warn("[{}] has reached maximum packet queue capacity: packets={}",
                ctx.channel().remoteAddress(), this.packetQueue.size());
        promise.tryFailure(new IllegalStateException("Packet queue got too large"));
        // The queue holds proxy-generated protocol packets; a connection that lost one can not
        // recover, so drop the downstream and let the normal failure handling take over.
        ctx.channel().close();
    }
}
