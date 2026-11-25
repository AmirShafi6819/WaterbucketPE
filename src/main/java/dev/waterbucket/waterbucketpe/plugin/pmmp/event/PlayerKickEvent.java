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

package dev.waterbucket.waterbucketpe.plugin.pmmp.event;

import dev.waterbucket.waterbucketpe.event.CancellableEvent;
import dev.waterbucket.waterbucketpe.plugin.pmmp.Player;

/**
 * Fired before a compatibility {@link dev.waterbucket.waterbucketpe.plugin.pmmp.Player#kick}
 * disconnects the player. Cancelling keeps the player online. The quit message is what the
 * other clients see when the kick goes through.
 */
public class PlayerKickEvent extends PmmpPlayerEvent implements CancellableEvent {

    private final String reason;
    private String quitMessage;
    private boolean cancelled;

    public PlayerKickEvent(Player player, String reason, String quitMessage) {
        super(player);
        this.reason = reason;
        this.quitMessage = quitMessage;
    }

    public String getReason() {
        return this.reason;
    }

    public String getQuitMessage() {
        return this.quitMessage;
    }

    public void setQuitMessage(String quitMessage) {
        this.quitMessage = quitMessage;
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
