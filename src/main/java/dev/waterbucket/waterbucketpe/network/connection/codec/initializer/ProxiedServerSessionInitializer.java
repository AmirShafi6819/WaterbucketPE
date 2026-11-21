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
import dev.waterbucket.waterbucketpe.network.NetworkMetrics;
import dev.waterbucket.waterbucketpe.network.connection.codec.server.ServerErrorHandler;
import dev.waterbucket.waterbucketpe.network.connection.peer.BedrockServerSession;
import dev.waterbucket.waterbucketpe.network.connection.peer.ProxiedBedrockPeer;
import dev.waterbucket.waterbucketpe.network.protocol.handler.upstream.LoginUpstreamHandler;
import io.netty.channel.Channel;
import org.cloudburstmc.netty.channel.raknet.RakChannel;
import org.cloudburstmc.netty.channel.TransportDisconnectReason;
import org.cloudburstmc.netty.channel.raknet.config.RakChannelMetrics;
import org.cloudburstmc.netty.channel.raknet.config.RakChannelOption;
import org.cloudburstmc.netty.handler.codec.raknet.common.RakSessionCodec;
import org.cloudburstmc.protocol.bedrock.BedrockPeer;
import org.cloudburstmc.protocol.bedrock.netty.codec.compression.CompressionStrategy;
import org.cloudburstmc.protocol.bedrock.PacketDirection;

public class ProxiedServerSessionInitializer extends ProxiedSessionInitializer<BedrockServerSession> {

    public ProxiedServerSessionInitializer(ProxyServer proxy) {
        super(proxy);
    }

    @Override
    protected CompressionStrategy getCompressionStrategy(int rakVersion, boolean initial) {
        // Upstream leg: zlib level from upstream_compression
        return ProxiedSessionInitializer.getCompressionStrategy(this.proxy.getConfiguration().getCompression(),
                rakVersion, initial, this.proxy.getConfiguration().getUpstreamCompression());
    }

    @Override
    protected void initChannel(Channel channel) {
        if (!this.proxy.getSecurityManager().onConnectionCreated(channel.remoteAddress())) {
            this.proxy.getLogger().info("[" + channel.remoteAddress() + "] <-> Connection request denied");
            disconnect(channel, TransportDisconnectReason.DISCONNECTED);
            return;
        }

        channel.attr(PacketDirection.ATTRIBUTE).set(PacketDirection.CLIENT_BOUND);

        NetworkMetrics metrics = this.proxy.getNetworkMetrics();
        if (metrics != null) {
            channel.attr(NetworkMetrics.ATTRIBUTE).set(metrics);
        }
        RakChannelMetrics rakMetrics = metrics == null ? null : metrics.rakMetrics(NetworkMetrics.Leg.UPSTREAM);
        if (rakMetrics != null) {
            channel.config().setOption(RakChannelOption.RAK_METRICS, rakMetrics);
        }
        channel.config().setOption(RakChannelOption.RAK_AUTO_FLUSH, false);

        super.initChannel(channel);

        channel.pipeline().addLast(ServerErrorHandler.NAME, new ServerErrorHandler.Child(proxy));
    }

    @Override
    protected BedrockServerSession createSession0(BedrockPeer peer, int subClientId) {
        this.proxy.getLogger().debug("[" + peer.getSocketAddress() + "] <-> Received first data");
        return new BedrockServerSession((ProxiedBedrockPeer) peer, subClientId);
    }

    @Override
    protected void initSession(BedrockServerSession session) {
        session.setPacketHandler(new LoginUpstreamHandler(this.proxy, session));
    }

    private static void disconnect(Channel channel, TransportDisconnectReason reason) {
        if (channel instanceof RakChannel) {
            ((RakChannel) channel).rakPipeline().get(RakSessionCodec.class).disconnect(reason);
        } else {
            channel.disconnect();
        }
    }
}
