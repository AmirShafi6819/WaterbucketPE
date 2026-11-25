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

package dev.waterbucket.waterbucketpe.plugin.compat.event;

import dev.waterbucket.waterbucketpe.event.Event;
import dev.waterbucket.waterbucketpe.player.ProxiedPlayer;
import dev.waterbucket.waterbucketpe.plugin.compat.Player;

/**
 * Base of the compatibility player events: the events of the well-known PocketMine server
 * software, raised on real proxy moments. {@link #getPlayer()} hands out the compatibility
 * {@link Player} wrapper (its methods are the documented plugin surface); the raw proxy player
 * stays reachable with {@link #getProxiedPlayer()} for anything advanced.
 */
public abstract class CompatPlayerEvent extends Event {

    private final Player player;

    protected CompatPlayerEvent(Player player) {
        this.player = player;
    }

    /**
     * The compatibility player wrapper this event is about.
     */
    public Player getPlayer() {
        return this.player;
    }

    /**
     * The underlying proxy player, for advanced operations outside the compatibility surface.
     */
    public ProxiedPlayer getProxiedPlayer() {
        return this.player.getHandle();
    }
}
