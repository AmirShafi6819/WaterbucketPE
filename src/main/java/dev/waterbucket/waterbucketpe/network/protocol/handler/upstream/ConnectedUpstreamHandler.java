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

package dev.waterbucket.waterbucketpe.network.protocol.handler.upstream;

import dev.waterbucket.waterbucketpe.network.connection.ProxiedConnection;
import dev.waterbucket.waterbucketpe.network.connection.client.ClientConnection;
import dev.waterbucket.waterbucketpe.network.protocol.handler.ProxyPacketHandler;
import dev.waterbucket.waterbucketpe.network.protocol.rewrite.RewriteMaps;
import dev.waterbucket.waterbucketpe.network.protocol.rewrite.types.RewriteData;
import lombok.Setter;
import org.cloudburstmc.protocol.bedrock.data.PlayerActionType;
import org.cloudburstmc.protocol.bedrock.netty.BedrockBatchWrapper;
import org.cloudburstmc.protocol.bedrock.packet.*;
import dev.waterbucket.waterbucketpe.ProxyServer;
import dev.waterbucket.waterbucketpe.event.defaults.PlayerChatEvent;
import dev.waterbucket.waterbucketpe.network.protocol.ProtocolVersion;
import dev.waterbucket.waterbucketpe.network.protocol.handler.TransferCallback;
import dev.waterbucket.waterbucketpe.player.ProxiedPlayer;
import dev.waterbucket.waterbucketpe.network.protocol.Signals;
import org.cloudburstmc.protocol.common.PacketSignal;

import static dev.waterbucket.waterbucketpe.network.protocol.user.PlayerRewriteUtils.injectAirSubChunkResponse;
import static dev.waterbucket.waterbucketpe.network.protocol.user.PlayerRewriteUtils.injectRespawnFinished;

/**
 * Main handler for handling packets received from upstream.
 */
public class ConnectedUpstreamHandler extends AbstractUpstreamHandler implements ProxyPacketHandler {

    /*
     * Written by TransferCallback (client event loop) during a transfer phase and read by
     * downstream event loops in sendProxiedBatch; volatile so a batch handed off across loops
     * never observes a stale (null/old) target and silently drops the first packets of the
     * new server.
     */
    @Setter
    private volatile ClientConnection targetConnection;

    public ConnectedUpstreamHandler(ProxiedPlayer player) {
        super(player);
    }

    @Override
    public void sendProxiedBatch(BedrockBatchWrapper batch) {
        if (this.targetConnection != null && this.targetConnection.isConnected()) {
            this.targetConnection.sendPacket(batch.retain());
        }
    }

    @Override
    public final PacketSignal handle(RequestChunkRadiusPacket packet) {
        this.player.getLoginData().setChunkRadius(packet.getRadius());
        this.player.getLoginData().setMaxChunkRadius(packet.getMaxRadius());
        return PacketSignal.UNHANDLED;
    }

    @Override
    public PacketSignal handle(PlayerActionPacket packet) {
        if (packet.getAction() == PlayerActionType.RESPAWN) {
            RewriteData rewriteData = this.player.getRewriteData();
            if (!rewriteData.isProxyRespawn()) {
                return PacketSignal.UNHANDLED;
            }
            // No server saw the death the proxy ended, so finish the respawn like one would
            rewriteData.setProxyRespawn(false);
            injectRespawnFinished(this.player.getConnection(), rewriteData.getEntityId());
            return Signals.CANCEL;
        }
        if (packet.getAction() != PlayerActionType.DIMENSION_CHANGE_SUCCESS) {
            return PacketSignal.UNHANDLED;
        }

        TransferCallback transferCallback = this.player.getRewriteData().getTransferCallback();
        if (transferCallback != null && transferCallback.onDimChangeSuccess()) {
            return Signals.CANCEL;
        }
        return PacketSignal.UNHANDLED;
    }

    @Override
    public PacketSignal handle(RespawnPacket packet) {
        return this.player.getRewriteData().isProxyRespawn() ? Signals.CANCEL : PacketSignal.UNHANDLED;
    }

    @Override
    public PacketSignal handle(SubChunkRequestPacket packet) {
        // Only the fake intermediate chunks (phase 1) are answered by us. On the target hop (phase 2) the new
        // server is wired to the client and provides the real chunks, so its sub-chunk requests must pass through.
        TransferCallback callback = this.player.getRewriteData().getTransferCallback();
        if (callback == null || callback.getPhase() != TransferCallback.TransferPhase.PHASE_1) {
            return PacketSignal.UNHANDLED;
        }
        injectAirSubChunkResponse(this.player.getConnection(), packet, this.player.getProtocol());
        return Signals.CANCEL;
    }

    @Override
    public final PacketSignal handle(TextPacket packet) {
        PlayerChatEvent event = new PlayerChatEvent(this.player, packet.getMessage());
        ProxyServer.getInstance().getEventManager().callEvent(event);

        if (event.isCancelled()) {
            return Signals.CANCEL;
        }

        if (event.isChanged()) {
            packet.setMessage(event.getMessage());
            return PacketSignal.HANDLED;
        }
        return PacketSignal.UNHANDLED;
    }

    @Override
    public final PacketSignal handle(CommandRequestPacket packet) {
        String message = packet.getCommand();
        if (this.player.getProxy().handlePlayerCommand(this.player, message)) {
            return Signals.CANCEL;
        }
        return PacketSignal.UNHANDLED;
    }

    @Override
    public PacketSignal handle(ClientCacheBlobStatusPacket packet) {
        if (this.player.getProtocol().isBefore(ProtocolVersion.MINECRAFT_PE_1_18_30)) {
            this.player.getChunkBlobs().addAll(packet.getNaks());
        }
        return PacketSignal.UNHANDLED;
    }

    @Override
    public PacketSignal handle(ContainerClosePacket packet) {
        if (packet.getId() == -1) {
            // I am not sure if -1 means close all or close the top one
            // Might require validation
            this.player.getOpenContainers().clear();
        } else {
            this.player.getOpenContainers().remove(packet.getId());
        }
        return PacketSignal.UNHANDLED;
    }

    @Override
    public boolean isForceEncode() {
        return false;
    }

    @Override
    public ProxiedConnection getConnection() {
        return this.targetConnection;
    }

    @Override
    public RewriteMaps getRewriteMaps() {
        return this.player.getRewriteMaps();
    }
}
