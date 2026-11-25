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

package dev.waterbucket.waterbucketpe.plugin.compat.world;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.waterbucket.waterbucketpe.network.goruntime.BridgeCodec;
import dev.waterbucket.waterbucketpe.network.goruntime.GoRuntimeManager;
import lombok.extern.log4j.Log4j2;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The Java-side entry to the live world model: every call here is a bounded
 * query against the Go runtime, answered from real streamed world data. When
 * the runtime is down, queries answer "unloaded" instead of throwing, so
 * plugins never break the packet pipeline.
 * <p>
 * Hot block lookups pass through a small TTL cache: repeated queries for the
 * same position (region guards, walk plugins) collapse into one RPC.
 */
@Log4j2
public final class WorldService {

    private static final long QUERY_TIMEOUT_MS = 150;
    private static final long BLOCK_CACHE_TTL_MS = 50;
    private static final int BLOCK_CACHE_MAX = 16384;

    private static final Gson GSON = new Gson();

    private final GoRuntimeManager runtime;
    private final Map<Long, CachedBlock> blockCache = new ConcurrentHashMap<>();
    private final Map<String, TrackedPlayer> trackedPlayers = new ConcurrentHashMap<>();

    private record CachedBlock(BlockView view, long expiresAt) {
    }

    public record TrackedPlayer(String serverKey, long eid, String name) {
    }

    public WorldService(GoRuntimeManager runtime) {
        this.runtime = runtime;
    }

    // ---- tracking (fed by the delta recorder) ----

    public void trackPlayer(String serverKey, long eid, String name) {
        if (name != null && !name.isEmpty()) {
            this.trackedPlayers.put(name, new TrackedPlayer(serverKey, eid, name));
        }
    }

    public void untrackPlayer(String name) {
        if (name != null) {
            this.trackedPlayers.remove(name);
        }
    }

    /**
     * Resolves a tracked player by name.
     */
    public TrackedPlayer trackedPlayer(String name) {
        return name == null ? null : this.trackedPlayers.get(name);
    }

    // ---- world queries ----

    /**
     * One live block from the world model.
     */
    public BlockView getBlock(String serverKey, int dim, BlockPos pos) {
        if (!this.runtime.isConnected() || serverKey == null || pos == null) {
            return BlockView.UNLOADED;
        }
        long cacheKey = cacheKey(serverKey, dim, pos);
        CachedBlock cached = this.blockCache.get(cacheKey);
        long now = System.currentTimeMillis();
        if (cached != null && cached.expiresAt() > now) {
            return cached.view();
        }
        try {
            BridgeCodec.Reader result = this.runtime.query(BridgeCodec.QUERY_GET_BLOCK, List.of(
                    BridgeCodec.Field.ofStr(serverKey),
                    BridgeCodec.Field.ofI32(dim),
                    BridgeCodec.Field.ofVec3i(pos.x(), pos.y(), pos.z())), QUERY_TIMEOUT_MS);
            BridgeCodec.Field nameField = result.field();
            BridgeCodec.Field propsField = result.field();
            String name = nameField.str();
            Map<String, String> props = parseProps(propsField.str());
            BlockView view = new BlockView(name, -1, pos, props);
            if (this.blockCache.size() > BLOCK_CACHE_MAX) {
                this.blockCache.clear();
            }
            this.blockCache.put(cacheKey, new CachedBlock(view, now + BLOCK_CACHE_TTL_MS));
            return view;
        } catch (GoRuntimeManager.QueryException e) {
            return BlockView.UNLOADED;
        } catch (Exception e) {
            return BlockView.UNLOADED;
        }
    }

    private static long cacheKey(String serverKey, int dim, BlockPos pos) {
        long h = serverKey.hashCode();
        h = h * 31 + dim;
        h = h * 1000003 + pos.x();
        h = h * 1000003 + pos.y();
        h = h * 1000003 + pos.z();
        return h;
    }

    private static Map<String, String> parseProps(String json) {
        if (json == null || json.isEmpty() || "{}".equals(json)) {
            return Map.of();
        }
        try {
            Map<String, String> out = new LinkedHashMap<>();
            for (Map.Entry<String, JsonElement> e : GSON.fromJson(json, JsonObject.class).entrySet()) {
                out.put(e.getKey(), e.getValue().getAsString());
            }
            return out;
        } catch (Exception e) {
            return Map.of();
        }
    }

    /**
     * Whether a chunk is resident in the model.
     */
    public boolean isChunkLoaded(String serverKey, int dim, int cx, int cz) {
        if (!this.runtime.isConnected() || serverKey == null) {
            return false;
        }
        try {
            BridgeCodec.Reader result = this.runtime.query(BridgeCodec.QUERY_CHUNK_LOADED, List.of(
                    BridgeCodec.Field.ofStr(serverKey),
                    BridgeCodec.Field.ofI32(dim),
                    BridgeCodec.Field.ofI32(cx),
                    BridgeCodec.Field.ofI32(cz)), QUERY_TIMEOUT_MS);
            return result.bool();
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * A chunk view: subchunk count plus every observed block entity.
     */
    public ChunkView getChunk(String serverKey, int dim, int cx, int cz) {
        if (!this.runtime.isConnected() || serverKey == null) {
            return new ChunkView(dim, cx, cz, 0, Map.of());
        }
        try {
            BridgeCodec.Reader result = this.runtime.query(BridgeCodec.QUERY_CHUNK_INFO, List.of(
                    BridgeCodec.Field.ofStr(serverKey),
                    BridgeCodec.Field.ofI32(dim),
                    BridgeCodec.Field.ofI32(cx),
                    BridgeCodec.Field.ofI32(cz)), QUERY_TIMEOUT_MS);
            int subs = result.field().i32();
            result.field(); // block entity count (the JSON array below is the payload)
            String besJson = result.field().str();
            Map<Long, BlockEntityView> bes = new LinkedHashMap<>();
            JsonArray array = safeArray(besJson);
            for (JsonElement element : array) {
                JsonObject o = element.getAsJsonObject();
                BlockPos pos = new BlockPos(o.get("x").getAsInt(), o.get("y").getAsInt(), o.get("z").getAsInt());
                bes.put(ChunkView.packPos(pos.x(), pos.y(), pos.z()), new BlockEntityView(
                        o.get("type").getAsString(), pos, nbtMap(o.get("nbt"))));
            }
            return new ChunkView(dim, cx, cz, subs, bes);
        } catch (Exception e) {
            return new ChunkView(dim, cx, cz, 0, Map.of());
        }
    }

    /**
     * One block entity, or null.
     */
    public BlockEntityView getBlockEntity(String serverKey, int dim, BlockPos pos) {
        if (!this.runtime.isConnected() || serverKey == null || pos == null) {
            return null;
        }
        try {
            BridgeCodec.Reader result = this.runtime.query(BridgeCodec.QUERY_BLOCK_ENTITY, List.of(
                    BridgeCodec.Field.ofStr(serverKey),
                    BridgeCodec.Field.ofI32(dim),
                    BridgeCodec.Field.ofVec3i(pos.x(), pos.y(), pos.z())), QUERY_TIMEOUT_MS);
            String type = result.field().str();
            String nbtJson = result.field().str();
            JsonElement element;
            try {
                element = GSON.fromJson(nbtJson, JsonElement.class);
            } catch (Exception parseError) {
                element = null;
            }
            return new BlockEntityView(type, pos, nbtMap(element));
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * One entity snapshot, or null.
     */
    public EntityView getEntity(String serverKey, long eid) {
        if (!this.runtime.isConnected() || serverKey == null) {
            return null;
        }
        try {
            BridgeCodec.Reader result = this.runtime.query(BridgeCodec.QUERY_ENTITY, List.of(
                    BridgeCodec.Field.ofStr(serverKey),
                    BridgeCodec.Field.ofI64(eid)), QUERY_TIMEOUT_MS);
            return entityFromJson(result.field().str());
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Entities within a radius of a point.
     */
    public List<EntityView> getEntitiesNear(String serverKey, int dim,
                                            float x, float y, float z, float radius, int limit) {
        if (!this.runtime.isConnected() || serverKey == null) {
            return List.of();
        }
        try {
            BridgeCodec.Reader result = this.runtime.query(BridgeCodec.QUERY_ENTITIES_NEAR, List.of(
                    BridgeCodec.Field.ofStr(serverKey),
                    BridgeCodec.Field.ofI32(dim),
                    BridgeCodec.Field.ofF32(x),
                    BridgeCodec.Field.ofF32(y),
                    BridgeCodec.Field.ofF32(z),
                    BridgeCodec.Field.ofF32(radius),
                    BridgeCodec.Field.ofI32(limit)), QUERY_TIMEOUT_MS);
            List<EntityView> out = new ArrayList<>();
            for (JsonElement element : safeArray(result.field().str())) {
                out.add(entityFromJson(element.getAsJsonObject().toString()));
            }
            return out;
        } catch (Exception e) {
            return List.of();
        }
    }

    /**
     * A tracked player snapshot, or null.
     */
    public PlayerView getPlayer(String serverKey, long eid) {
        if (!this.runtime.isConnected() || serverKey == null) {
            return null;
        }
        try {
            BridgeCodec.Reader result = this.runtime.query(BridgeCodec.QUERY_PLAYER, List.of(
                    BridgeCodec.Field.ofStr(serverKey),
                    BridgeCodec.Field.ofI64(eid)), QUERY_TIMEOUT_MS);
            return playerFromJson(result.field().str());
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * A tracked player by name, or null.
     */
    public PlayerView getPlayerByName(String name) {
        WorldService.TrackedPlayer tracked = this.trackedPlayer(name);
        return tracked == null ? null : this.getPlayer(tracked.serverKey(), tracked.eid());
    }

    /**
     * One inventory window of a player.
     */
    public InventoryView getInventory(String serverKey, long eid, int windowId) {
        if (!this.runtime.isConnected() || serverKey == null) {
            return new InventoryView(windowId, "", List.of());
        }
        try {
            BridgeCodec.Reader result = this.runtime.query(BridgeCodec.QUERY_INVENTORY, List.of(
                    BridgeCodec.Field.ofStr(serverKey),
                    BridgeCodec.Field.ofI64(eid),
                    BridgeCodec.Field.ofI32(windowId)), QUERY_TIMEOUT_MS);
            List<ItemView> items = new ArrayList<>();
            for (JsonElement element : safeArray(result.field().str())) {
                items.add(itemFromJson(element.getAsJsonObject()));
            }
            return new InventoryView(windowId, "", items);
        } catch (Exception e) {
            return new InventoryView(windowId, "", List.of());
        }
    }

    /**
     * Every world currently visible.
     */
    public List<WorldView> listWorlds() {
        if (!this.runtime.isConnected()) {
            return List.of();
        }
        try {
            BridgeCodec.Reader result = this.runtime.query(BridgeCodec.QUERY_WORLD_LIST, List.of(), QUERY_TIMEOUT_MS);
            List<WorldView> out = new ArrayList<>();
            for (JsonElement element : safeArray(result.field().str())) {
                JsonObject o = element.getAsJsonObject();
                JsonObject spawn = o.getAsJsonObject("Spawn");
                out.add(new WorldView(
                        o.get("Key").getAsString(),
                        o.get("Name").getAsString(),
                        o.get("Seed").getAsLong(),
                        new BlockPos(spawn.get("X").getAsInt(), spawn.get("Y").getAsInt(), spawn.get("Z").getAsInt()),
                        o.get("Gamemode").getAsInt(),
                        o.get("Difficulty").getAsInt(),
                        0, false, false, Map.of()));
            }
            return out;
        } catch (Exception e) {
            return List.of();
        }
    }

    /**
     * Full world meta by server key, or null.
     */
    public WorldView worldMeta(String serverKey) {
        if (!this.runtime.isConnected() || serverKey == null) {
            return null;
        }
        try {
            BridgeCodec.Reader result = this.runtime.query(BridgeCodec.QUERY_WORLD_META, List.of(
                    BridgeCodec.Field.ofStr(serverKey)), QUERY_TIMEOUT_MS);
            JsonObject o = GSON.fromJson(result.field().str(), JsonObject.class);
            JsonObject spawn = o.getAsJsonObject("spawn");
            Map<String, String> rules = new HashMap<>();
            JsonObject rulesJson = o.getAsJsonObject("gamerules");
            if (rulesJson != null) {
                for (Map.Entry<String, JsonElement> e : rulesJson.entrySet()) {
                    rules.put(e.getKey(), e.getValue().getAsString());
                }
            }
            return new WorldView(
                    o.get("key").getAsString(),
                    o.get("name").getAsString(),
                    o.get("seed").getAsLong(),
                    new BlockPos(spawn.get("x").getAsInt(), spawn.get("y").getAsInt(), spawn.get("z").getAsInt()),
                    o.get("gamemode").getAsInt(),
                    o.get("difficulty").getAsInt(),
                    o.get("time").getAsInt(),
                    o.get("raining").getAsBoolean(),
                    o.get("thunder").getAsBoolean(),
                    rules);
        } catch (Exception e) {
            return null;
        }
    }

    // ---- JSON helpers ----

    private static JsonArray safeArray(String json) {
        if (json == null || json.isEmpty() || "[]".equals(json)) {
            return new JsonArray();
        }
        try {
            return GSON.fromJson(json, JsonArray.class);
        } catch (Exception e) {
            return new JsonArray();
        }
    }

    private static Map<String, Object> nbtMap(JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return Map.of();
        }
        try {
            Map<String, Object> out = new LinkedHashMap<>();
            JsonObject o = element.getAsJsonObject();
            for (Map.Entry<String, JsonElement> e : o.entrySet()) {
                out.put(e.getKey(), plain(e.getValue()));
            }
            return out;
        } catch (Exception e) {
            return Map.of();
        }
    }

    private static Object plain(JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return null;
        }
        if (element.isJsonPrimitive()) {
            var prim = element.getAsJsonPrimitive();
            if (prim.isBoolean()) {
                return prim.getAsBoolean();
            }
            if (prim.isNumber()) {
                return prim.getAsNumber();
            }
            return prim.getAsString();
        }
        if (element.isJsonArray()) {
            List<Object> out = new ArrayList<>();
            for (JsonElement e : element.getAsJsonArray()) {
                out.add(plain(e));
            }
            return out;
        }
        return nbtMap(element);
    }

    private static ItemView itemFromJson(JsonObject o) {
        if (o == null) {
            return ItemView.AIR;
        }
        String name = o.has("name") ? o.get("name").getAsString() : "";
        return new ItemView(
                name,
                o.has("damage") ? o.get("damage").getAsInt() : 0,
                o.has("count") ? o.get("count").getAsInt() : 0,
                o.has("netId") ? o.get("netId").getAsLong() : 0,
                nbtMap(o.get("nbt")));
    }

    private static EntityView entityFromJson(String json) {
        JsonObject o = GSON.fromJson(json, JsonObject.class);
        if (o == null) {
            return null;
        }
        Map<String, Float> attrs = new LinkedHashMap<>();
        if (o.has("attrs") && o.get("attrs").isJsonObject()) {
            for (Map.Entry<String, JsonElement> e : o.getAsJsonObject("attrs").entrySet()) {
                attrs.put(e.getKey(), e.getValue().getAsFloat());
            }
        }
        return new EntityView(
                o.get("eid").getAsLong(),
                o.has("type") ? o.get("type").getAsString() : "",
                o.has("dim") ? o.get("dim").getAsInt() : 0,
                o.get("x").getAsFloat(), o.get("y").getAsFloat(), o.get("z").getAsFloat(),
                o.has("motX") ? o.get("motX").getAsFloat() : 0f,
                o.has("motY") ? o.get("motY").getAsFloat() : 0f,
                o.has("motZ") ? o.get("motZ").getAsFloat() : 0f,
                o.has("yaw") ? o.get("yaw").getAsFloat() : 0f,
                o.has("pitch") ? o.get("pitch").getAsFloat() : 0f,
                o.has("flags") ? o.get("flags").getAsLong() : 0L,
                attrs,
                o.has("isPlayer") && o.get("isPlayer").getAsBoolean(),
                o.has("name") ? o.get("name").getAsString() : "",
                o.has("xuid") ? o.get("xuid").getAsString() : "");
    }

    private static PlayerView playerFromJson(String json) {
        JsonObject o = GSON.fromJson(json, JsonObject.class);
        if (o == null) {
            return null;
        }
        EntityView entity = entityFromJson(json);
        ItemView held = o.has("heldItem") ? itemFromJson(o.getAsJsonObject("heldItem")) : ItemView.AIR;
        return new PlayerView(entity,
                o.has("gamemode") ? o.get("gamemode").getAsInt() : 0,
                held,
                o.has("worldKey") ? o.get("worldKey").getAsString() : "");
    }
}
