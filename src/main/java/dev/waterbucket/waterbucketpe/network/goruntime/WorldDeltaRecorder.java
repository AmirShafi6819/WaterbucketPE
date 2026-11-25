/*
 * Copyright 2026 WaterbucketTEAM
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

package dev.waterbucket.waterbucketpe.network.goruntime;

import dev.waterbucket.waterbucketpe.event.EventManager;
import dev.waterbucket.waterbucketpe.network.protocol.handler.PluginPacketHandler;
import dev.waterbucket.waterbucketpe.network.protocol.rewrite.types.RewriteData;
import dev.waterbucket.waterbucketpe.player.ProxiedPlayer;
import dev.waterbucket.waterbucketpe.plugin.compat.Player;
import dev.waterbucket.waterbucketpe.plugin.compat.event.block.BlockEvents;
import dev.waterbucket.waterbucketpe.plugin.compat.event.entity.EntityEvents;
import dev.waterbucket.waterbucketpe.plugin.compat.event.inventory.InventoryEvents;
import dev.waterbucket.waterbucketpe.plugin.compat.event.player.PlayerEvents;
import dev.waterbucket.waterbucketpe.plugin.compat.event.world.WorldEvents;
import dev.waterbucket.waterbucketpe.plugin.compat.world.BlockPos;
import dev.waterbucket.waterbucketpe.plugin.compat.world.BlockView;
import dev.waterbucket.waterbucketpe.plugin.compat.world.ItemView;
import dev.waterbucket.waterbucketpe.plugin.compat.world.WorldService;
import lombok.extern.log4j.Log4j2;
import org.cloudburstmc.math.vector.Vector3f;
import org.cloudburstmc.math.vector.Vector3i;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtUtils;
import org.cloudburstmc.protocol.bedrock.data.LevelEvent;
import org.cloudburstmc.protocol.bedrock.data.PlayerActionType;
import org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData;
import org.cloudburstmc.protocol.bedrock.data.PlayerBlockActionData;
import org.cloudburstmc.protocol.bedrock.data.AttributeData;
import org.cloudburstmc.protocol.bedrock.data.entity.EntityDataMap;
import org.cloudburstmc.protocol.bedrock.data.entity.EntityFlag;
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemData;
import org.cloudburstmc.protocol.bedrock.data.definitions.ItemDefinition;
import org.cloudburstmc.protocol.bedrock.packet.AddEntityPacket;
import org.cloudburstmc.protocol.bedrock.packet.AddItemEntityPacket;
import org.cloudburstmc.protocol.bedrock.packet.AddPlayerPacket;
import org.cloudburstmc.protocol.bedrock.packet.AnimatePacket;
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket;
import org.cloudburstmc.protocol.bedrock.packet.BlockEntityDataPacket;
import org.cloudburstmc.protocol.bedrock.packet.ChangeDimensionPacket;
import org.cloudburstmc.protocol.bedrock.packet.ContainerClosePacket;
import org.cloudburstmc.protocol.bedrock.packet.ContainerOpenPacket;
import org.cloudburstmc.protocol.bedrock.packet.GameRulesChangedPacket;
import org.cloudburstmc.protocol.bedrock.packet.InteractPacket;
import org.cloudburstmc.protocol.bedrock.packet.InventoryContentPacket;
import org.cloudburstmc.protocol.bedrock.packet.InventorySlotPacket;
import org.cloudburstmc.protocol.bedrock.packet.InventoryTransactionPacket;
import org.cloudburstmc.protocol.bedrock.data.inventory.transaction.InventoryTransactionType;
import org.cloudburstmc.protocol.bedrock.packet.ItemComponentPacket;
import org.cloudburstmc.protocol.bedrock.packet.LevelChunkPacket;
import org.cloudburstmc.protocol.bedrock.packet.LevelEventPacket;
import org.cloudburstmc.protocol.bedrock.packet.MobEffectPacket;
import org.cloudburstmc.protocol.bedrock.packet.MobEquipmentPacket;
import org.cloudburstmc.protocol.bedrock.packet.ModalFormResponsePacket;
import org.cloudburstmc.protocol.bedrock.packet.MoveEntityAbsolutePacket;
import org.cloudburstmc.protocol.bedrock.packet.MoveEntityDeltaPacket;
import org.cloudburstmc.protocol.bedrock.packet.MovePlayerPacket;
import org.cloudburstmc.protocol.bedrock.packet.PlayerActionPacket;
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket;
import org.cloudburstmc.protocol.bedrock.packet.RemoveEntityPacket;
import org.cloudburstmc.protocol.bedrock.packet.RespawnPacket;
import org.cloudburstmc.protocol.bedrock.packet.SetEntityMotionPacket;
import org.cloudburstmc.protocol.bedrock.packet.SetDifficultyPacket;
import org.cloudburstmc.protocol.bedrock.packet.SetEntityDataPacket;
import org.cloudburstmc.protocol.bedrock.packet.SetPlayerGameTypePacket;
import org.cloudburstmc.protocol.bedrock.packet.SetSpawnPositionPacket;
import org.cloudburstmc.protocol.bedrock.packet.ItemStackRequestPacket;
import org.cloudburstmc.protocol.bedrock.packet.SetTimePacket;
import org.cloudburstmc.protocol.bedrock.packet.StartGamePacket;
import org.cloudburstmc.protocol.bedrock.packet.UpdateAttributesPacket;
import org.cloudburstmc.protocol.bedrock.packet.UpdateBlockPacket;
import org.cloudburstmc.protocol.bedrock.packet.UpdateBlockSyncedPacket;
import org.cloudburstmc.protocol.common.PacketSignal;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One recorder per player: watches every packet in both directions, keeps the
 * Go runtime's world model fed (deltas) and raises the compatibility world
 * events on real moments. Cancelling a compat event drops the client packet
 * that caused it, so cancellation is real, not cosmetic.
 * <p>
 * Nothing here runs heavy work inline: deltas are queued to the bridge writer
 * (drop-on-full), events dispatch synchronously like every other proxy event.
 */
@Log4j2
public final class WorldDeltaRecorder implements PluginPacketHandler {

    private final ProxiedPlayer player;
    private final GoRuntimeManager runtime;
    private final WorldService worldService;
    private final EventManager events;
    private final long[] healthByEntity = new long[0]; // placeholder, map below is authoritative
    private final Map<Long, Float> lastHealth = new HashMap<>();
    private final Map<Long, String> entityTypes = new HashMap<>();
    private boolean sneaking;
    private boolean sprinting;
    private ItemData heldItem;
    private final Map<Integer, String> blockTable = new HashMap<>();
    private int gamemode = -1;
    private int dimension = 0;
    private String serverKey = "unknown";

    ProxiedPlayer player() {
        return this.player;
    }

    public WorldDeltaRecorder(ProxiedPlayer player, GoRuntimeManager runtime, WorldService worldService) {
        this.player = player;
        this.runtime = runtime;
        this.worldService = worldService;
        this.events = player.getProxy().getEventManager();
    }

    // ---- helpers ----

    private String serverKey() {
        if (this.player.getServerInfo() != null) {
            this.serverKey = this.player.getServerInfo().getServerName();
        }
        return this.serverKey;
    }

    private int dimension() {
        RewriteData rewriteData = this.player.getRewriteData();
        return rewriteData != null ? rewriteData.getDimension() : this.dimension;
    }

    private long selfEntityId() {
        RewriteData rewriteData = this.player.getRewriteData();
        return rewriteData != null ? rewriteData.getOriginalRuntimeEntityId() : -1;
    }

    private Player compatPlayer() {
        return new Player(this.player);
    }

    private ItemView itemView(ItemData data) {
        if (data == null || (data.getCount() == 0 && (data.getDefinition() == null || data.getDefinition().getIdentifier() == null))) {
            return ItemView.AIR;
        }
        ItemDefinition definition = data.getDefinition();
        String name = definition != null && definition.getIdentifier() != null
                ? definition.getIdentifier() : "minecraft:unknown";
        Map<String, Object> nbt = null;
        NbtMap tag = data.getTag();
        if (tag != null && !tag.isEmpty()) {
            nbt = tag;
        }
        return new ItemView(name, data.getDamage(), data.getCount(), data.getNetId(), nbt);
    }

    private BridgeCodec.WireItem wireItem(ItemData data) {
        if (data == null) {
            return BridgeCodec.WireItem.AIR;
        }
        ItemDefinition definition = data.getDefinition();
        String name = definition != null && definition.getIdentifier() != null
                ? definition.getIdentifier() : "";
        byte[] nbtBytes = null;
        NbtMap tag = data.getTag();
        if (tag != null && !tag.isEmpty()) {
            try {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                var writer = NbtUtils.createNetworkWriter(out);
                writer.writeTag(tag);
                nbtBytes = out.toByteArray();
            } catch (Exception e) {
                nbtBytes = null;
            }
        }
        return new BridgeCodec.WireItem(name, data.getDamage(), data.getCount(), data.getNetId(), nbtBytes);
    }

    private byte[] nbtBytes(NbtMap tag) {
        if (tag == null || tag.isEmpty()) {
            return null;
        }
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            NbtUtils.createNetworkWriter(out).writeTag(tag);
            return out.toByteArray();
        } catch (Exception e) {
            return null;
        }
    }

    private void send(BridgeCodec.Writer w) {
        this.runtime.sendAsync(w.toArray());
    }

    private boolean fireSyncCancellable(dev.waterbucket.waterbucketpe.event.Event event, int eventId, List<BridgeCodec.Field> fields) {
        this.events.callEvent(event);
        if (event.isCancelled()) {
            return true;
        }
        return this.runtime.isEventCancelled(eventId, fields, this.runtime.settings().eventSyncTimeoutMs());
    }

    // ---- packet entry ----

    @Override
    public PacketSignal handlePacket(BedrockPacket packet, org.cloudburstmc.protocol.bedrock.PacketDirection direction) {
        try {
            return direction == org.cloudburstmc.protocol.bedrock.PacketDirection.CLIENT_BOUND
                    ? handleClientBound(packet) : handleServerBound(packet);
        } catch (Throwable t) {
            // Never let world observation break the packet flow.
            log.debug("[WorldView] recorder error on {}: {}", packet.getClass().getSimpleName(), t.getMessage());
            return PacketSignal.UNHANDLED;
        }
    }

    // ---- server -> client: build the model, raise observations ----

    private PacketSignal handleClientBound(BedrockPacket packet) {
        if (!this.runtime.settings().enable()) {
            return PacketSignal.UNHANDLED;
        }
        String key = serverKey();

        if (packet instanceof StartGamePacket p) {
            int dim = p.getDimensionId();
            this.dimension = dim;
            this.gamemode = p.getLevelGameType() != null ? p.getLevelGameType().ordinal() : 0;
            Vector3i spawn = p.getDefaultSpawn() != null ? p.getDefaultSpawn() : Vector3i.from(0, 0, 0);
            List<BridgeCodec.Field> gamerules = new ArrayList<>();
            Map<String, String> rules = new HashMap<>();
            for (var rule : p.getGamerules()) {
                rules.put(rule.getName(), String.valueOf(rule.getValue()));
            }
            BridgeCodec.Writer w = new BridgeCodec.Writer(128);
            w.deltaHeader(BridgeCodec.DELTA_WORLD_META);
            w.string(key);
            w.string(p.getLevelName());
            w.i64(p.getSeed());
            w.vec3i(spawn.getX(), spawn.getY(), spawn.getZ());
            w.i32(this.gamemode);
            w.i32(p.getDifficulty());
            w.u16(rules.size());
            rules.forEach((k, v) -> w.string(k).string(v));
            // Block palette: runtime id = list index (name per entry).
            List<org.cloudburstmc.protocol.bedrock.data.BlockPropertyData> blockProps = p.getBlockProperties();
            this.blockTable.clear();
            w.u16(blockProps.size());
            for (int i = 0; i < blockProps.size(); i++) {
                w.i32(i);
                w.string(blockProps.get(i).getName());
                this.blockTable.put(i, blockProps.get(i).getName());
            }
            send(w);
            WorldViewInitCompat(key, p.getLevelName(), p.getSeed(), dim);
            return PacketSignal.UNHANDLED;
        }

        if (packet instanceof LevelChunkPacket p) {
            BridgeCodec.Writer w = new BridgeCodec.Writer(64);
            w.deltaHeader(BridgeCodec.DELTA_CHUNK);
            w.string(key);
            w.i32(dimension());
            w.i32(p.getChunkX());
            w.i32(p.getChunkZ());
            var data = p.getData();
            byte[] blob;
            if (data.hasArray()) {
                blob = java.util.Arrays.copyOfRange(data.array(), data.arrayOffset(), data.arrayOffset() + data.readableBytes());
            } else {
                blob = new byte[data.readableBytes()];
                data.readBytes(blob);
                data.resetReaderIndex();
            }
            w.bytes(blob);
            send(w);
            this.events.callEvent(new WorldEvents.ChunkSend(key, dimension(),
                    p.getChunkX(), p.getChunkZ(), this.player.getName()));
            return PacketSignal.UNHANDLED;
        }

        if (packet instanceof UpdateBlockPacket p) {
            Vector3i pos = p.getBlockPosition();
            int runtimeId = p.getDefinition() != null ? p.getDefinition().getRuntimeId() : -1;
            BlockView old = this.worldService.getBlock(key, dimension(), new BlockPos(pos.getX(), pos.getY(), pos.getZ()));
            BridgeCodec.Writer w = new BridgeCodec.Writer(64);
            w.deltaHeader(BridgeCodec.DELTA_BLOCK_UPDATE);
            w.string(key);
            w.i32(dimension());
            w.vec3i(pos.getX(), pos.getY(), pos.getZ());
            w.i32(p.getDataLayer());
            w.i32(runtimeId);
            send(w);
            this.events.callEvent(new BlockEvents.Update(key, dimension(),
                    new BlockPos(pos.getX(), pos.getY(), pos.getZ()),
                    runtimeId, resolveBlockName(runtimeId), old.name(), p.getDataLayer()));
            return PacketSignal.UNHANDLED;
        }

        if (packet instanceof UpdateBlockSyncedPacket p) {
            Vector3i pos = p.getBlockPosition();
            int runtimeId = p.getDefinition() != null ? p.getDefinition().getRuntimeId() : -1;
            BridgeCodec.Writer w = new BridgeCodec.Writer(64);
            w.deltaHeader(BridgeCodec.DELTA_BLOCK_UPDATE);
            w.string(key);
            w.i32(dimension());
            w.vec3i(pos.getX(), pos.getY(), pos.getZ());
            w.i32(0);
            w.i32(runtimeId);
            send(w);
            return PacketSignal.UNHANDLED;
        }

        if (packet instanceof BlockEntityDataPacket p) {
            Vector3i pos = p.getBlockPosition();
            NbtMap tag = p.getData();
            String type = tag.getString("id", "unknown");
            BridgeCodec.Writer w = new BridgeCodec.Writer(96);
            w.deltaHeader(BridgeCodec.DELTA_BLOCK_ENTITY);
            w.string(key);
            w.i32(dimension());
            w.vec3i(pos.getX(), pos.getY(), pos.getZ());
            w.string(type);
            w.bytes(nbtBytes(tag) == null ? new byte[0] : nbtBytes(tag));
            send(w);
            return PacketSignal.UNHANDLED;
        }

        if (packet instanceof AddEntityPacket p) {
            var rot = p.getRotation();
            trackEntityAdd(key, p.getIdentifier(), p.getRuntimeEntityId(), p.getPosition(),
                    p.getMotion(), rot.getY(), rot.getX(), false, "", p.getMetadata());
            return PacketSignal.UNHANDLED;
        }

        if (packet instanceof AddPlayerPacket p) {
            trackEntityAdd(key, "minecraft:player", p.getRuntimeEntityId(), p.getPosition(),
                    p.getMotion(), 0f, 0f, true, p.getUsername(), p.getMetadata());
            return PacketSignal.UNHANDLED;
        }

        if (packet instanceof AddItemEntityPacket p) {
            String type = "minecraft:item";
            trackEntityAdd(key, type, p.getRuntimeEntityId(), p.getPosition(), p.getMotion(),
                    0f, 0f, false, "", p.getMetadata());
            return PacketSignal.UNHANDLED;
        }

        if (packet instanceof RemoveEntityPacket p) {
            BridgeCodec.Writer w = new BridgeCodec.Writer(32);
            w.deltaHeader(BridgeCodec.DELTA_ENTITY_REMOVE);
            w.string(key);
            w.i64(p.getUniqueEntityId());
            send(w);
            this.events.callEvent(new EntityEvents.Despawn(key, p.getUniqueEntityId(),
                    this.entityTypes.getOrDefault(p.getUniqueEntityId(), "unknown")));
            this.entityTypes.remove(p.getUniqueEntityId());
            this.lastHealth.remove(p.getUniqueEntityId());
            return PacketSignal.UNHANDLED;
        }

        if (packet instanceof MoveEntityAbsolutePacket p) {
            entityMove(key, p.getRuntimeEntityId(), p.getPosition(), p.getRotation(), p.isTeleported());
            return PacketSignal.UNHANDLED;
        }

        if (packet instanceof MoveEntityDeltaPacket p) {
            boolean teleported = p.getFlags().contains(MoveEntityDeltaPacket.Flag.TELEPORTING);
            if (p.getFlags().contains(MoveEntityDeltaPacket.Flag.HAS_X)
                    || p.getFlags().contains(MoveEntityDeltaPacket.Flag.HAS_Y)
                    || p.getFlags().contains(MoveEntityDeltaPacket.Flag.HAS_Z)) {
                entityMove(key, p.getRuntimeEntityId(),
                        Vector3f.from(p.getX(), p.getY(), p.getZ()), Vector3f.ZERO, teleported);
            } else if (teleported) {
                this.events.callEvent(new EntityEvents.Teleport(key, p.getRuntimeEntityId(),
                        p.getX(), p.getY(), p.getZ()));
            }
            return PacketSignal.UNHANDLED;
        }

        if (packet instanceof MovePlayerPacket p) {
            entityMove(key, p.getRuntimeEntityId(), p.getPosition(), p.getRotation(), false);
            return PacketSignal.UNHANDLED;
        }

        if (packet instanceof SetEntityMotionPacket p) {
            Vector3f motion = p.getMotion();
            BridgeCodec.Writer w = new BridgeCodec.Writer(48);
            w.deltaHeader(BridgeCodec.DELTA_ENTITY_MOTION);
            w.string(key);
            w.i64(p.getRuntimeEntityId());
            w.vec3f(motion.getX(), motion.getY(), motion.getZ());
            send(w);
            this.events.callEvent(new EntityEvents.Motion(key, p.getRuntimeEntityId(),
                    motion.getX(), motion.getY(), motion.getZ()));
            return PacketSignal.UNHANDLED;
        }

        if (packet instanceof SetEntityDataPacket p) {
            EntityDataMap metadata = p.getMetadata();
            long flags = 0;
            var flagMap = metadata.getFlags();
            if (flagMap != null) {
                for (Map.Entry<EntityFlag, Boolean> e : flagMap.entrySet()) {
                    if (Boolean.TRUE.equals(e.getValue())) {
                        flags |= 1L << e.getKey().ordinal();
                    }
                }
            }
            BridgeCodec.Writer w = new BridgeCodec.Writer(40);
            w.deltaHeader(BridgeCodec.DELTA_ENTITY_META);
            w.string(key);
            w.i64(p.getRuntimeEntityId());
            w.i64(flags);
            send(w);
            this.events.callEvent(new EntityEvents.DataUpdate(key, p.getRuntimeEntityId(), flags));
            return PacketSignal.UNHANDLED;
        }

        if (packet instanceof UpdateAttributesPacket p) {
            for (AttributeData attribute : p.getAttributes()) {
                BridgeCodec.Writer w = new BridgeCodec.Writer(72);
                w.deltaHeader(BridgeCodec.DELTA_ENTITY_ATTRIBUTE);
                w.string(key);
                w.i64(p.getRuntimeEntityId());
                w.string(attribute.getName());
                w.f32(attribute.getValue());
                send(w);
                if (attribute.getName().equals("minecraft:health")) {
                    fireHealthChanges(key, p.getRuntimeEntityId(), attribute.getValue());
                }
            }
            return PacketSignal.UNHANDLED;
        }

        if (packet instanceof MobEffectPacket p) {
            int action = switch (p.getEvent()) {
                case ADD -> EntityEvents.EFFECT_ADD;
                case MODIFY -> EntityEvents.EFFECT_MODIFY;
                case REMOVE -> EntityEvents.EFFECT_REMOVE;
                default -> 0;
            };
            BridgeCodec.Writer w = new BridgeCodec.Writer(64);
            w.deltaHeader(BridgeCodec.DELTA_ENTITY_EFFECT);
            w.string(key);
            w.i64(p.getRuntimeEntityId());
            w.u8(action);
            w.i32(p.getEffectId());
            w.i32(p.getAmplifier());
            w.i32(p.getDuration());
            send(w);
            this.events.callEvent(new EntityEvents.Effect(key, p.getRuntimeEntityId(),
                    action, p.getEffectId(), p.getAmplifier(), p.getDuration()));
            return PacketSignal.UNHANDLED;
        }

        if (packet instanceof MobEquipmentPacket p) {
            BridgeCodec.WireItem item = wireItem(p.getItem());
            BridgeCodec.Writer w = new BridgeCodec.Writer(96);
            w.deltaHeader(BridgeCodec.DELTA_PLAYER_EQUIP);
            w.string(key);
            w.i64(p.getRuntimeEntityId());
            w.i32(p.getHotbarSlot());
            w.i32(p.getInventorySlot());
            w.item(item);
            send(w);
            if (p.getRuntimeEntityId() == selfEntityId()) {
                this.events.callEvent(new PlayerEvents.ItemHeld(compatPlayer(), key, dimension(),
                        itemView(p.getItem()), p.getHotbarSlot(), p.getInventorySlot()));
            }
            return PacketSignal.UNHANDLED;
        }

        if (packet instanceof ContainerOpenPacket p) {
            BridgeCodec.Writer w = new BridgeCodec.Writer(96);
            w.deltaHeader(BridgeCodec.DELTA_CONTAINER);
            w.string(key);
            w.i64(selfEntityId());
            w.bool(true);
            w.i32(p.getId());
            w.string(p.getType() != null ? p.getType().name() : "unknown");
            w.vec3i(p.getBlockPosition().getX(), p.getBlockPosition().getY(), p.getBlockPosition().getZ());
            send(w);
            this.events.callEvent(new InventoryEvents.Open(compatPlayer(), key, dimension(), p.getId(),
                    p.getType() != null ? p.getType().name() : "unknown",
                    new BlockPos(p.getBlockPosition().getX(), p.getBlockPosition().getY(), p.getBlockPosition().getZ())));
            return PacketSignal.UNHANDLED;
        }

        if (packet instanceof ContainerClosePacket p) {
            BridgeCodec.Writer w = new BridgeCodec.Writer(48);
            w.deltaHeader(BridgeCodec.DELTA_CONTAINER);
            w.string(key);
            w.i64(selfEntityId());
            w.bool(false);
            w.i32(p.getId());
            w.string(p.getType() != null ? p.getType().name() : "unknown");
            w.vec3i(0, 0, 0);
            send(w);
            this.events.callEvent(new InventoryEvents.Close(compatPlayer(), key, dimension(), p.getId()));
            return PacketSignal.UNHANDLED;
        }

        if (packet instanceof InventoryContentPacket p) {
            BridgeCodec.Writer w = new BridgeCodec.Writer(256);
            w.deltaHeader(BridgeCodec.DELTA_INVENTORY_CONTENT);
            w.string(key);
            w.i64(selfEntityId());
            w.i32(p.getContainerId());
            w.u16(p.getContents().size());
            int slot = 0;
            for (ItemData itemData : p.getContents()) {
                w.i32(slot);
                w.item(wireItem(itemData));
                slot++;
            }
            send(w);
            slot = 0;
            for (ItemData itemData : p.getContents()) {
                this.events.callEvent(new InventoryEvents.Update(compatPlayer(), key, dimension(),
                        p.getContainerId(), slot, itemView(itemData)));
                slot++;
            }
            return PacketSignal.UNHANDLED;
        }

        if (packet instanceof InventorySlotPacket p) {
            BridgeCodec.Writer w = new BridgeCodec.Writer(96);
            w.deltaHeader(BridgeCodec.DELTA_INVENTORY_SLOT);
            w.string(key);
            w.i64(selfEntityId());
            w.i32(p.getContainerId());
            w.i32(p.getSlot());
            w.item(wireItem(p.getItem()));
            send(w);
            this.events.callEvent(new InventoryEvents.Update(compatPlayer(), key, dimension(),
                    p.getContainerId(), p.getSlot(), itemView(p.getItem())));
            return PacketSignal.UNHANDLED;
        }

        if (packet instanceof SetTimePacket p) {
            BridgeCodec.Writer w = new BridgeCodec.Writer(24);
            w.deltaHeader(BridgeCodec.DELTA_WORLD_TIME);
            w.string(key);
            w.i32(p.getTime());
            send(w);
            this.events.callEvent(new WorldEvents.TimeChange(key, p.getTime()));
            return PacketSignal.UNHANDLED;
        }

        if (packet instanceof SetDifficultyPacket p) {
            BridgeCodec.Writer w = new BridgeCodec.Writer(24);
            w.deltaHeader(BridgeCodec.DELTA_WORLD_DIFFICULTY);
            w.string(key);
            w.i32(p.getDifficulty());
            send(w);
            this.events.callEvent(new WorldEvents.DifficultyChange(key, p.getDifficulty()));
            return PacketSignal.UNHANDLED;
        }

        if (packet instanceof GameRulesChangedPacket p) {
            for (var rule : p.getGameRules()) {
                String value = String.valueOf(rule.getValue());
                BridgeCodec.Writer w = new BridgeCodec.Writer(64);
                w.deltaHeader(BridgeCodec.DELTA_WORLD_GAMERULE);
                w.string(key);
                w.string(rule.getName());
                w.string(value);
                send(w);
                this.events.callEvent(new WorldEvents.GameRuleChange(key, rule.getName(), value));
            }
            return PacketSignal.UNHANDLED;
        }

        if (packet instanceof SetSpawnPositionPacket p) {
            if (p.getSpawnType() == SetSpawnPositionPacket.Type.WORLD_SPAWN) {
                Vector3i pos = p.getBlockPosition();
                BridgeCodec.Writer w = new BridgeCodec.Writer(48);
                w.deltaHeader(BridgeCodec.DELTA_SPAWN_POS);
                w.string(key);
                w.i32(dimension());
                w.vec3i(pos.getX(), pos.getY(), pos.getZ());
                send(w);
                this.events.callEvent(new WorldEvents.SpawnPositionChange(key, dimension(),
                        new BlockPos(pos.getX(), pos.getY(), pos.getZ())));
            }
            return PacketSignal.UNHANDLED;
        }

        if (packet instanceof LevelEventPacket p && p.getType() instanceof LevelEvent levelEvent) {
            Boolean weather = switch (levelEvent) {
                case START_RAINING, START_THUNDERSTORM -> true;
                case STOP_RAINING, STOP_THUNDERSTORM -> false;
                default -> null;
            };
            if (weather != null) {
                boolean thunder = levelEvent == LevelEvent.START_THUNDERSTORM
                        || levelEvent == LevelEvent.STOP_THUNDERSTORM;
                BridgeCodec.Writer w = new BridgeCodec.Writer(32);
                w.deltaHeader(BridgeCodec.DELTA_WEATHER);
                w.string(key);
                w.i32(dimension());
                w.bool(weather);
                w.bool(thunder);
                send(w);
                this.events.callEvent(new WorldEvents.WeatherChange(key, dimension(), weather, thunder));
            }
            return PacketSignal.UNHANDLED;
        }

        if (packet instanceof ChangeDimensionPacket p) {
            int from = dimension();
            this.dimension = p.getDimension();
            RewriteData rewriteData = this.player.getRewriteData();
            if (rewriteData != null) {
                rewriteData.setDimension(p.getDimension());
            }
            this.events.callEvent(new PlayerEvents.ChangeDimension(compatPlayer(), key, from, from, p.getDimension()));
            return PacketSignal.UNHANDLED;
        }

        if (packet instanceof RespawnPacket p && p.getState() == RespawnPacket.State.SERVER_READY) {
            Vector3f pos = p.getPosition();
            this.events.callEvent(new PlayerEvents.Respawn(compatPlayer(), key, dimension(),
                    pos.getX(), pos.getY(), pos.getZ()));
            return PacketSignal.UNHANDLED;
        }

        if (packet instanceof SetPlayerGameTypePacket p) {
            this.gamemode = p.getGamemode();
            this.events.callEvent(new PlayerEvents.GameModeChange(compatPlayer(), key, dimension(), this.gamemode));
            return PacketSignal.UNHANDLED;
        }

        if (packet instanceof ItemComponentPacket p) {
            // The item definitions here refresh the codec tables; names of
            // items we already resolve come through ItemData.getDefinition().
            return PacketSignal.UNHANDLED;
        }

        return PacketSignal.UNHANDLED;
    }

    private void WorldViewInitCompat(String key, String levelName, long seed, int dim) {
        var meta = this.worldService.worldMeta(key);
        if (meta == null) {
            // The Go side processes the meta delta asynchronously; build the
            // event from the packet data so plugins never miss the moment.
            meta = new dev.waterbucket.waterbucketpe.plugin.compat.world.WorldView(key, levelName, seed,
                    new BlockPos(0, 0, 0), this.gamemode, 0, 0, false, false, Map.of());
        }
        this.events.callEvent(new WorldEvents.WorldInit(key, meta));
    }

    private String resolveBlockName(int runtimeId) {
        if (runtimeId < 0) {
            return "minecraft:unknown";
        }
        String name = this.blockTable.get(runtimeId);
        return name != null ? name : "minecraft:runtime_id_" + runtimeId;
    }

    private void trackEntityAdd(String key, String type, long eid, Vector3f pos, Vector3f motion,
                                float yaw, float pitch, boolean isPlayer, String username, EntityDataMap metadata) {
        this.entityTypes.put(eid, type);
        long flags = 0;
        if (metadata != null && metadata.getFlags() != null) {
            for (Map.Entry<EntityFlag, Boolean> e : metadata.getFlags().entrySet()) {
                if (Boolean.TRUE.equals(e.getValue())) {
                    flags |= 1L << e.getKey().ordinal();
                }
            }
        }
        BridgeCodec.Writer w = new BridgeCodec.Writer(128);
        w.deltaHeader(BridgeCodec.DELTA_ENTITY_ADD);
        w.string(key);
        w.i32(dimension());
        w.i64(eid);
        w.string(type);
        w.vec3f(pos.getX(), pos.getY(), pos.getZ());
        w.f32(yaw);
        w.f32(pitch);
        w.vec3f(motion.getX(), motion.getY(), motion.getZ());
        w.u8(isPlayer ? 1 : 0);
        w.string(username);
        w.string("");
        w.i64(flags);
        send(w);
        this.events.callEvent(new EntityEvents.Spawn(key, new dev.waterbucket.waterbucketpe.plugin.compat.world.EntityView(
                eid, type, dimension(), pos.getX(), pos.getY(), pos.getZ(),
                motion.getX(), motion.getY(), motion.getZ(),
                yaw, pitch, flags, Map.of(), isPlayer, username, "")));
    }

    private void entityMove(String key, long eid, Vector3f pos, Vector3f rotation, boolean teleported) {
        BridgeCodec.Writer w = new BridgeCodec.Writer(64);
        w.deltaHeader(BridgeCodec.DELTA_ENTITY_MOVE);
        w.string(key);
        w.i64(eid);
        w.vec3f(pos.getX(), pos.getY(), pos.getZ());
        w.f32(rotation.getY());
        w.f32(rotation.getX());
        w.u8(1);
        w.u8(teleported ? 1 : 0);
        send(w);
        String type = this.entityTypes.getOrDefault(eid, "minecraft:entity");
        boolean isPlayer = eid == selfEntityId()
                || this.entityTypes.get(eid) == null;
        this.events.callEvent(new EntityEvents.Move(key, new dev.waterbucket.waterbucketpe.plugin.compat.world.EntityView(
                eid, type, dimension(), pos.getX(), pos.getY(), pos.getZ(),
                0, 0, 0, rotation.getY(), rotation.getX(), 0, Map.of(), isPlayer,
                eid == selfEntityId() ? this.player.getName() : "", ""), teleported));
        if (teleported) {
            this.events.callEvent(new EntityEvents.Teleport(key, eid, pos.getX(), pos.getY(), pos.getZ()));
        }
    }

    private void fireHealthChanges(String key, long eid, float newHealth) {
        Float oldBoxed = this.lastHealth.put(eid, newHealth);
        if (oldBoxed == null) {
            return;
        }
        float oldHealth = oldBoxed;
        this.events.callEvent(new EntityEvents.HealthChange(key, eid, oldHealth, newHealth));
        if (newHealth < oldHealth) {
            this.events.callEvent(new EntityEvents.Damage(key, eid, oldHealth - newHealth, oldHealth, newHealth));
            if (eid == selfEntityId() && newHealth <= 0) {
                this.events.callEvent(new PlayerEvents.Death(compatPlayer(), key, dimension()));
            }
        } else if (newHealth > oldHealth) {
            this.events.callEvent(new EntityEvents.RegainHealth(key, eid, newHealth - oldHealth, oldHealth, newHealth));
        }
    }

    // ---- client -> server: action events with real cancellation ----

    private PacketSignal handleServerBound(BedrockPacket packet) {
        if (!this.runtime.settings().enable()) {
            return PacketSignal.UNHANDLED;
        }
        String key = serverKey();

        if (packet instanceof PlayerAuthInputPacket p) {
            // Movement snapshot feeds the model.
            Vector3f pos = p.getPosition();
            Vector3f rotation = p.getRotation();
            BridgeCodec.Writer w = new BridgeCodec.Writer(64);
            w.deltaHeader(BridgeCodec.DELTA_PLAYER_STATE);
            w.string(key);
            w.i64(selfEntityId());
            w.vec3f(pos.getX(), pos.getY(), pos.getZ());
            w.f32(rotation.getY());
            w.f32(rotation.getX());
            send(w);

            // Block actions (modern path).
            for (PlayerBlockActionData action : p.getPlayerActions()) {
                PacketSignal signal = handleBlockAction(action.getAction(), action.getBlockPosition(), action.getFace());
                if (signal == PacketSignal.HANDLED) {
                    return PacketSignal.HANDLED;
                }
            }
            // (movement deltas flow through the state delta above)
            // Sneak/sprint toggles.
            Set<PlayerAuthInputData> inputData = p.getInputData();
            boolean sneakNow = inputData.contains(PlayerAuthInputData.SNEAKING);
            if (sneakNow != this.sneaking) {
                this.sneaking = sneakNow;
                this.events.callEvent(new PlayerEvents.ToggleSneak(compatPlayer(), key, dimension(), sneakNow));
            }
            boolean sprintNow = inputData.contains(PlayerAuthInputData.SPRINTING);
            if (sprintNow != this.sprinting) {
                this.sprinting = sprintNow;
                this.events.callEvent(new PlayerEvents.ToggleSprint(compatPlayer(), key, dimension(), sprintNow));
            }
            // Modern item use on block rides the auth input.
            var itemUse = p.getItemUseTransaction();
            if (itemUse != null) {
                return handleItemUse(itemUse.getActionType(), itemUse.getBlockPosition(), itemUse.getBlockFace(),
                        itemUse.getItemInHand());
            }
            return PacketSignal.UNHANDLED;
        }

        if (packet instanceof PlayerActionPacket p) {
            switch (p.getAction()) {
                case START_SLEEP -> {
                    Vector3i pos = p.getBlockPosition();
                    BlockEventsDummy();
                    return fireSyncCancellable(
                            new PlayerEvents.BedEnter(compatPlayer(), key, dimension(),
                                    new BlockPos(pos.getX(), pos.getY(), pos.getZ())),
                            BridgeCodec.EVENT_PLAYER_BED_ENTER,
                            bedEnterFields(key, pos)) ? PacketSignal.HANDLED : PacketSignal.UNHANDLED;
                }
                case STOP_SLEEP -> {
                    this.events.callEvent(new PlayerEvents.BedLeave(compatPlayer(), key, dimension()));
                    return PacketSignal.UNHANDLED;
                }
                default -> {
                    return handleBlockAction(p.getAction(), p.getBlockPosition(), p.getFace());
                }
            }
        }

        if (packet instanceof InventoryTransactionPacket p) {
            InventoryTransactionType type = p.getTransactionType();
            if (type == InventoryTransactionType.ITEM_USE) {
                // Use-on-block (place or interact attempt): cancellable path.
                return handleItemUse(p.getActionType(), p.getBlockPosition(), p.getBlockFace(), p.getItemInHand());
            }
            if (type == InventoryTransactionType.ITEM_RELEASE) {
                // Item drop / consume: the drop is cancellable, the item stays.
                return fireSyncCancellable(
                        new PlayerEvents.DropItem(compatPlayer(), key, dimension(),
                                itemView(p.getItemInHand()), p.getItemInHand() != null ? p.getItemInHand().getCount() : 0),
                        BridgeCodec.EVENT_PLAYER_DROP_ITEM,
                        List.of(BridgeCodec.Field.ofStr(key), BridgeCodec.Field.ofI32(dimension()),
                                BridgeCodec.Field.ofStr(this.player.getName()),
                                BridgeCodec.Field.ofItem(wireItem(p.getItemInHand())),
                                BridgeCodec.Field.ofI32(p.getItemInHand() != null ? p.getItemInHand().getCount() : 0)))
                        ? PacketSignal.HANDLED : PacketSignal.UNHANDLED;
            }
            if (type == InventoryTransactionType.NORMAL || type == InventoryTransactionType.INVENTORY_MISMATCH) {
                return fireSyncCancellable(
                        new InventoryEvents.Transaction(compatPlayer(), key, dimension(),
                                type != null ? type.ordinal() : -1, p.getActionType()),
                        BridgeCodec.EVENT_INVENTORY_TRANSACTION,
                        List.of(BridgeCodec.Field.ofStr(key), BridgeCodec.Field.ofI32(dimension()),
                                BridgeCodec.Field.ofStr(this.player.getName()),
                                BridgeCodec.Field.ofI32(type != null ? type.ordinal() : -1),
                                BridgeCodec.Field.ofI32(p.getActionType())))
                        ? PacketSignal.HANDLED : PacketSignal.UNHANDLED;
            }
            return PacketSignal.UNHANDLED;
        }

        if (packet instanceof ItemStackRequestPacket p) {
            boolean craft = false;
            int recipeNetId = -1;
            StringBuilder actions = new StringBuilder();
            for (var request : p.getRequests()) {
                for (var action : request.getActions()) {
                    String typeName = action.getType() != null ? action.getType().name() : "";
                    if (typeName.contains("Craft")) {
                        craft = true;
                        if (action instanceof org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.request.action.CraftRecipeAction craftAction) {
                            recipeNetId = craftAction.getRecipeNetworkId();
                        }
                    }
                    if (actions.length() < 240) {
                        if (actions.length() > 0) {
                            actions.append(',');
                        }
                        actions.append(typeName);
                    }
                }
            }
            if (!craft) {
                return PacketSignal.UNHANDLED;
            }
            InventoryEvents.Craft event = new InventoryEvents.Craft(compatPlayer(), key, dimension(),
                    recipeNetId, actions.toString());
            boolean cancelled = fireSyncCancellable(event, BridgeCodec.EVENT_CRAFT_ITEM,
                    craftFields(key, recipeNetId, actions.toString()));
            if (cancelled) {
                // Real cancel: answer the client with a proper rejection.
                var response = new org.cloudburstmc.protocol.bedrock.packet.ItemStackResponsePacket();
                var entry = new org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.response.ItemStackResponse(
                        org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.response.ItemStackResponseStatus.ERROR,
                        -1, List.of());
                response.getEntries().add(entry);
                this.player.getConnection().sendPacket(response);
                return PacketSignal.HANDLED;
            }
            return PacketSignal.UNHANDLED;
        }

        if (packet instanceof BlockEntityDataPacket p) {
            NbtMap tag = p.getData();
            String id = tag.getString("id", "");
            if (!id.contains("sign")) {
                return PacketSignal.UNHANDLED;
            }
            String[] lines = new String[4];
            java.util.Arrays.fill(lines, "");
            fillSignSide(tag, "FrontText", lines, 0);
            if (lines[0].isEmpty()) {
                fillSignSide(tag, "", lines, 0);
            }
            Vector3i pos = p.getBlockPosition();
            BlockEvents.SignChange event = new BlockEvents.SignChange(compatPlayer(), key, dimension(),
                    new BlockPos(pos.getX(), pos.getY(), pos.getZ()), lines);
            List<BridgeCodec.Field> fields = new ArrayList<>();
            fields.add(BridgeCodec.Field.ofStr(key));
            fields.add(BridgeCodec.Field.ofI32(dimension()));
            fields.add(BridgeCodec.Field.ofStr(this.player.getName()));
            fields.add(BridgeCodec.Field.ofVec3i(pos.getX(), pos.getY(), pos.getZ()));
            fields.add(BridgeCodec.Field.ofBytes(String.join("\0", lines).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            return fireSyncCancellable(event, BridgeCodec.EVENT_SIGN_CHANGE, fields)
                    ? PacketSignal.HANDLED : PacketSignal.UNHANDLED;
        }

        if (packet instanceof MobEquipmentPacket p) {
            this.heldItem = p.getItem();
            BridgeCodec.Writer w = new BridgeCodec.Writer(96);
            w.deltaHeader(BridgeCodec.DELTA_PLAYER_EQUIP);
            w.string(key);
            w.i64(selfEntityId());
            w.i32(p.getHotbarSlot());
            w.i32(p.getInventorySlot());
            w.item(wireItem(p.getItem()));
            send(w);
            this.events.callEvent(new PlayerEvents.ItemHeld(compatPlayer(), key, dimension(),
                    itemView(p.getItem()), p.getHotbarSlot(), p.getInventorySlot()));
            return PacketSignal.UNHANDLED;
        }

        if (packet instanceof InteractPacket p) {
            if (p.getAction() == InteractPacket.Action.MOUSEOVER) {
                this.events.callEvent(new PlayerEvents.MouseOverEntity(compatPlayer(), key, dimension(),
                        this.worldService.getEntity(key, p.getRuntimeEntityId())));
                return PacketSignal.UNHANDLED;
            }
            if (p.getAction() == InteractPacket.Action.INTERACT) {
                return fireSyncCancellable(
                        new PlayerEvents.InteractEntity(compatPlayer(), key, dimension(),
                                this.worldService.getEntity(key, p.getRuntimeEntityId())),
                        BridgeCodec.EVENT_PLAYER_INTERACT_ENTITY,
                        List.of(BridgeCodec.Field.ofStr(key), BridgeCodec.Field.ofI32(dimension()),
                                BridgeCodec.Field.ofStr(this.player.getName()),
                                BridgeCodec.Field.ofI64(p.getRuntimeEntityId()),
                                BridgeCodec.Field.ofStr(this.entityTypes.getOrDefault(p.getRuntimeEntityId(), ""))))
                        ? PacketSignal.HANDLED : PacketSignal.UNHANDLED;
            }
            return PacketSignal.UNHANDLED;
        }

        if (packet instanceof ModalFormResponsePacket p) {
            return fireSyncCancellable(
                    new PlayerEvents.FormRespond(compatPlayer(), key, dimension(), p.getFormId(), p.getFormData()),
                    BridgeCodec.EVENT_PLAYER_FORM_RESPOND,
                    List.of(BridgeCodec.Field.ofStr(key), BridgeCodec.Field.ofI32(dimension()),
                            BridgeCodec.Field.ofStr(this.player.getName()),
                            BridgeCodec.Field.ofI32(p.getFormId()),
                            BridgeCodec.Field.ofStr(p.getFormData() == null ? "" : p.getFormData())))
                    ? PacketSignal.HANDLED : PacketSignal.UNHANDLED;
        }

        if (packet instanceof AnimatePacket p) {
            if (p.getAction() == AnimatePacket.Action.SWING_ARM) {
                return fireSyncCancellable(
                        new PlayerEvents.Animation(compatPlayer(), key, dimension(),
                                p.getAction() != null ? p.getAction().ordinal() : 0),
                        BridgeCodec.EVENT_PLAYER_ANIMATION,
                        List.of(BridgeCodec.Field.ofStr(key), BridgeCodec.Field.ofI32(dimension()),
                                BridgeCodec.Field.ofStr(this.player.getName()),
                                BridgeCodec.Field.ofI32(p.getAction() != null ? p.getAction().ordinal() : 0)))
                        ? PacketSignal.HANDLED : PacketSignal.UNHANDLED;
            }
            return PacketSignal.UNHANDLED;
        }

        return PacketSignal.UNHANDLED;
    }

    private PacketSignal handleBlockAction(PlayerActionType action, Vector3i pos, int face) {
        String key = serverKey();
        if (pos == null) {
            return PacketSignal.UNHANDLED;
        }
        switch (action) {
            case START_BREAK -> {
                PlayerEvents.Interact interact = new PlayerEvents.Interact(compatPlayer(), key, dimension(),
                        new BlockPos(pos.getX(), pos.getY(), pos.getZ()), face, heldItemView(),
                        PlayerEvents.ACTION_LEFT_CLICK_BLOCK);
                BlockEvents.StartBreak startBreak = new BlockEvents.StartBreak(compatPlayer(), key, dimension(),
                        new BlockPos(pos.getX(), pos.getY(), pos.getZ()), heldItemView(), face);
                List<BridgeCodec.Field> fields = blockActionFields(key, pos, face);
                boolean cancelled = fireSyncCancellable(interact, BridgeCodec.EVENT_PLAYER_INTERACT, fields);
                boolean cancelledStart = cancelled || fireSyncCancellable(startBreak, BridgeCodec.EVENT_BLOCK_START_BREAK, fields);
                return cancelledStart ? PacketSignal.HANDLED : PacketSignal.UNHANDLED;
            }
            case STOP_BREAK -> {
                BlockEvents.Break event = new BlockEvents.Break(compatPlayer(), key, dimension(),
                        new BlockPos(pos.getX(), pos.getY(), pos.getZ()), heldItemView(), face);
                return fireSyncCancellable(event, BridgeCodec.EVENT_BLOCK_BREAK, blockActionFields(key, pos, face))
                        ? PacketSignal.HANDLED : PacketSignal.UNHANDLED;
            }
            case ABORT_BREAK -> {
                return PacketSignal.UNHANDLED;
            }
            default -> {
                return PacketSignal.UNHANDLED;
            }
        }
    }

    private PacketSignal handleItemUse(int actionType, Vector3i pos, int face, ItemData itemData) {
        if (pos == null) {
            return PacketSignal.UNHANDLED;
        }
        String key = serverKey();
        boolean rightClick = actionType != 2; // 2 = break action in the legacy item-use numbering
        PlayerEvents.Interact interact = new PlayerEvents.Interact(compatPlayer(), key, dimension(),
                new BlockPos(pos.getX(), pos.getY(), pos.getZ()), face, itemView(itemData),
                rightClick ? PlayerEvents.ACTION_RIGHT_CLICK_BLOCK : PlayerEvents.ACTION_LEFT_CLICK_BLOCK);
        BlockEvents.Place place = new BlockEvents.Place(compatPlayer(), key, dimension(),
                new BlockPos(pos.getX(), pos.getY(), pos.getZ()), itemView(itemData), face);
        List<BridgeCodec.Field> fields = blockActionFields(key, pos, face);
        fields.add(BridgeCodec.Field.ofItem(wireItem(itemData)));
        fields.add(BridgeCodec.Field.ofI32(rightClick
                ? PlayerEvents.ACTION_RIGHT_CLICK_BLOCK : PlayerEvents.ACTION_LEFT_CLICK_BLOCK));
        boolean cancelled = fireSyncCancellable(interact, BridgeCodec.EVENT_PLAYER_INTERACT, fields);
        boolean cancelledPlace = cancelled || fireSyncCancellable(place, BridgeCodec.EVENT_BLOCK_PLACE, fields);
        return cancelledPlace ? PacketSignal.HANDLED : PacketSignal.UNHANDLED;
    }

    private void BlockEventsDummy() {
        // placeholder kept for symmetry with legacy sleep handling
    }

    private List<BridgeCodec.Field> blockActionFields(String key, Vector3i pos, int face) {
        List<BridgeCodec.Field> fields = new ArrayList<>();
        fields.add(BridgeCodec.Field.ofStr(key));
        fields.add(BridgeCodec.Field.ofI32(dimension()));
        fields.add(BridgeCodec.Field.ofStr(this.player.getName()));
        fields.add(BridgeCodec.Field.ofVec3i(pos.getX(), pos.getY(), pos.getZ()));
        fields.add(BridgeCodec.Field.ofItem(wireItem(this.heldItem)));
        fields.add(BridgeCodec.Field.ofI32(face));
        return fields;
    }

    private List<BridgeCodec.Field> bedEnterFields(String key, Vector3i pos) {
        List<BridgeCodec.Field> fields = new ArrayList<>();
        fields.add(BridgeCodec.Field.ofStr(key));
        fields.add(BridgeCodec.Field.ofI32(dimension()));
        fields.add(BridgeCodec.Field.ofStr(this.player.getName()));
        fields.add(BridgeCodec.Field.ofVec3i(pos.getX(), pos.getY(), pos.getZ()));
        return fields;
    }

    private List<BridgeCodec.Field> craftFields(String key, int recipeNetId, String actions) {
        List<BridgeCodec.Field> fields = new ArrayList<>();
        fields.add(BridgeCodec.Field.ofStr(key));
        fields.add(BridgeCodec.Field.ofI32(dimension()));
        fields.add(BridgeCodec.Field.ofStr(this.player.getName()));
        fields.add(BridgeCodec.Field.ofI32(recipeNetId));
        fields.add(BridgeCodec.Field.ofStr(actions));
        return fields;
    }

    private ItemView heldItemView() {
        ItemData held = this.heldItem;
        return held == null ? ItemView.AIR : itemView(held);
    }

    private void fillSignSide(NbtMap tag, String side, String[] lines, int offset) {
        Object section = side.isEmpty() ? tag : tag.getCompound(side);
        if (!(section instanceof NbtMap map)) {
            return;
        }
        Object text = map.getString("Text", "");
        if (text instanceof String s && !s.isEmpty()) {
            String[] parts = s.split("\n");
            for (int i = 0; i < Math.min(parts.length, 4 - offset); i++) {
                lines[offset + i] = parts[i];
            }
        }
    }
}
