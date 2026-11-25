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

import dev.waterbucket.waterbucketpe.event.AsyncEvent;
import dev.waterbucket.waterbucketpe.network.connection.client.ClientConnection;
import dev.waterbucket.waterbucketpe.player.ProxiedPlayer;
import lombok.Getter;

/**
 * Called when PlayStatusPacket with PLAYER_SPAWN from downstream server is received.
 * At this point player is really connected to downstream server. Some downstream servers have different handlers per login stage.
 * To signalize last stage of login, we can use this event. For example applies on downstream servers of the original Bedrock software generation.
 */
@Getter
@AsyncEvent
public class PostTransferCompleteEvent extends PlayerEvent {

    private final ClientConnection connection;

    public PostTransferCompleteEvent(ClientConnection connection, ProxiedPlayer player) {
        super(player);
        this.connection = connection;
    }

}

