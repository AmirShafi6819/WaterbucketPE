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

package dev.waterbucket.waterbucketpe.network.protocol.rewrite.types;

import dev.waterbucket.waterbucketpe.ProxyServer;
import dev.waterbucket.waterbucketpe.network.protocol.handler.TransferCallback;
import lombok.Getter;
import lombok.Setter;
import dev.waterbucket.waterbucketpe.network.protocol.user.PlayerRewriteUtils;
import org.cloudburstmc.math.vector.Vector2f;
import org.cloudburstmc.math.vector.Vector3f;
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodecHelper;
import org.cloudburstmc.protocol.bedrock.data.BlockPropertyData;
import org.cloudburstmc.protocol.bedrock.data.ClientStoreEntrypointConfiguration;
import org.cloudburstmc.protocol.bedrock.data.GameRuleData;
import org.cloudburstmc.protocol.common.PacketSignal;

import java.util.List;
import java.util.function.LongConsumer;

/**
 * Rewrite data of a present player.
 * Holds both the client-known entityId and the downstream-known clientId.
 * Important when interacting with packets, as different packet targets might want different entityIds.
 */
@Setter
public class RewriteData {

    /*
     * Everything from here down to immobileFlag is written once per server join/switch in
     * StartGame handling on the new downstream event loop, while rewrites of client->server
     * packets read it on the upstream loop and other loops. Plain fields had no happens-before
     * edge between those threads (the volatile transferCallback write happens before these
     * updates, not after), so readers could observe stale entity ids or dimensions. Writes are
     * once per switch, so volatile is free for the relay hot path.
     */
    /**
     * The original entityId known to the client
     */
    @Getter
    private volatile long entityId;
    /**
     * The downstream-known runtime entityId
     */
    @Getter
    private volatile long originalRuntimeEntityId;
    /**
     * The downstream-known unique entityId, BDS keeps it apart from the runtime one
     */
    @Getter
    private volatile long originalUniqueEntityId;

    @Getter
    private volatile BlockPalette blockPalette;
    @Getter
    private volatile BlockPaletteRewrite blockPaletteRewrite;
    @Getter
    private volatile List<BlockPropertyData> blockProperties;

    /**
     * A list of GameRules currently known to the client.
     */
    @Getter
    private volatile List<GameRuleData<?>> gameRules;
    /**
     * The dimensionId the player is currently in
     */
    @Getter
    private volatile int dimension = 0;
    @Getter
    private volatile TransferCallback transferCallback;
    /**
     * Captured from the first StartGamePacket: settings the client can not change without a reconnect.
     */
    @Getter
    private volatile StartGameSettings startGameSettings;
    /**
     * The store entrypoint currently known to the client.
     */
    @Getter
    private volatile ClientStoreEntrypointConfiguration storeEntrypoint;

    @Getter
    private volatile Vector3f spawnPosition;
    @Getter
    private volatile Vector2f rotation;
    /**
     * Whether the client is on the death screen of the current server
     */
    @Getter
    private volatile boolean dead;
    /**
     * Set while the client answers a death screen the proxy ended during a transfer
     */
    @Getter
    private volatile boolean proxyRespawn;
    /**
     * Server known value of immobile flag
     * Actually applied value may be different during server transfer
     */
    private volatile boolean immobileFlag;

    /**
     * The name that is shown up in the player list (or pause menu)
     */
    @Getter
    private String proxyName;

    @Getter
    private BedrockCodecHelper codecHelper;

    public RewriteData() {
        this.proxyName = ProxyServer.getInstance().getConfiguration().getName();
    }

    /**
     * Atomically claims the transfer slot. Downstream connections run on different event loops,
     * so two of them can reach START_GAME concurrently and only one may win.
     *
     * @return false if another transfer is still in progress.
     */
    public synchronized boolean trySetTransferCallback(TransferCallback callback) {
        if (this.transferCallback != null && this.transferCallback.getPhase() != TransferCallback.TransferPhase.RESET) {
            return false;
        }
        this.transferCallback = callback;
        return true;
    }

    /**
     * Clears the transfer slot only if it is still owned by the given callback.
     */
    public synchronized void clearTransferCallback(TransferCallback callback) {
        if (this.transferCallback == callback) {
            this.transferCallback = null;
        }
    }

    public synchronized void setTransferCallback(TransferCallback callback) {
        this.transferCallback = callback;
    }

    public boolean hasImmobileFlag() {
        return this.immobileFlag;
    }

    public PacketSignal rewriteEntityId(long from, LongConsumer setter) {
        return rewrite(from, PlayerRewriteUtils.rewriteId(from, getEntityId(), getOriginalRuntimeEntityId()), setter);
    }

    public PacketSignal rewriteUniqueEntityId(long from, LongConsumer setter) {
        return rewrite(from, this.rewriteUniqueId(from), setter);
    }

    public long rewriteUniqueId(long from) {
        return PlayerRewriteUtils.rewriteId(from, getEntityId(), getOriginalUniqueEntityId());
    }

    private static PacketSignal rewrite(long from, long rewriteId, LongConsumer setter) {
        if (rewriteId == from) {
            return PacketSignal.UNHANDLED;
        }
        setter.accept(rewriteId);
        return PacketSignal.HANDLED;
    }

    /**
     * @deprecated use {@link #setOriginalRuntimeEntityId(long)}
     */
    @Deprecated
    public void setOriginalEntityId(long originalRuntimeEntityId) {
        this.originalRuntimeEntityId = originalRuntimeEntityId;
    }

    /**
     * @deprecated use {@link #getOriginalRuntimeEntityId()}
     */
    @Deprecated
    public long getOriginalEntityId() {
        return originalRuntimeEntityId;
    }
}
