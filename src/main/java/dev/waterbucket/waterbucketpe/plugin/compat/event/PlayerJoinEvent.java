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

import dev.waterbucket.waterbucketpe.plugin.compat.Player;

/**
 * Fired when a player finishes the proxy login: the player is registered, address and identity
 * are known, and the first downstream server is being connected. Exactly the moment the
 * original server software raises its join event.
 */
public class PlayerJoinEvent extends CompatPlayerEvent {

    /**
     * The message broadcast to every online player after the event; null broadcasts nothing.
     */
    private String joinMessage;

    public PlayerJoinEvent(Player player, String joinMessage) {
        super(player);
        this.joinMessage = joinMessage;
    }

    public String getJoinMessage() {
        return this.joinMessage;
    }

    public void setJoinMessage(String joinMessage) {
        this.joinMessage = joinMessage;
    }
}
