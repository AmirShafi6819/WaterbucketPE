/*
 * Copyright 2022 WaterbucketTEAM
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

package dev.waterbucket.waterbucketpe.network.connection.codec.batch;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.CompositeByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToMessageEncoder;
import org.cloudburstmc.netty.channel.TransportChannel;
import org.cloudburstmc.protocol.bedrock.netty.BedrockBatchWrapper;
import org.cloudburstmc.protocol.bedrock.netty.BedrockPacketWrapper;
import org.cloudburstmc.protocol.common.util.VarInts;

import java.util.List;

public class BedrockBatchEncoder extends MessageToMessageEncoder<BedrockBatchWrapper> {
    public static final String NAME = "bedrock-batch-encoder";

    @Override
    protected void encode(ChannelHandlerContext ctx, BedrockBatchWrapper msg, List<Object> out) {
        List<BedrockPacketWrapper> packets = msg.getPackets();
        // A packet the bridge re-encoded (HANDLED) whose encode failed in BedrockPacketCodec has
        // a null packetBuffer. encodedLength/concat would throw on it and the resulting
        // exceptionCaught kicked the whole peer AND IP-blocked the client for one bad packet.
        // Drop exactly those wrappers from the batch and let the rest travel.
        for (BedrockPacketWrapper packet : packets) {
            if (packet.getPacketBuffer() == null) {
                packets.removeIf(broken -> broken.getPacketBuffer() == null);
                break;
            }
        }

        if (!msg.isModified() && (msg.getCompressed() != null || msg.getUncompressed() != null)) {
            out.add(msg.retain());
            return;
        }

        int maxBatchSize = maxBatchSize(ctx);
        int start = 0;
        int size = 0;
        for (int i = 0; i < packets.size(); i++) {
            int length = encodedLength(packets.get(i));
            if (i > start && size + length > maxBatchSize) {
                out.add(split(ctx, msg, packets.subList(start, i)));
                start = i;
                size = 0;
            }
            size += length;
        }

        if (start == 0) {
            msg.setUncompressed(concat(ctx, packets));
            out.add(msg.retain());
        } else {
            out.add(split(ctx, msg, packets.subList(start, packets.size())));
        }
    }

    /**
     * Encoded bytes a batch may hold before the remaining packets go out in another, or {@link Integer#MAX_VALUE} for
     * no limit. A batch travels as one message, which may be no larger than the transport allows, so this leaves a
     * little room for compression and encryption to grow it. A larger single packet still goes alone.
     */
    private static int maxBatchSize(ChannelHandlerContext ctx) {
        if (!(ctx.channel() instanceof TransportChannel channel)) {
            return Integer.MAX_VALUE;
        }
        int maxMessageSize = channel.maxMessageSize();
        return maxMessageSize - maxMessageSize / 64;
    }

    // A batch of its own for some of the packets, keeping the flags of the batch they came in
    private static BedrockBatchWrapper split(ChannelHandlerContext ctx, BedrockBatchWrapper msg,
                                             List<BedrockPacketWrapper> packets) {
        BedrockBatchWrapper batch = BedrockBatchWrapper.newInstance();
        for (BedrockPacketWrapper packet : packets) {
            batch.addPacket(packet.retain());
        }
        batch.getFlags().addAll(msg.getFlags());
        batch.setUncompressed(concat(ctx, packets));
        return batch;
    }

    private static ByteBuf concat(ChannelHandlerContext ctx, List<BedrockPacketWrapper> packets) {
        // An all-unencodable batch arrives here with zero packets: maxNumComponents must stay >= 1.
        CompositeByteBuf buf = ctx.alloc().compositeDirectBuffer(Math.max(1, packets.size() * 2));
        try {
            for (BedrockPacketWrapper packet : packets) {
                ByteBuf message = packet.getPacketBuffer();
                ByteBuf header = ctx.alloc().ioBuffer(5);
                VarInts.writeUnsignedInt(header, message.readableBytes());
                buf.addComponent(true, header);
                buf.addComponent(true, message.retainedSlice());
            }
            return buf.retain();
        } finally {
            buf.release();
        }
    }

    private static int encodedLength(BedrockPacketWrapper packet) {
        ByteBuf message = packet.getPacketBuffer();
        if (message == null) {
            throw new IllegalArgumentException("BedrockPacket is not encoded");
        }
        return VarInts.sizeOfUnsignedInt(message.readableBytes()) + message.readableBytes();
    }
}
