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

import dev.waterbucket.waterbucketpe.event.CancellableEvent;
import dev.waterbucket.waterbucketpe.plugin.compat.Player;

/**
 * Fired when a player reaches the proxy login flow. Cancelling refuses the login: the player
 * is disconnected with the cancel reason before any downstream server is dialed.
 */
public class PlayerLoginEvent extends CompatPlayerEvent implements CancellableEvent {

    private String kickMessage;
    private boolean cancelled;

    public PlayerLoginEvent(Player player, String kickMessage) {
        super(player);
        this.kickMessage = kickMessage;
    }

    /**
     * The disconnect text shown when the event is cancelled.
     */
    public String getKickMessage() {
        return this.kickMessage;
    }

    public void setKickMessage(String kickMessage) {
        this.kickMessage = kickMessage;
    }

    @Override
    public boolean isCancelled() {
        return this.cancelled;
    }

    @Override
    public void setCancelled(boolean cancelled) {
        this.cancelled = cancelled;
    }

    @Override
    public void setCancelled() {
        this.cancelled = true;
    }
}
