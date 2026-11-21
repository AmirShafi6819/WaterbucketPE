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

package dev.waterbucket.waterbucketpe.event.defaults;

import dev.waterbucket.waterbucketpe.event.AsyncEvent;
import dev.waterbucket.waterbucketpe.network.connection.handler.ReconnectReason;
import dev.waterbucket.waterbucketpe.network.serverinfo.ServerInfo;
import dev.waterbucket.waterbucketpe.player.ProxiedPlayer;
import lombok.Getter;

/**
 * Called when a transfer to a new downstream server fails. This is purely informational:
 * where the player goes next is decided by IReconnectHandler.
 * This event is called before IReconnectHandler is notified.
 */
@Getter
@AsyncEvent
public class ServerTransferFailedEvent extends PlayerEvent {

    private final ServerInfo targetServer;
    private final ReconnectReason reason;
    private final String message;
    /**
     * True when the failure happened before StartGame and the player is still connected
     * to the previous downstream server.
     */
    private final boolean recoverable;

    public ServerTransferFailedEvent(ProxiedPlayer player, ServerInfo targetServer, ReconnectReason reason, String message, boolean recoverable) {
        super(player);
        this.targetServer = targetServer;
        this.reason = reason;
        this.message = message;
        this.recoverable = recoverable;
    }
}