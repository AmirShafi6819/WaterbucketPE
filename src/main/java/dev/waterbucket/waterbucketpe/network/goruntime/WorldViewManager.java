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
import dev.waterbucket.waterbucketpe.event.EventPriority;
import dev.waterbucket.waterbucketpe.event.defaults.PlayerDisconnectedEvent;
import dev.waterbucket.waterbucketpe.event.defaults.InitialServerConnectedEvent;
import dev.waterbucket.waterbucketpe.event.defaults.ServerConnectedEvent;
import dev.waterbucket.waterbucketpe.network.protocol.handler.PluginPacketHandler;
import dev.waterbucket.waterbucketpe.player.ProxiedPlayer;
import dev.waterbucket.waterbucketpe.plugin.compat.world.WorldService;
import lombok.Getter;
import lombok.extern.log4j.Log4j2;

import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Boots and owns the world view: the Go runtime sidecar, the world query
 * service and one packet recorder per player. Registered once during proxy
 * boot, before plugins load, so the first lifecycle hook already sees worlds.
 */
@Log4j2
public final class WorldViewManager {

    @Getter
    private static volatile WorldViewManager instance;

    @Getter
    private final GoRuntimeManager runtime;
    @Getter
    private final WorldService worldService;
    private final CopyOnWriteArrayList<WorldDeltaRecorder> recorders = new CopyOnWriteArrayList<>();

    private WorldViewManager(ProxyServer proxy) {
        this.runtime = new GoRuntimeManager(proxy, proxy.getConfiguration().getWorldViewSettings());
        this.worldService = new WorldService(this.runtime);
    }

    /**
     * Creates and boots the manager; safe to call once per proxy lifetime.
     */
    public static WorldViewManager init(ProxyServer proxy) {
        WorldViewManager manager = new WorldViewManager(proxy);
        instance = manager;
        manager.runtime.start();

        var events = proxy.getEventManager();
        // First downstream connection: attach the recorder and tell the model
        // who this player is. HIGHEST: world capture is bookkeeping, proxy
        // handlers run first.
        events.subscribe(InitialServerConnectedEvent.class, event -> {
            ProxiedPlayer player = event.getPlayer();
            attachRecorder(player);
            manager.worldService.trackPlayer(manager.serverKey(player), entityId(player), player.getName());
            sendPlayerInfo(manager, player);
        }, EventPriority.HIGHEST);

        // Every server switch: refresh the model's player identity.
        events.subscribe(ServerConnectedEvent.class, event -> {
            ProxiedPlayer player = event.getPlayer();
            attachRecorder(player);
            manager.worldService.trackPlayer(manager.serverKey(player), entityId(player), player.getName());
            sendPlayerInfo(manager, player);
        }, EventPriority.HIGHEST);

        // Leave: detach the recorder and drop the player from the model.
        events.subscribe(PlayerDisconnectedEvent.class, event -> {
            ProxiedPlayer player = event.getPlayer();
            String key = manager.serverKey(player);
            long eid = entityId(player);
            manager.detachRecorder(player);
            manager.worldService.untrackPlayer(player.getName());
            BridgeCodec.Writer w = new BridgeCodec.Writer(48);
            w.deltaHeader(BridgeCodec.DELTA_PLAYER_REMOVE);
            w.string(key);
            w.i64(eid);
            manager.runtime.sendAsync(w.toArray());
            w = new BridgeCodec.Writer(48);
            w.deltaHeader(BridgeCodec.DELTA_SERVER_DOWN);
            w.string(key);
            manager.runtime.sendAsync(w.toArray());
        }, EventPriority.HIGHEST);

        log.info("[WorldView] manager active (world view {}, Go runtime {})",
                manager.runtime.settings().enable() ? "on" : "off",
                manager.runtime.settings().goRuntime() ? "on" : "off");
        return manager;
    }

    private static long entityId(ProxiedPlayer player) {
        return player.getRewriteData() != null ? player.getRewriteData().getOriginalRuntimeEntityId() : -1;
    }

    private String serverKey(ProxiedPlayer player) {
        return player.getServerInfo() != null ? player.getServerInfo().getServerName() : "unknown";
    }

    private static void sendPlayerInfo(WorldViewManager manager, ProxiedPlayer player) {
        BridgeCodec.Writer w = new BridgeCodec.Writer(96);
        w.deltaHeader(BridgeCodec.DELTA_PLAYER_INFO);
        w.string(manager.serverKey(player));
        w.i64(entityId(player));
        w.string(player.getName());
        w.string(player.getXuid());
        w.i32(0); // gamemode arrives with StartGame; the model corrects itself
        w.i32(player.getRewriteData() != null ? player.getRewriteData().getDimension() : 0);
        manager.runtime.sendAsync(w.toArray());
    }

    private static void attachRecorder(ProxiedPlayer player) {
        WorldViewManager manager = instance;
        if (manager == null || player.getPluginPacketHandlers().stream().anyMatch(h -> h instanceof WorldDeltaRecorder)) {
            return;
        }
        PluginPacketHandler recorder = new WorldDeltaRecorder(player, manager.runtime, manager.worldService);
        player.getPluginPacketHandlers().add(recorder);
        manager.recorders.add((WorldDeltaRecorder) recorder);
    }

    private void detachRecorder(ProxiedPlayer player) {
        player.getPluginPacketHandlers().removeIf(h -> h instanceof WorldDeltaRecorder);
        this.recorders.removeIf(r -> r.player() == player);
    }

    /**
     * Stops the sidecar on proxy shutdown.
     */
    public void shutdown() {
        this.runtime.shutdown();
    }
}
