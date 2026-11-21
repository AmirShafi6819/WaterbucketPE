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

package dev.waterbucket.waterbucketpe.network.connection.codec.initializer;

import dev.waterbucket.waterbucketpe.ProxyServer;
import dev.waterbucket.waterbucketpe.network.connection.TransportProfile;
import dev.waterbucket.waterbucketpe.network.connection.codec.batch.BedrockBatchDecoder;
import dev.waterbucket.waterbucketpe.network.connection.codec.batch.BedrockBatchEncoder;
import dev.waterbucket.waterbucketpe.network.connection.codec.batch.NetherNetFrameCodec;
import dev.waterbucket.waterbucketpe.network.connection.codec.batch.RakNetFrameCodec;
import dev.waterbucket.waterbucketpe.network.connection.codec.batch.TransportFrameCodec;
import dev.waterbucket.waterbucketpe.network.connection.codec.compression.ProxiedCompressionCodec;
import dev.waterbucket.waterbucketpe.network.connection.codec.packet.BedrockPacketCodec;
import dev.waterbucket.waterbucketpe.network.connection.codec.packet.BedrockPacketCodec_v1;
import dev.waterbucket.waterbucketpe.network.connection.codec.packet.BedrockPacketCodec_v2;
import dev.waterbucket.waterbucketpe.network.connection.codec.packet.BedrockPacketCodec_v3;
import dev.waterbucket.waterbucketpe.network.connection.peer.ProxiedBedrockPeer;
import io.netty.channel.*;
import io.netty.handler.flush.FlushConsolidationHandler;
import lombok.AllArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.cloudburstmc.netty.channel.raknet.config.RakChannelOption;
import org.cloudburstmc.protocol.bedrock.BedrockPeer;
import org.cloudburstmc.protocol.bedrock.BedrockSession;
import org.cloudburstmc.protocol.bedrock.data.CompressionAlgorithm;
import org.cloudburstmc.protocol.bedrock.data.PacketCompressionAlgorithm;
import org.cloudburstmc.protocol.bedrock.netty.codec.compression.*;
import org.cloudburstmc.protocol.common.util.Zlib;

@Log4j2
@AllArgsConstructor
public abstract class ProxiedSessionInitializer<T extends BedrockSession> extends ChannelInitializer<Channel> {
    public static final RakNetFrameCodec RAKNET_FRAME_CODEC = new RakNetFrameCodec();
    public static final NetherNetFrameCodec NETHERNET_FRAME_CODEC = new NetherNetFrameCodec();

    /**
     * NetherNet batching, compression and packet framing follow the same rules as RakNet
     * protocol 11.
     */
    public static final int NETHERNET_CODEC_VERSION = 11;
    public static final BedrockBatchDecoder BATCH_DECODER = new BedrockBatchDecoder();

    /**
     * Fallback zlib level for the legacy plugin-facing strategy lookup.
     */
    public static final int DEFAULT_ZLIB_LEVEL = 6;

    /**
     * Explicit flush count for {@link FlushConsolidationHandler}, matching Velocity's tuning.
     */
    public static final int FLUSH_CONSOLIDATION_FLUSH_COUNT = 256;

    public static final CompressionStrategy ZLIB_RAW_STRATEGY = new SimpleCompressionStrategy(new ZlibCompression(Zlib.RAW));
    public static final CompressionStrategy ZLIB_STRATEGY = new SimpleCompressionStrategy(new ZlibCompression(Zlib.DEFAULT));
    public static final CompressionStrategy SNAPPY_STRATEGY = new SimpleCompressionStrategy(new SnappyCompression());
    public static final CompressionStrategy NOOP_STRATEGY = new SimpleCompressionStrategy(new NoopCompression());

    /**
     * Creates a strategy with its own {@link ZlibCompression} instance at the given level.
     * The upstream and downstream legs need different levels (upstream_compression vs
     * downstream_compression); sharing the static instances forced one global level on both.
     * The deflate/inflate codecs live in FastThreadLocals inside the shared {@link Zlib}, so
     * a per-connection wrapper is allocation-cheap and stays off the hot path.
     */
    public static CompressionStrategy zlibStrategy(boolean raw, int level) {
        ZlibCompression compression = new ZlibCompression(raw ? Zlib.RAW : Zlib.DEFAULT);
        compression.setLevel(level);
        return new SimpleCompressionStrategy(compression);
    }

    protected final ProxyServer proxy;

    @Override
    protected void initChannel(Channel channel) {
        TransportProfile profile = this.getTransportProfile(channel);
        int rakVersion = profile.codecVersion();

        channel.pipeline()
                // Consolidate the many small flushes of the relay path the same way Velocity does:
                // one syscall per read burst instead of one per batch
                .addLast("flush-consolidation", new FlushConsolidationHandler(FLUSH_CONSOLIDATION_FLUSH_COUNT, true))
                .addLast(TransportFrameCodec.NAME, this.getFrameCodec())
                .addLast(CompressionCodec.NAME, new ProxiedCompressionCodec(this.getCompressionStrategy(rakVersion, true), false))
                .addLast(BedrockBatchDecoder.NAME, BATCH_DECODER)
                .addLast(BedrockBatchEncoder.NAME, new BedrockBatchEncoder())
                .addLast(BedrockPacketCodec.NAME, getPacketCodec(rakVersion))
                .addLast(BedrockPeer.NAME, new ProxiedBedrockPeer(channel, this::createSession, proxy, profile));
    }

    /**
     * Compression strategy for this leg. Both legs used to share static instances, which forced
     * the upstream level onto every connection; each leg now gets its own level.
     */
    protected abstract CompressionStrategy getCompressionStrategy(int rakVersion, boolean initial);

    /**
     * Framing ahead of the compression codec. Registered under {@link TransportFrameCodec#NAME} whatever
     * the transport is, so everything above it can be shared.
     */
    protected ChannelHandler getFrameCodec() {
        return RAKNET_FRAME_CODEC;
    }

    protected TransportProfile getTransportProfile(Channel channel) {
        return TransportProfile.raknet(channel.config().getOption(RakChannelOption.RAK_PROTOCOL_VERSION));
    }

    protected final T createSession(BedrockPeer peer, int subClientId) {
        T session = this.createSession0(peer, subClientId);
        this.initSession(session);
        return session;
    }

    protected abstract T createSession0(BedrockPeer peer, int subClientId);

    protected abstract void initSession(T session);

    public static BedrockPacketCodec getPacketCodec(int rakVersion) {
        return switch (rakVersion) {
            case 7 -> new BedrockPacketCodec_v1();
            case 8 -> new BedrockPacketCodec_v2();
            case 9, 10, 11 -> new BedrockPacketCodec_v3();
            default -> throw new UnsupportedOperationException("Unsupported RakNet protocol version: " + rakVersion);
        };
    }

    public static CompressionStrategy getCompressionStrategy(CompressionAlgorithm algorithm, int rakVersion, boolean initial) {
        return switch (rakVersion) {
            case 7, 8, 9 -> ZLIB_STRATEGY;
            case 10 -> ZLIB_RAW_STRATEGY;
            case 11 -> initial ? NOOP_STRATEGY : getCompressionStrategy(algorithm, DEFAULT_ZLIB_LEVEL);
            default -> throw new UnsupportedOperationException("Unsupported RakNet protocol version: " + rakVersion);
        };
    }

    /**
     * Same as {@link #getCompressionStrategy(CompressionAlgorithm, int, boolean)} but with the
     * zlib level of the connection's leg applied to freshly created zlib strategies.
     */
    public static CompressionStrategy getCompressionStrategy(CompressionAlgorithm algorithm, int rakVersion, boolean initial, int zlibLevel) {
        return switch (rakVersion) {
            case 7, 8, 9 -> zlibStrategy(false, zlibLevel);
            case 10 -> zlibStrategy(true, zlibLevel);
            case 11 -> initial ? NOOP_STRATEGY : getCompressionStrategy(algorithm, zlibLevel);
            default -> throw new UnsupportedOperationException("Unsupported RakNet protocol version: " + rakVersion);
        };
    }

    private static CompressionStrategy getCompressionStrategy(CompressionAlgorithm algorithm, int zlibLevel) {
        if (algorithm == PacketCompressionAlgorithm.ZLIB) {
            return zlibStrategy(true, zlibLevel);
        } else if (algorithm == PacketCompressionAlgorithm.SNAPPY) {
            return SNAPPY_STRATEGY;
        } if (algorithm == PacketCompressionAlgorithm.NONE) {
            return NOOP_STRATEGY;
        } else {
            throw new UnsupportedOperationException("Unsupported compression algorithm: " + algorithm + " Maybe you want to use CompressionStrategy instead?");
        }
    }
}
