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

/**
 * A tracked player snapshot on a downstream server: the entity view plus the
 * player-specific state (gamemode, held item, tracked world key).
 */
public record PlayerView(EntityView entity, int gamemode, ItemView heldItem, String worldKey) {

    public static final int GAMEMODE_SURVIVAL = 0;
    public static final int GAMEMODE_CREATIVE = 1;
    public static final int GAMEMODE_ADVENTURE = 2;
    public static final int GAMEMODE_SPECTATOR = 3;

    public String name() {
        return this.entity.name();
    }

    public long eid() {
        return this.entity.eid();
    }

    public String gamemodeName() {
        return switch (this.gamemode) {
            case GAMEMODE_SURVIVAL -> "Survival";
            case GAMEMODE_CREATIVE -> "Creative";
            case GAMEMODE_ADVENTURE -> "Adventure";
            case GAMEMODE_SPECTATOR -> "Spectator";
            default -> "Unknown";
        };
    }
}
