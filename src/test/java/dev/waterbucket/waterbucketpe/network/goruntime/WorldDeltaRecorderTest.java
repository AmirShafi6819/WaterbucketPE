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

import dev.waterbucket.waterbucketpe.ProxyServer;
import dev.waterbucket.waterbucketpe.event.EventManager;
import dev.waterbucket.waterbucketpe.network.serverinfo.ServerInfo;
import dev.waterbucket.waterbucketpe.player.ProxiedPlayer;
import dev.waterbucket.waterbucketpe.plugin.compat.event.block.BlockEvents;
import dev.waterbucket.waterbucketpe.plugin.compat.event.inventory.InventoryEvents;
import dev.waterbucket.waterbucketpe.plugin.compat.event.player.PlayerEvents;
import dev.waterbucket.waterbucketpe.plugin.compat.event.world.WorldEvents;
import dev.waterbucket.waterbucketpe.plugin.compat.world.ItemView;
import dev.waterbucket.waterbucketpe.plugin.compat.world.WorldService;
import dev.waterbucket.waterbucketpe.utils.config.proxy.WorldViewSettings;
import org.cloudburstmc.math.vector.Vector3i;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.protocol.bedrock.PacketDirection;
import org.cloudburstmc.protocol.bedrock.data.PlayerActionType;
import org.cloudburstmc.protocol.bedrock.data.definitions.SimpleBlockDefinition;
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemData;
import org.cloudburstmc.protocol.bedrock.data.inventory.transaction.InventoryTransactionType;
import org.cloudburstmc.protocol.bedrock.packet.BlockEntityDataPacket;
import org.cloudburstmc.protocol.bedrock.packet.InventoryTransactionPacket;
import org.cloudburstmc.protocol.bedrock.packet.LevelChunkPacket;
import org.cloudburstmc.protocol.bedrock.packet.PlayerActionPacket;
import org.cloudburstmc.protocol.bedrock.packet.SetTimePacket;
import org.cloudburstmc.protocol.bedrock.packet.StartGamePacket;
import org.cloudburstmc.protocol.bedrock.packet.UpdateBlockPacket;
import org.cloudburstmc.protocol.common.PacketSignal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The packet recorder: real protocol packets go in, compatibility events come
 * out, and cancelling an event really drops the packet. The Go runtime stays
 * offline here (sendAsync no-ops, sync verdicts fail open) - the events and
 * cancellation semantics are fully exercised without it.
 */
class WorldDeltaRecorderTest {

    private ProxyServer proxy;
    private EventManager events;
    private ProxiedPlayer player;
    private WorldDeltaRecorder recorder;

    @BeforeEach
    void setUp() {
        this.proxy = mock(ProxyServer.class);
        dev.waterbucket.waterbucketpe.utils.config.proxy.ProxyConfig config =
                mock(dev.waterbucket.waterbucketpe.utils.config.proxy.ProxyConfig.class);
        Mockito.when(config.getIdleThreads()).thenReturn(4);
        when(this.proxy.getConfiguration()).thenReturn(config);
        this.events = new EventManager(this.proxy);
        when(this.proxy.getEventManager()).thenReturn(this.events);

        this.player = mock(ProxiedPlayer.class);
        when(this.player.getProxy()).thenReturn(this.proxy);
        when(this.player.getName()).thenReturn("Steve");
        when(this.player.getRewriteData()).thenReturn(null);
        ServerInfo serverInfo = mock(ServerInfo.class);
        when(serverInfo.getServerName()).thenReturn("lobby");
        when(this.player.getServerInfo()).thenReturn(serverInfo);

        WorldViewSettings settings = new WorldViewSettings();
        settings.enable(true);
        settings.goRuntime(false);
        GoRuntimeManager runtime = new GoRuntimeManager(this.proxy, settings);
        WorldService service = new WorldService(runtime);
        this.recorder = new WorldDeltaRecorder(this.player, runtime, service);
    }

    private List<WorldEvents.TimeChange> timeChanges = new ArrayList<>();
    private List<WorldEvents.ChunkSend> chunkSends = new ArrayList<>();
    private List<BlockEvents.Break> breaks = new ArrayList<>();
    private List<BlockEvents.Update> updates = new ArrayList<>();
    private List<PlayerEvents.DropItem> drops = new ArrayList<>();

    @Test
    void setTimeFiresTimeChange() {
        this.events.subscribe(WorldEvents.TimeChange.class, e -> this.timeChanges.add(e));
        SetTimePacket packet = new SetTimePacket();
        packet.setTime(6000);

        PacketSignal signal = this.recorder.handlePacket(packet, PacketDirection.CLIENT_BOUND);

        assertEquals(PacketSignal.UNHANDLED, signal);
        assertEquals(1, this.timeChanges.size());
        assertEquals(6000, this.timeChanges.get(0).getTime());
    }

    @Test
    void chunkRelayFiresChunkSend() {
        this.chunkSends = new CopyOnWriteArrayList<>();
        this.events.subscribe(WorldEvents.ChunkSend.class, e -> this.chunkSends.add(e));
        LevelChunkPacket packet = new LevelChunkPacket();
        packet.setChunkX(3);
        packet.setChunkZ(-4);
        packet.setData(io.netty.buffer.Unpooled.wrappedBuffer(new byte[]{1, 2, 3}));

        PacketSignal signal = this.recorder.handlePacket(packet, PacketDirection.CLIENT_BOUND);

        assertEquals(PacketSignal.UNHANDLED, signal);
        assertEquals(1, this.chunkSends.size());
        assertEquals(3, this.chunkSends.get(0).getChunkX());
        assertEquals(-4, this.chunkSends.get(0).getChunkZ());
        assertEquals("Steve", this.chunkSends.get(0).getPlayerName());
    }

    @Test
    void cancelledBlockBreakDropsThePacket() {
        this.breaks = new CopyOnWriteArrayList<>();
        this.events.subscribe(BlockEvents.Break.class, e -> {
            this.breaks.add(e);
            e.setCancelled(true);
        });
        PlayerActionPacket packet = new PlayerActionPacket();
        packet.setAction(PlayerActionType.STOP_BREAK);
        packet.setBlockPosition(Vector3i.from(10, 64, -5));
        packet.setFace(2);
        packet.setRuntimeEntityId(1);

        PacketSignal signal = this.recorder.handlePacket(packet, PacketDirection.SERVER_BOUND);

        assertEquals(PacketSignal.HANDLED, signal);
        assertEquals(1, this.breaks.size());
        assertEquals(10, this.breaks.get(0).getBlockPos().x());
        assertTrue(this.breaks.get(0).isCancelled());
    }

    @Test
    void allowedBlockBreakPassesThrough() {
        this.breaks = new CopyOnWriteArrayList<>();
        this.events.subscribe(BlockEvents.Break.class, e -> this.breaks.add(e));
        PlayerActionPacket packet = new PlayerActionPacket();
        packet.setAction(PlayerActionType.STOP_BREAK);
        packet.setBlockPosition(Vector3i.from(1, 2, 3));
        packet.setRuntimeEntityId(1);

        assertEquals(PacketSignal.UNHANDLED,
                this.recorder.handlePacket(packet, PacketDirection.SERVER_BOUND));
        assertEquals(1, this.breaks.size());
        assertFalse(this.breaks.get(0).isCancelled());
    }

    @Test
    void blockUpdateResolvesNamesFromTheStartGamePalette() {
        // The palette arrives with StartGame first.
        StartGamePacket startGame = new StartGamePacket();
        startGame.setLevelName("world");
        startGame.setSeed(42);
        startGame.setDimensionId(0);
        startGame.getGamerules().clear();
        startGame.getBlockProperties().add(new org.cloudburstmc.protocol.bedrock.data.BlockPropertyData(
                "minecraft:stone", NbtMap.EMPTY));
        this.recorder.handlePacket(startGame, PacketDirection.CLIENT_BOUND);

        this.updates = new CopyOnWriteArrayList<>();
        this.events.subscribe(BlockEvents.Update.class, e -> this.updates.add(e));
        UpdateBlockPacket packet = new UpdateBlockPacket();
        packet.setBlockPosition(Vector3i.from(5, 70, 9));
        packet.setDefinition(new SimpleBlockDefinition("minecraft:stone", 0, NbtMap.EMPTY));
        packet.setDataLayer(0);

        this.recorder.handlePacket(packet, PacketDirection.CLIENT_BOUND);

        assertEquals(1, this.updates.size());
        assertEquals("minecraft:stone", this.updates.get(0).getNewName());
    }

    @Test
    void cancelledItemDropDropsThePacket() {
        this.drops = new CopyOnWriteArrayList<>();
        this.events.subscribe(PlayerEvents.DropItem.class, e -> {
            this.drops.add(e);
            e.setCancelled(true);
        });
        InventoryTransactionPacket packet = new InventoryTransactionPacket();
        packet.setTransactionType(InventoryTransactionType.ITEM_RELEASE);
        packet.setItemInHand(ItemData.builder()
                .definition(new org.cloudburstmc.protocol.bedrock.data.definitions.SimpleItemDefinition(
                        "minecraft:dirt", 1, true))
                .count(64)
                .build());

        PacketSignal signal = this.recorder.handlePacket(packet, PacketDirection.SERVER_BOUND);

        assertEquals(PacketSignal.HANDLED, signal);
        assertEquals(1, this.drops.size());
        assertEquals(64, this.drops.get(0).getCount());
        assertEquals("minecraft:dirt", this.drops.get(0).getItem().identifier());
    }

    @Test
    void signEditCarriesTheLines() {
        List<BlockEvents.SignChange> signs = new ArrayList<>();
        this.events.subscribe(BlockEvents.SignChange.class, signs::add);
        BlockEntityDataPacket packet = new BlockEntityDataPacket();
        packet.setBlockPosition(Vector3i.from(0, 64, 0));
        packet.setData(NbtMap.builder()
                .putString("id", "minecraft:sign")
                .putCompound("FrontText", NbtMap.builder().putString("Text", "line one").build())
                .build());

        PacketSignal signal = this.recorder.handlePacket(packet, PacketDirection.SERVER_BOUND);

        assertEquals(PacketSignal.UNHANDLED, signal);
        assertEquals(1, signs.size());
        assertEquals("line one", signs.get(0).getLines()[0]);
    }

    @Test
    void recorderErrorsNeverBreakThePipeline() {
        // A packet with hostile content must not throw out of the recorder.
        LevelChunkPacket packet = new LevelChunkPacket();
        packet.setChunkX(0);
        packet.setChunkZ(0);
        packet.setData(null);

        PacketSignal signal = this.recorder.handlePacket(packet, PacketDirection.CLIENT_BOUND);
        assertEquals(PacketSignal.UNHANDLED, signal);
    }

    @Test
    void worldEventBridgeExposesEveryEventClass() {
        // The interpreted-language facade exposes every new event class.
        var compat = new dev.waterbucket.waterbucketpe.plugin.compat.CompatApi();
        assertTrue(compat.blockBreakEvent() == BlockEvents.Break.class);
        assertTrue(compat.entitySpawnEvent() == dev.waterbucket.waterbucketpe.plugin.compat.event.entity.EntityEvents.Spawn.class);
        assertTrue(compat.inventoryOpenEvent() == InventoryEvents.Open.class);
        assertTrue(compat.playerDeathEvent() == PlayerEvents.Death.class);
        assertTrue(compat.timeChangeEvent() == WorldEvents.TimeChange.class);
    }

    @Test
    void itemViewsFromRealItemData() {
        ItemData data = ItemData.builder()
                .definition(new org.cloudburstmc.protocol.bedrock.data.definitions.SimpleItemDefinition(
                        "minecraft:torch", 2, true))
                .count(32)
                .build();
        var compat = new dev.waterbucket.waterbucketpe.plugin.compat.CompatApi();
        ItemView view = new ItemView(data.getDefinition().getIdentifier(), data.getDamage(),
                data.getCount(), data.getNetId(), null);
        assertEquals("minecraft:torch", view.identifier());
        assertEquals(32, view.count());
        assertFalse(view.isAir());
    }
}
