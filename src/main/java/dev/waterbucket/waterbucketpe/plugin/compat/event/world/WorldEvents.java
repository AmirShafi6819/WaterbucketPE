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

package dev.waterbucket.waterbucketpe.plugin.compat.event.world;

import dev.waterbucket.waterbucketpe.event.Event;
import dev.waterbucket.waterbucketpe.plugin.compat.world.BlockPos;
import dev.waterbucket.waterbucketpe.plugin.compat.world.WorldView;

/**
 * World-side events raised from real packets: world identity, chunks, time,
 * weather, difficulty, game rules and spawn position.
 */
public final class WorldEvents {

    private WorldEvents() {
    }

    /**
     * A downstream server's world became visible: identity, seed, spawn,
     * gamemode and difficulty are known from here on.
     */
    public static final class WorldInit extends Event {

        private final String serverKey;
        private final WorldView world;

        public WorldInit(String serverKey, WorldView world) {
            this.serverKey = serverKey;
            this.world = world;
        }

        public String getServerKey() {
            return this.serverKey;
        }

        public WorldView getWorld() {
            return this.world;
        }
    }

    /**
     * The downstream session ended and its world model was dropped.
     */
    public static final class WorldUnload extends Event {

        private final String serverKey;

        public WorldUnload(String serverKey) {
            this.serverKey = serverKey;
        }

        public String getServerKey() {
            return this.serverKey;
        }
    }

    /**
     * A chunk became visible in the model for the first time.
     */
    public static final class ChunkLoad extends Event {

        private final String serverKey;
        private final int dim;
        private final int cx;
        private final int cz;

        public ChunkLoad(String serverKey, int dim, int cx, int cz) {
            this.serverKey = serverKey;
            this.dim = dim;
            this.cx = cx;
            this.cz = cz;
        }

        public String getServerKey() {
            return this.serverKey;
        }

        public int getDimension() {
            return this.dim;
        }

        public int getChunkX() {
            return this.cx;
        }

        public int getChunkZ() {
            return this.cz;
        }
    }

    /**
     * The retention janitor evicted a chunk nobody looked at anymore.
     */
    public static final class ChunkUnload extends Event {

        private final String serverKey;
        private final int dim;
        private final int cx;
        private final int cz;

        public ChunkUnload(String serverKey, int dim, int cx, int cz) {
            this.serverKey = serverKey;
            this.dim = dim;
            this.cx = cx;
            this.cz = cz;
        }

        public String getServerKey() {
            return this.serverKey;
        }

        public int getDimension() {
            return this.dim;
        }

        public int getChunkX() {
            return this.cx;
        }

        public int getChunkZ() {
            return this.cz;
        }
    }

    /**
     * The proxy relayed a chunk to a player.
     */
    public static final class ChunkSend extends Event {

        private final String serverKey;
        private final int dim;
        private final int cx;
        private final int cz;
        private final String playerName;

        public ChunkSend(String serverKey, int dim, int cx, int cz, String playerName) {
            this.serverKey = serverKey;
            this.dim = dim;
            this.cx = cx;
            this.cz = cz;
            this.playerName = playerName;
        }

        public String getServerKey() {
            return this.serverKey;
        }

        public int getDimension() {
            return this.dim;
        }

        public int getChunkX() {
            return this.cx;
        }

        public int getChunkZ() {
            return this.cz;
        }

        public String getPlayerName() {
            return this.playerName;
        }
    }

    /**
     * The world time changed (SetTime).
     */
    public static final class TimeChange extends Event {

        private final String serverKey;
        private final int time;

        public TimeChange(String serverKey, int time) {
            this.serverKey = serverKey;
            this.time = time;
        }

        public String getServerKey() {
            return this.serverKey;
        }

        public int getTime() {
            return this.time;
        }
    }

    /**
     * Rain or thunder started or stopped.
     */
    public static final class WeatherChange extends Event {

        private final String serverKey;
        private final int dim;
        private final boolean raining;
        private final boolean thunder;

        public WeatherChange(String serverKey, int dim, boolean raining, boolean thunder) {
            this.serverKey = serverKey;
            this.dim = dim;
            this.raining = raining;
            this.thunder = thunder;
        }

        public String getServerKey() {
            return this.serverKey;
        }

        public int getDimension() {
            return this.dim;
        }

        public boolean isRaining() {
            return this.raining;
        }

        public boolean isThundering() {
            return this.thunder;
        }
    }

    /**
     * The world difficulty changed.
     */
    public static final class DifficultyChange extends Event {

        private final String serverKey;
        private final int difficulty;

        public DifficultyChange(String serverKey, int difficulty) {
            this.serverKey = serverKey;
            this.difficulty = difficulty;
        }

        public String getServerKey() {
            return this.serverKey;
        }

        public int getDifficulty() {
            return this.difficulty;
        }
    }

    /**
     * One game rule changed.
     */
    public static final class GameRuleChange extends Event {

        private final String serverKey;
        private final String rule;
        private final String value;

        public GameRuleChange(String serverKey, String rule, String value) {
            this.serverKey = serverKey;
            this.rule = rule;
            this.value = value;
        }

        public String getServerKey() {
            return this.serverKey;
        }

        public String getRule() {
            return this.rule;
        }

        public String getValue() {
            return this.value;
        }
    }

    /**
     * The world spawn position changed.
     */
    public static final class SpawnPositionChange extends Event {

        private final String serverKey;
        private final int dim;
        private final BlockPos pos;

        public SpawnPositionChange(String serverKey, int dim, BlockPos pos) {
            this.serverKey = serverKey;
            this.dim = dim;
            this.pos = pos;
        }

        public String getServerKey() {
            return this.serverKey;
        }

        public int getDimension() {
            return this.dim;
        }

        public BlockPos getPosition() {
            return this.pos;
        }
    }
}
