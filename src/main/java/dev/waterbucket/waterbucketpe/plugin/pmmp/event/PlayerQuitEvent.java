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

import dev.waterbucket.waterbucketpe.plugin.pmmp.Player;

/**
 * Fired when a player's upstream connection is gone, whatever the cause (kick, client quit,
 * network loss). The reason string is the disconnect text the proxy saw; a quit message set
 * with {@link #setQuitMessage(String)} is broadcast to the remaining players (null = silent).
 */
public class PlayerQuitEvent extends PmmpPlayerEvent {

    private final String reason;
    private String quitMessage;

    public PlayerQuitEvent(Player player, String reason, String quitMessage) {
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
}
