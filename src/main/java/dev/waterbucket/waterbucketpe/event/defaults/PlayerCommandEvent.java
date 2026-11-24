/*
 * Copyright 2022 WaterbucketTEAM
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

package dev.waterbucket.waterbucketpe.event.defaults;

import dev.waterbucket.waterbucketpe.event.CancellableEvent;
import dev.waterbucket.waterbucketpe.player.ProxiedPlayer;
import lombok.Getter;
import lombok.Setter;

import java.util.Objects;

/**
 * Called when a player issues a command from in-game, right after the raw text arrives and
 * before the proxy decides whether the command belongs to it or to the downstream server.
 * <p>
 * Unlike {@link DispatchCommandEvent}, which fires once a command is actually dispatched,
 * this event fires for <b>every</b> command a player types, including commands that are not
 * registered on the proxy and would be forwarded to the downstream server unchanged.
 * <p>
 * Canceling the event consumes the command: it is neither executed by the proxy nor passed
 * to the downstream server. The command text can also be rewritten with
 * {@link #setCommand(String)}; the modified text is what the proxy dispatches.
 */
@Getter
public class PlayerCommandEvent extends PlayerEvent implements CancellableEvent {

    /**
     * The full command text as typed by the player, including the leading command prefix.
     */
    private String command;

    private boolean changed;

    public PlayerCommandEvent(ProxiedPlayer player, String command) {
        super(player);
        this.command = command;
    }

    public void setCommand(String command) {
        if (!Objects.equals(this.command, command)) {
            this.changed = true;
        }
        this.command = command;
    }

}
