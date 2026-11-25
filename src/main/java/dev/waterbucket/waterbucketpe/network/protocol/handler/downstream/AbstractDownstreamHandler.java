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

import dev.waterbucket.waterbucketpe.command.Command;
import dev.waterbucket.waterbucketpe.persianfix.PersianFixManager;
import dev.waterbucket.waterbucketpe.utils.config.proxy.PersianFixSettings;
import it.unimi.dsi.fastutil.objects.Object2ObjectMap;
import dev.waterbucket.waterbucketpe.network.connection.client.ClientConnection;
import dev.waterbucket.waterbucketpe.network.protocol.ProtocolVersion;
import dev.waterbucket.waterbucketpe.network.protocol.handler.ProxyPacketHandler;
import dev.waterbucket.waterbucketpe.network.protocol.handler.TransferCallback;
import dev.waterbucket.waterbucketpe.network.protocol.registry.FakeDefinitionRegistry;
import dev.waterbucket.waterbucketpe.network.protocol.rewrite.RewriteMaps;
import dev.waterbucket.waterbucketpe.network.protocol.rewrite.types.RewriteData;
import dev.waterbucket.waterbucketpe.network.protocol.user.PlayerRewriteUtils;
import dev.waterbucket.waterbucketpe.player.ProxiedPlayer;
import dev.waterbucket.waterbucketpe.network.protocol.Signals;
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodecHelper;
import org.cloudburstmc.protocol.bedrock.data.AttributeData;
import org.cloudburstmc.protocol.bedrock.data.ScoreInfo;
import org.cloudburstmc.protocol.bedrock.data.camera.CameraPreset;
import org.cloudburstmc.protocol.bedrock.data.command.CommandData;
import org.cloudburstmc.protocol.bedrock.data.command.CommandEnumConstraint;
import org.cloudburstmc.protocol.bedrock.data.command.CommandEnumData;
import org.cloudburstmc.protocol.bedrock.data.definitions.ItemDefinition;
import org.cloudburstmc.protocol.bedrock.data.definitions.SimpleNamedDefinition;
import org.cloudburstmc.protocol.bedrock.data.entity.EntityDataMap;
import org.cloudburstmc.protocol.bedrock.data.entity.EntityFlag;
import org.cloudburstmc.protocol.bedrock.netty.BedrockBatchWrapper;
import org.cloudburstmc.protocol.bedrock.packet.*;
import org.cloudburstmc.protocol.common.NamedDefinition;
import org.cloudburstmc.protocol.common.PacketSignal;
import org.cloudburstmc.protocol.common.SimpleDefinitionRegistry;

import java.util.*;
import java.util.function.Consumer;

import static dev.waterbucket.waterbucketpe.network.protocol.Signals.mergeSignals;

public abstract class AbstractDownstreamHandler implements ProxyPacketHandler {

    protected final ClientConnection connection;
    protected final ProxiedPlayer player;
    private boolean refused;

    public AbstractDownstreamHandler(ProxiedPlayer player, ClientConnection connection) {
        this.player = player;
        this.connection = connection;
    }

    @Override
    public PacketSignal handle(PlayStatusPacket packet) {
        if (packet.getStatus() != PlayStatusPacket.Status.PLAYER_SPAWN) {
            return PacketSignal.UNHANDLED;
        }
        TransferCallback transferCallback = player.getRewriteData().getTransferCallback();
        if (transferCallback != null && transferCallback.getConnection() == this.connection) {
            transferCallback.onPlayStatus();
        }
        return PacketSignal.UNHANDLED;
    }

    @Override
    public PacketSignal handle(UpdateAttributesPacket packet) {
        if (packet.getRuntimeEntityId() != this.player.getRewriteData().getOriginalRuntimeEntityId()) {
            return PacketSignal.UNHANDLED;
        }
        for (AttributeData attribute : packet.getAttributes()) {
            if (attribute.getName().equals("minecraft:health")) {
                this.trackDeath(attribute.getValue() <= 0);
            }
        }
        return PacketSignal.UNHANDLED;
    }

    @Override
    public PacketSignal handle(SetHealthPacket packet) {
        // BDS and PowerNukkitX send this on every spawn and respawn
        this.trackDeath(packet.getHealth() <= 0);
        return PacketSignal.UNHANDLED;
    }

    @Override
    public PacketSignal handle(RespawnPacket packet) {
        // Geyser only sends health 0, a Nukkit kill() only SERVER_SEARCHING
        if (packet.getState() == RespawnPacket.State.SERVER_SEARCHING) {
            this.trackDeath(true);
        } else if (packet.getState() == RespawnPacket.State.SERVER_READY) {
            this.trackDeath(false);
            // BDS and Geyser put a placeholder in StartGame, the real spawn only comes with the join handshake
            TransferCallback transferCallback = this.player.getRewriteData().getTransferCallback();
            if (transferCallback != null && transferCallback.getConnection() == this.connection) {
                this.player.getRewriteData().setSpawnPosition(packet.getPosition());
            }
        }
        return PacketSignal.UNHANDLED;
    }

    private void trackDeath(boolean dead) {
        if (this.connection != this.player.getDownstreamConnection()) {
            return;
        }
        RewriteData rewriteData = this.player.getRewriteData();
        rewriteData.setDead(dead);
        // A new death once the transfer settled outdates a respawn the proxy started
        if (dead && rewriteData.getTransferCallback() == null) {
            rewriteData.setProxyRespawn(false);
        }
    }

    @Override
    public PacketSignal handle(SetEntityDataPacket packet) {
        RewriteData rewriteData = this.player.getRewriteData();
        EntityDataMap metadata = packet.getMetadata();
        // Only what reaches the client counts, and a packet without flags keeps the last value
        if (this.connection == this.player.getDownstreamConnection()
                && packet.getRuntimeEntityId() == rewriteData.getOriginalRuntimeEntityId()
                && metadata.isFlagPresent(EntityFlag.NO_AI)) {
            rewriteData.setImmobileFlag(PlayerRewriteUtils.checkForImmobileFlag(metadata));
        }
        return PacketSignal.UNHANDLED;
    }

    @Override
    public PacketSignal handle(ItemComponentPacket packet) {
        if (!this.player.acceptItemComponentPacket()) {
            return Signals.CANCEL;
        }
        player.setAcceptItemComponentPacket(false);
        if (this.player.getProtocol().isAfterOrEqual(ProtocolVersion.MINECRAFT_PE_1_21_60)) {
            setItemDefinitions(packet.getItems());
        }
        return PacketSignal.UNHANDLED;
    }

    // ---- Persian Fix: display-ready Arabic-script text on every downstream software ----
    // Each handler fixes only the text surfaces the persian_fix section enables, and returns
    // UNHANDLED (no re-encode, zero cost beyond one scan) when nothing changed.

    @Override
    public PacketSignal handle(TextPacket packet) {
        PersianFixSettings persianFix = this.player.getProxy().getConfiguration().getPersianFixSettings();
        boolean changed = false;

        String message = packet.getMessage();
        if (message != null) {
            String fixed = PersianFixManager.fixMessage(persianFix, message);
            if (fixed != message) {
                packet.setMessage(fixed);
                changed = true;
            }
        }
        String filtered = packet.getFilteredMessage();
        if (filtered != null) {
            String fixed = PersianFixManager.fixMessage(persianFix, filtered);
            if (fixed != filtered) {
                packet.setFilteredMessage(fixed);
                changed = true;
            }
        }
        List<String> parameters = packet.getParameters();
        if (parameters != null && !parameters.isEmpty()) {
            List<String> fixedParameters = null;
            for (int i = 0; i < parameters.size(); i++) {
                String parameter = parameters.get(i);
                String fixedParameter = PersianFixManager.fixMessage(persianFix, parameter);
                if (fixedParameter != parameter) {
                    if (fixedParameters == null) {
                        fixedParameters = new ArrayList<>(parameters);
                    }
                    fixedParameters.set(i, fixedParameter);
                }
            }
            if (fixedParameters != null) {
                packet.setParameters(fixedParameters);
                changed = true;
            }
        }
        return changed ? PacketSignal.HANDLED : PacketSignal.UNHANDLED;
    }

    @Override
    public PacketSignal handle(SetTitlePacket packet) {
        PersianFixSettings persianFix = this.player.getProxy().getConfiguration().getPersianFixSettings();
        boolean changed = false;

        String text = packet.getText();
        if (text != null) {
            String fixed = PersianFixManager.fixTitle(persianFix, text);
            if (fixed != text) {
                packet.setText(fixed);
                changed = true;
            }
        }
        String filtered = packet.getFilteredTitleText();
        if (filtered != null) {
            String fixed = PersianFixManager.fixTitle(persianFix, filtered);
            if (fixed != filtered) {
                packet.setFilteredTitleText(fixed);
                changed = true;
            }
        }
        return changed ? PacketSignal.HANDLED : PacketSignal.UNHANDLED;
    }

    @Override
    public PacketSignal handle(BossEventPacket packet) {
        PersianFixSettings persianFix = this.player.getProxy().getConfiguration().getPersianFixSettings();
        boolean changed = false;

        String title = packet.getTitle();
        if (title != null) {
            String fixed = PersianFixManager.fixBossBar(persianFix, title);
            if (fixed != title) {
                packet.setTitle(fixed);
                changed = true;
            }
        }
        String filtered = packet.getFilteredTitle();
        if (filtered != null) {
            String fixed = PersianFixManager.fixBossBar(persianFix, filtered);
            if (fixed != filtered) {
                packet.setFilteredTitle(fixed);
                changed = true;
            }
        }
        return changed ? PacketSignal.HANDLED : PacketSignal.UNHANDLED;
    }

    @Override
    public PacketSignal handle(ModalFormRequestPacket packet) {
        PersianFixSettings persianFix = this.player.getProxy().getConfiguration().getPersianFixSettings();
        String formData = packet.getFormData();
        String fixed = PersianFixManager.fixFormJson(persianFix, formData);
        if (fixed != formData) {
            packet.setFormData(fixed);
            return PacketSignal.HANDLED;
        }
        return PacketSignal.UNHANDLED;
    }

    @Override
    public PacketSignal handle(SetDisplayObjectivePacket packet) {
        PersianFixSettings persianFix = this.player.getProxy().getConfiguration().getPersianFixSettings();
        String displayName = packet.getDisplayName();
        String fixed = PersianFixManager.fixScoreboardText(persianFix, displayName);
        if (fixed != displayName) {
            packet.setDisplayName(fixed);
            return PacketSignal.HANDLED;
        }
        return PacketSignal.UNHANDLED;
    }

    @Override
    public PacketSignal handle(SetScorePacket packet) {
        PersianFixSettings persianFix = this.player.getProxy().getConfiguration().getPersianFixSettings();
        List<ScoreInfo> infos = packet.getInfos();
        if (infos == null || infos.isEmpty()) {
            return PacketSignal.UNHANDLED;
        }

        List<ScoreInfo> fixedInfos = null;
        for (int i = 0; i < infos.size(); i++) {
            ScoreInfo info = infos.get(i);
            String name = info.getName();
            // Only fake-player entries carry a literal name the client draws; entity scores are
            // resolved by the client from the entity itself and their entries stay untouched.
            if (name == null || info.getType() != ScoreInfo.ScorerType.FAKE) {
                continue;
            }
            String fixed = PersianFixManager.fixScoreboardText(persianFix, name);
            if (fixed != name) {
                if (fixedInfos == null) {
                    fixedInfos = new ArrayList<>(infos);
                }
                fixedInfos.set(i, new ScoreInfo(info.getScoreboardId(), info.getObjectiveId(), info.getScore(), fixed));
            }
        }
        if (fixedInfos != null) {
            packet.setInfos(fixedInfos);
            return PacketSignal.HANDLED;
        }
        return PacketSignal.UNHANDLED;
    }

    @Override
    public PacketSignal handle(BlockEntityDataPacket packet) {
        // Sign updates a server sends back to the client (dynamic sign text): the same wrap and
        // shape as the client's own sign edits.
        PersianFixSettings persianFix = this.player.getProxy().getConfiguration().getPersianFixSettings();
        org.cloudburstmc.nbt.NbtMap fixed = PersianFixManager.fixSignNbt(persianFix, packet.getData());
        if (fixed != packet.getData()) {
            packet.setData(fixed);
            return PacketSignal.HANDLED;
        }
        return PacketSignal.UNHANDLED;
    }

    @Override
    public void sendProxiedBatch(BedrockBatchWrapper batch) {
        ClientConnection current = this.player.getDownstreamConnection();
        if (current != null && this.connection != current) {
            // Noop. Drop batches from a downstream that is no longer the player's active one.
            // Null check is for the initial connection.
            return;
        }
        if (this.player.getConnection().isConnected()) {
            this.player.getConnection().sendPacket(batch.retain());
        }
    }

    @Override
    public PacketSignal doPacketRewrite(BedrockPacket packet) {
        RewriteMaps rewriteMaps = this.player.getRewriteMaps();
        if (rewriteMaps.getBlockMap() != null) {
            return mergeSignals(rewriteMaps.getBlockMap().doRewrite(packet),
                    ProxyPacketHandler.super.doPacketRewrite(packet));
        }
        return ProxyPacketHandler.super.doPacketRewrite(packet);
    }

    @Override
    public PacketSignal handle(AvailableCommandsPacket packet) {
        if (!this.player.getProxy().getConfiguration().injectCommands()) {
            return PacketSignal.UNHANDLED;
        }
        int sizeBefore = packet.getCommands().size();

        // fastutil's synchronized wrapper requires manual synchronization on the map for
        // iteration: this runs on event loops while Python plugins may register commands at
        // runtime, and an unsynchronized read used to race the registration path.
        Object2ObjectMap<String, Command> commands = this.player.getProxy().getCommandMap().getCommands();
        synchronized (commands) {
            for (Command command : commands.values()) {
                if (command.getPermission() == null || this.player.hasPermission(command.getPermission())) {
                    packet.getCommands().add(command.getCommandData());
                }
            }
        }

        if (packet.getCommands().size() == sizeBefore) {
            return PacketSignal.UNHANDLED;
        }

        // Some server commands are missing aliases, which protocol lib doesn't like
        ListIterator<CommandData> iterator = packet.getCommands().listIterator();
        while (iterator.hasNext()) {
            CommandData command = iterator.next();
            if (command.getAliases() != null) {
                continue;
            }

            Map<String, Set<CommandEnumConstraint>> aliases = new LinkedHashMap<>();
            aliases.put(command.getName(), EnumSet.of(CommandEnumConstraint.ALLOW_ALIASES));

            iterator.set(new CommandData(command.getName(),
                    command.getDescription(),
                    command.getFlags(),
                    command.getPermission(),
                    new CommandEnumData(command.getName() + "_aliases", aliases, false),
                    Collections.emptyList(),
                    command.getOverloads()));
        }
        return PacketSignal.HANDLED;
    }

    @Override
    public PacketSignal handle(ChunkRadiusUpdatedPacket packet) {
        this.player.getLoginData().setChunkRadius(packet.getRadius());
        return PacketSignal.UNHANDLED;
    }

    @Override
    public PacketSignal handle(ChangeDimensionPacket packet) {
        this.player.getRewriteData().setDimension(packet.getDimension());
        return PacketSignal.UNHANDLED;
    }

    @Override
    public PacketSignal handle(ClientCacheMissResponsePacket packet) {
        if (this.player.getProtocol().isBefore(ProtocolVersion.MINECRAFT_PE_1_18_30)) {
            this.player.getChunkBlobs().removeAll(packet.getBlobs().keySet());
        }
        return PacketSignal.UNHANDLED;
    }

    @Override
    public PacketSignal handle(CameraPresetsPacket packet) {
        setCameraPresetDefinitions(packet.getPresets());
        return PacketSignal.UNHANDLED;
    }

    protected PacketSignal onPlayStatus(PlayStatusPacket packet, Consumer<String> failedTask, ClientConnection connection) {
        String message;
        switch (packet.getStatus()) {
            case LOGIN_SUCCESS -> {
                if (this.player.getProtocol().isAfterOrEqual(ProtocolVersion.MINECRAFT_PE_1_12)) {
                    ClientCacheStatusPacket cachePacket = new ClientCacheStatusPacket();
                    cachePacket.setSupported(this.player.getLoginData().isCacheSupported());
                    connection.sendPacket(cachePacket);
                }
                return Signals.CANCEL;
            }
            case LOGIN_FAILED_CLIENT_OLD, LOGIN_FAILED_SERVER_OLD -> message = "Incompatible version";
            case FAILED_SERVER_FULL_SUB_CLIENT -> message = "Server is full";
            default -> {
                return PacketSignal.UNHANDLED;
            }
        }

        this.logRefused(message + " (" + packet.getStatus() + ")");
        failedTask.accept(message);
        return Signals.CANCEL;
    }

    /**
     * Logs why the server turned the player away while connecting, which no other line says. Only
     * the first refusal is logged, as a server refusing with a status disconnects right after.
     */
    protected void logRefused(String reason) {
        if (this.refused) {
            return;
        }
        this.refused = true;
        this.player.getLogger().info("[{}|{}] -> Downstream [{}] refused the connection: {}", this.connection.getSocketAddress(),
                this.player.getName(), this.connection.getServerInfo().getServerName(), reason);
    }

    protected void logRefused(DisconnectPacket packet) {
        String message = packet.getKickMessage();
        this.logRefused(message == null || message.isBlank() ? String.valueOf(packet.getReason()) : message);
    }

    @Override
    public RewriteMaps getRewriteMaps() {
        return this.player.getRewriteMaps();
    }

    @Override
    public ClientConnection getConnection() {
        return connection;
    }

    protected static final String BLOCKING_ID = "minecraft:shield";

    protected void setItemDefinitions(Collection<ItemDefinition> definitions) {
        BedrockCodecHelper codecHelper = this.player.getConnection()
            .getPeer()
            .getCodecHelper();
        FakeDefinitionRegistry<ItemDefinition> itemRegistry = FakeDefinitionRegistry.createItemRegistry();
        for (ItemDefinition definition : definitions) {
            if (definition.getIdentifier().equals(BLOCKING_ID)) {
                itemRegistry.getRuntimeMap().put(definition.getRuntimeId(), definition);
                break;
            }
        }
        codecHelper.setItemDefinitions(itemRegistry);
    }

    protected void setCameraPresetDefinitions(Collection<CameraPreset> presets) {
        BedrockCodecHelper codecHelper = this.player.getConnection()
                .getPeer()
                .getCodecHelper();
        SimpleDefinitionRegistry.Builder<NamedDefinition> registry = SimpleDefinitionRegistry.builder();
        int id = 0;
        for (CameraPreset preset : presets) {
            registry.add(new SimpleNamedDefinition(preset.getIdentifier(), id++));
        }
        codecHelper.setCameraPresetDefinitions(registry.build());
    }
}
