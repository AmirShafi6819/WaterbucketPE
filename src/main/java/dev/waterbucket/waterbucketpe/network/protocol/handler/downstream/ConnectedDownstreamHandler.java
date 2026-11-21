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

package dev.waterbucket.waterbucketpe.network.protocol.handler.downstream;

import dev.waterbucket.waterbucketpe.network.connection.client.ClientConnection;
import dev.waterbucket.waterbucketpe.network.connection.handler.ReconnectReason;
import dev.waterbucket.waterbucketpe.network.protocol.handler.PluginPacketHandler;
import org.cloudburstmc.protocol.bedrock.PacketDirection;
import org.cloudburstmc.protocol.bedrock.packet.*;
import dev.waterbucket.waterbucketpe.event.defaults.FastTransferRequestEvent;
import dev.waterbucket.waterbucketpe.event.defaults.PostTransferCompleteEvent;
import dev.waterbucket.waterbucketpe.network.protocol.rewrite.types.RewriteData;
import dev.waterbucket.waterbucketpe.network.serverinfo.ServerInfo;
import dev.waterbucket.waterbucketpe.player.ProxiedPlayer;
import dev.waterbucket.waterbucketpe.network.protocol.Signals;
import org.cloudburstmc.protocol.common.PacketSignal;

import dev.waterbucket.waterbucketpe.network.protocol.ProtocolVersion;

import static dev.waterbucket.waterbucketpe.network.protocol.Signals.mergeSignals;
import static dev.waterbucket.waterbucketpe.network.protocol.user.PlayerRewriteUtils.injectEntityImmobile;
import static dev.waterbucket.waterbucketpe.network.protocol.user.PlayerRewriteUtils.injectInputLocks;

public class ConnectedDownstreamHandler extends AbstractDownstreamHandler {

    public ConnectedDownstreamHandler(ProxiedPlayer player, ClientConnection connection) {
        super(player, connection);
    }

    @Override
    public PacketSignal handlePacket(BedrockPacket packet) {
        PacketSignal signal = super.handlePacket(packet);
        if (!player.getPluginPacketHandlers().isEmpty()) {
            for (PluginPacketHandler handler : this.player.getPluginPacketHandlers()) {
                signal = mergeSignals(signal, handler.handlePacket(packet, PacketDirection.CLIENT_BOUND));
            }
        }
        return signal;
    }

    @Override
    public PacketSignal handle(LevelChunkPacket packet) {
        // Remember whether this server serves chunks via the sub-chunk request system so injected
        // empty chunks match it and the client keeps requesting sub-chunks instead of breaking.
        this.player.setSubChunkRequestMode(packet.isRequestSubChunks());
        return PacketSignal.UNHANDLED;
    }

    @Override
    public PacketSignal handle(ServerStoreInfoPacket packet) {
        this.player.getRewriteData().setStoreEntrypoint(packet.getStore());
        return PacketSignal.UNHANDLED;
    }

    @Override
    public PacketSignal handle(TransferPacket packet) {
        if (!this.player.getProxy().getConfiguration().useFastTransfer()) {
            return PacketSignal.UNHANDLED;
        }

        // A malformed transfer (null/blank address, invalid port) must never throw out of the
        // packet handler: it would kill the downstream session. Let it pass through instead.
        String address = packet.getAddress();
        int port = packet.getPort();
        if (address == null || address.trim().isEmpty() || port <= 0 || port > 65535) {
            return PacketSignal.UNHANDLED;
        }

        ServerInfo serverInfo = this.player.getProxy().getServerInfo(address);
        if (serverInfo == null) {
            serverInfo = this.player.getProxy().getServerInfo(address, port);
        }

        FastTransferRequestEvent event = new FastTransferRequestEvent(serverInfo, this.player, packet.getAddress(), packet.getPort());
        this.player.getProxy().getEventManager().callEvent(event);

        if (!event.isCancelled() && event.getServerInfo() != null) {
            this.player.connect(event.getServerInfo());
            return Signals.CANCEL;
        }
        return PacketSignal.UNHANDLED;
    }

    @Override
    public final PacketSignal handle(DisconnectPacket packet) {
        // This is not really a failure
        this.player.onDownstreamFailure(this.connection, ReconnectReason.SERVER_KICK, packet.getKickMessage());
        return Signals.CANCEL;
    }
}
