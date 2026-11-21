package dev.waterbucket.waterbucketpe.network.connection.codec.server;

import dev.waterbucket.waterbucketpe.network.NetworkMetrics;
import dev.waterbucket.waterbucketpe.network.connection.codec.batch.BatchFlags;
import dev.waterbucket.waterbucketpe.network.connection.peer.BedrockServerSession;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.util.internal.PlatformDependent;
import lombok.extern.log4j.Log4j2;
import org.cloudburstmc.protocol.bedrock.netty.BedrockBatchWrapper;

import java.util.Queue;

@Log4j2
public class PacketQueueHandler extends ChannelDuplexHandler {
    public static final String NAME = "packet-queue-handler";
    private static final int MAX_BATCHES = 256;
    private static final int MAX_PACKETS = 8000;

    private final BedrockServerSession session;

    private int packetCounter = 0;
    private final Queue<BedrockBatchWrapper> queue = PlatformDependent.newMpscQueue(MAX_BATCHES);

    private volatile boolean finished;
    private volatile boolean dropQueued;

    public PacketQueueHandler(BedrockServerSession session) {
        this.session = session;
    }

    /**
     * Drop queued batches instead of flushing them on removal.
     * Used when the transfer fails and the queue holds packets from the abandoned target server.
     */
    public void dropQueued() {
        this.dropQueued = true;
    }

    private void finish(ChannelHandlerContext ctx, boolean send) {
        if (this.finished) {
            return;
        }
        this.finished = true;

        if (ctx.pipeline().get(NAME) == this) {
            ctx.pipeline().remove(this);
        }

        BedrockBatchWrapper batch;
        while ((batch = this.queue.poll()) != null) {
            if (send) {
                ctx.write(batch);
            } else {
                batch.release();
            }
        }

        if (send) {
            ctx.flush();
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        this.finish(ctx, false);
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) {
        this.finish(ctx, !this.dropQueued && ctx.channel().isActive());
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
        if (this.finished || !(msg instanceof BedrockBatchWrapper batch) || batch.hasFlag(BatchFlags.SKIP_QUEUE)) {
            ctx.write(msg, promise);
            return;
        }

        // MPSC queues must not be mutated from the producer thread beyond offer(), so the
        // two overflow cases are distinguished by the offer result instead of remove():
        boolean offered = this.queue.offer(batch);
        if (offered && this.packetCounter < MAX_PACKETS) {
            this.packetCounter += batch.getPackets().size();
            // The batch is accepted and written later by finish(); complete the promise now so
            // the write contract holds (an unresolved promise made every await() on such a
            // write hang forever).
            promise.trySuccess();
            return;
        }

        log.warn("[{}] has reached maximum transfer queue capacity: batches={} packets={}", this.session.getSocketAddress(), this.queue.size(), this.packetCounter);
        if (!offered) {
            // The offer was rejected (queue full): the batch was neither queued nor written
            // and finish() only sees queue members, so it leaked its pooled buffers.
            batch.release();
        } // else: the batch is in the queue and finish(false) below releases it
        promise.tryFailure(new IllegalStateException("Transfer queue got too large"));
        this.finish(ctx, false);
        this.session.disconnect("Transfer queue got too large");

        NetworkMetrics metrics = ctx.channel().attr(NetworkMetrics.ATTRIBUTE).get();
        if (metrics != null) {
            metrics.packetQueueTooLarge();
        }
    }
}
