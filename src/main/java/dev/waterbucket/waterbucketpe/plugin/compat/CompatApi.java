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

package dev.waterbucket.waterbucketpe.plugin.compat;

import dev.waterbucket.waterbucketpe.plugin.compat.world.WorldService;
import dev.waterbucket.waterbucketpe.plugin.compat.event.world.WorldEvents;
import dev.waterbucket.waterbucketpe.plugin.compat.event.block.BlockEvents;
import dev.waterbucket.waterbucketpe.plugin.compat.event.entity.EntityEvents;
import dev.waterbucket.waterbucketpe.plugin.compat.event.player.PlayerEvents;
import dev.waterbucket.waterbucketpe.plugin.compat.event.inventory.InventoryEvents;

/**
 * The facade object the interpreted plugin languages (Python, PHP) receive as their
 * {@code compat} global: one entry to the compatibility server, its event classes and the
 * compatibility config utility. Java plugins reach everything directly through this package
 * instead.
 * <p>
 * Event classes are exposed as accessor methods ({@code compat.playerJoinEvent()}) so scripts
 * can subscribe with {@code plugin.subscribe(compat.playerJoinEvent(), handler)} without any
 * Java import syntax; snake_case aliases exist for the same reason.
 */
public class CompatApi {

    /**
     * The compatibility server facade.
     */
    public Server getServer() {
        return Server.getInstance();
    }

    // ---- event classes (for plugin.subscribe(...)) ----

    public Class<dev.waterbucket.waterbucketpe.plugin.compat.event.PlayerJoinEvent> playerJoinEvent() {
        return dev.waterbucket.waterbucketpe.plugin.compat.event.PlayerJoinEvent.class;
    }

    public Class<dev.waterbucket.waterbucketpe.plugin.compat.event.PlayerQuitEvent> playerQuitEvent() {
        return dev.waterbucket.waterbucketpe.plugin.compat.event.PlayerQuitEvent.class;
    }

    public Class<dev.waterbucket.waterbucketpe.plugin.compat.event.PlayerChatEvent> playerChatEvent() {
        return dev.waterbucket.waterbucketpe.plugin.compat.event.PlayerChatEvent.class;
    }

    public Class<dev.waterbucket.waterbucketpe.plugin.compat.event.PlayerCommandPreprocessEvent> playerCommandPreprocessEvent() {
        return dev.waterbucket.waterbucketpe.plugin.compat.event.PlayerCommandPreprocessEvent.class;
    }

    public Class<dev.waterbucket.waterbucketpe.plugin.compat.event.PlayerKickEvent> playerKickEvent() {
        return dev.waterbucket.waterbucketpe.plugin.compat.event.PlayerKickEvent.class;
    }

    public Class<dev.waterbucket.waterbucketpe.plugin.compat.event.PlayerLoginEvent> playerLoginEvent() {
        return dev.waterbucket.waterbucketpe.plugin.compat.event.PlayerLoginEvent.class;
    }

    // ---- snake_case aliases (Python/PHP ergonomics) ----

    public Server get_server() {
        return this.getServer();
    }

    public Class<dev.waterbucket.waterbucketpe.plugin.compat.event.PlayerJoinEvent> player_join_event() {
        return this.playerJoinEvent();
    }

    public Class<dev.waterbucket.waterbucketpe.plugin.compat.event.PlayerQuitEvent> player_quit_event() {
        return this.playerQuitEvent();
    }

    public Class<dev.waterbucket.waterbucketpe.plugin.compat.event.PlayerChatEvent> player_chat_event() {
        return this.playerChatEvent();
    }

    public Class<dev.waterbucket.waterbucketpe.plugin.compat.event.PlayerCommandPreprocessEvent> player_command_preprocess_event() {
        return this.playerCommandPreprocessEvent();
    }

    public Class<dev.waterbucket.waterbucketpe.plugin.compat.event.PlayerKickEvent> player_kick_event() {
        return this.playerKickEvent();
    }

    public Class<dev.waterbucket.waterbucketpe.plugin.compat.event.PlayerLoginEvent> player_login_event() {
        return this.playerLoginEvent();
    }

    /**
     * The compatibility config utility constructor for scripts:
     * {@code cfg = compat.newConfig(plugin.getDataFolder() + "/data.yml")}.
     */
    public Config newConfig(String path) {
        return new Config(java.nio.file.Path.of(path));
    }

    public Config newConfig(String path, String type) {
        return new Config(java.nio.file.Path.of(path),
                "json".equalsIgnoreCase(type) ? Config.Type.JSON : Config.Type.YAML);
    }

    public Config new_config(String path) {
        return this.newConfig(path);
    }

    public Config new_config(String path, String type) {
        return this.newConfig(path, type);
    }

    // ---- world access (live world model through the Go runtime) ----

    /**
     * The live world query service: blocks, block entities, chunks, entities,
     * players, inventories and world meta of every downstream server.
     */
    public WorldService world() {
        return dev.waterbucket.waterbucketpe.network.goruntime.WorldViewManager.getInstance().getWorldService();
    }

    public WorldService get_world() {
        return this.world();
    }

    // ---- world event classes (for plugin.subscribe(...)) ----

    public Class<WorldEvents.WorldInit> worldInitEvent() { return WorldEvents.WorldInit.class; }

    public Class<WorldEvents.ChunkLoad> chunkLoadEvent() { return WorldEvents.ChunkLoad.class; }

    public Class<WorldEvents.ChunkUnload> chunkUnloadEvent() { return WorldEvents.ChunkUnload.class; }

    public Class<WorldEvents.TimeChange> timeChangeEvent() { return WorldEvents.TimeChange.class; }

    public Class<WorldEvents.WeatherChange> weatherChangeEvent() { return WorldEvents.WeatherChange.class; }

    public Class<WorldEvents.DifficultyChange> difficultyChangeEvent() { return WorldEvents.DifficultyChange.class; }

    public Class<WorldEvents.GameRuleChange> gameRuleChangeEvent() { return WorldEvents.GameRuleChange.class; }

    public Class<BlockEvents.Break> blockBreakEvent() { return BlockEvents.Break.class; }

    public Class<BlockEvents.Place> blockPlaceEvent() { return BlockEvents.Place.class; }

    public Class<BlockEvents.StartBreak> blockStartBreakEvent() { return BlockEvents.StartBreak.class; }

    public Class<BlockEvents.Update> blockUpdateEvent() { return BlockEvents.Update.class; }

    public Class<BlockEvents.SignChange> signChangeEvent() { return BlockEvents.SignChange.class; }

    public Class<EntityEvents.Spawn> entitySpawnEvent() { return EntityEvents.Spawn.class; }

    public Class<EntityEvents.Despawn> entityDespawnEvent() { return EntityEvents.Despawn.class; }

    public Class<EntityEvents.Move> entityMoveEvent() { return EntityEvents.Move.class; }

    public Class<EntityEvents.Motion> entityMotionEvent() { return EntityEvents.Motion.class; }

    public Class<EntityEvents.Effect> entityEffectEvent() { return EntityEvents.Effect.class; }

    public Class<EntityEvents.HealthChange> entityHealthChangeEvent() { return EntityEvents.HealthChange.class; }

    public Class<EntityEvents.Damage> entityDamageEvent() { return EntityEvents.Damage.class; }

    public Class<EntityEvents.RegainHealth> entityRegainHealthEvent() { return EntityEvents.RegainHealth.class; }

    public Class<EntityEvents.DataUpdate> entityDataUpdateEvent() { return EntityEvents.DataUpdate.class; }

    public Class<EntityEvents.Teleport> entityTeleportEvent() { return EntityEvents.Teleport.class; }

    public Class<PlayerEvents.Interact> playerInteractEvent() { return PlayerEvents.Interact.class; }

    public Class<PlayerEvents.ItemHeld> playerItemHeldEvent() { return PlayerEvents.ItemHeld.class; }

    public Class<PlayerEvents.DropItem> playerDropItemEvent() { return PlayerEvents.DropItem.class; }

    public Class<PlayerEvents.ToggleSneak> playerToggleSneakEvent() { return PlayerEvents.ToggleSneak.class; }

    public Class<PlayerEvents.ToggleSprint> playerToggleSprintEvent() { return PlayerEvents.ToggleSprint.class; }

    public Class<PlayerEvents.Animation> playerAnimationEvent() { return PlayerEvents.Animation.class; }

    public Class<PlayerEvents.Death> playerDeathEvent() { return PlayerEvents.Death.class; }

    public Class<PlayerEvents.Respawn> playerRespawnEvent() { return PlayerEvents.Respawn.class; }

    public Class<PlayerEvents.GameModeChange> playerGameModeChangeEvent() { return PlayerEvents.GameModeChange.class; }

    public Class<PlayerEvents.ChangeDimension> playerChangeDimensionEvent() { return PlayerEvents.ChangeDimension.class; }

    public Class<PlayerEvents.FormRespond> playerFormRespondEvent() { return PlayerEvents.FormRespond.class; }

    public Class<PlayerEvents.InteractEntity> playerInteractEntityEvent() { return PlayerEvents.InteractEntity.class; }

    public Class<PlayerEvents.MouseOverEntity> playerMouseOverEntityEvent() { return PlayerEvents.MouseOverEntity.class; }

    public Class<PlayerEvents.BedEnter> playerBedEnterEvent() { return PlayerEvents.BedEnter.class; }

    public Class<PlayerEvents.BedLeave> playerBedLeaveEvent() { return PlayerEvents.BedLeave.class; }

    public Class<InventoryEvents.Open> inventoryOpenEvent() { return InventoryEvents.Open.class; }

    public Class<InventoryEvents.Close> inventoryCloseEvent() { return InventoryEvents.Close.class; }

    public Class<InventoryEvents.Update> inventoryUpdateEvent() { return InventoryEvents.Update.class; }

    public Class<InventoryEvents.Craft> craftItemEvent() { return InventoryEvents.Craft.class; }

    public Class<InventoryEvents.Transaction> inventoryTransactionEvent() { return InventoryEvents.Transaction.class; }

    // ---- snake_case aliases ----

    public Class<WorldEvents.WorldInit> world_init_event() { return this.worldInitEvent(); }

    public Class<BlockEvents.Break> block_break_event() { return this.blockBreakEvent(); }

    public Class<BlockEvents.Place> block_place_event() { return this.blockPlaceEvent(); }

    public Class<BlockEvents.SignChange> sign_change_event() { return this.signChangeEvent(); }

    public Class<EntityEvents.Spawn> entity_spawn_event() { return this.entitySpawnEvent(); }

    public Class<EntityEvents.Despawn> entity_despawn_event() { return this.entityDespawnEvent(); }

    public Class<EntityEvents.Move> entity_move_event() { return this.entityMoveEvent(); }

    public Class<EntityEvents.Damage> entity_damage_event() { return this.entityDamageEvent(); }

    public Class<PlayerEvents.Interact> player_interact_event() { return this.playerInteractEvent(); }

    public Class<PlayerEvents.DropItem> player_drop_item_event() { return this.playerDropItemEvent(); }

    public Class<PlayerEvents.Death> player_death_event() { return this.playerDeathEvent(); }

    public Class<PlayerEvents.Respawn> player_respawn_event() { return this.playerRespawnEvent(); }

    public Class<PlayerEvents.ToggleSneak> player_toggle_sneak_event() { return this.playerToggleSneakEvent(); }

    public Class<PlayerEvents.ToggleSprint> player_toggle_sprint_event() { return this.playerToggleSprintEvent(); }

    public Class<InventoryEvents.Open> inventory_open_event() { return this.inventoryOpenEvent(); }

    public Class<InventoryEvents.Close> inventory_close_event() { return this.inventoryCloseEvent(); }

    public Class<InventoryEvents.Craft> craft_item_event() { return this.craftItemEvent(); }
}
