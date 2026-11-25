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
 * Fired when a player submits a command line (leading slash included, exactly as typed).
 * Cancelling stops the command from reaching the downstream server and the proxy command map;
 * editing the message rewrites what is executed.
 */
public class PlayerCommandPreprocessEvent extends PmmpPlayerEvent implements CancellableEvent {

    private String message;
    private boolean cancelled;

    public PlayerCommandPreprocessEvent(Player player, String message) {
        super(player);
        this.message = message;
    }

    public String getMessage() {
        return this.message;
    }

    public void setMessage(String message) {
        this.message = message;
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
