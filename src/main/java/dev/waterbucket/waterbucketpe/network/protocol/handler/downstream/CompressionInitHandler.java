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
import dev.waterbucket.waterbucketpe.network.connection.codec.compression.CompressionType;
import dev.waterbucket.waterbucketpe.network.protocol.Signals;
import dev.waterbucket.waterbucketpe.player.ProxiedPlayer;
import org.cloudburstmc.protocol.bedrock.netty.BedrockBatchWrapper;
import org.cloudburstmc.protocol.bedrock.packet.*;
import org.cloudburstmc.protocol.common.PacketSignal;

public class CompressionInitHandler extends AbstractDownstreamHandler {

    private final BedrockPacketHandler nextHandler;

    public CompressionInitHandler(ProxiedPlayer player, ClientConnection connection, BedrockPacketHandler nextHandler) {
        super(player, connection);
        this.nextHandler = nextHandler;

        RequestNetworkSettingsPacket packet = new RequestNetworkSettingsPacket();
        packet.setProtocolVersion(player.getProtocol().getProtocol());
        connection.sendPacket(packet);
    }

    @Override
    public void sendProxiedBatch(BedrockBatchWrapper batch) {
        // Noop. We are not forwarding anything yet
    }

    @Override
    public PacketSignal handle(NetworkSettingsPacket packet) {
        CompressionType compression = CompressionType.fromBedrockCompression(packet.getCompressionAlgorithm());
        this.connection.setCompression(compression);
        this.connection.setPacketHandler(nextHandler);
        this.connection.sendPacket(this.player.getLoginData().getLoginPacket(this.connection.getServerInfo()));
        return Signals.CANCEL;
    }

    // The server checks the protocol on RequestNetworkSettings and refuses before NetworkSettings, so
    // the refusal goes to the handler that knows whether this is the first join or a transfer
    @Override
    public PacketSignal handle(PlayStatusPacket packet) {
        return this.nextHandler.handle(packet);
    }

    @Override
    public PacketSignal handle(DisconnectPacket packet) {
        return this.nextHandler.handle(packet);
    }
}
