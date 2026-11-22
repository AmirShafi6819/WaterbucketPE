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

import dev.waterbucket.waterbucketpe.network.serverinfo.ServerInfo;
import dev.waterbucket.waterbucketpe.player.ProxiedPlayer;
import lombok.Getter;
import lombok.Setter;

/**
 * Signalizing that player is being transferred to a new server.
 * This even is not cancellable. Use {@link ServerTransferRequestEvent} to cancel the
 * request before the proxy dials the target, or
 * {@link dev.waterbucket.waterbucketpe.event.defaults.ServerPreConnectEvent} to hold out
 * or redirect the dial.
 */
@Getter
public class ServerTransferEvent extends PlayerEvent {

    private final ServerInfo sourceServer;
    private final ServerInfo targetServer;
    @Setter
    private boolean transferScreenAllowed = true;

    public ServerTransferEvent(ProxiedPlayer player, ServerInfo sourceServer, ServerInfo targetServer) {
        super(player);
        this.sourceServer = sourceServer;
        this.targetServer = targetServer;
    }

}

