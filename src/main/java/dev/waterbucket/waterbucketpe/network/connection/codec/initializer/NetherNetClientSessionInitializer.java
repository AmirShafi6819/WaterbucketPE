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

package dev.waterbucket.waterbucketpe.network.connection.codec.initializer;

import dev.waterbucket.waterbucketpe.network.connection.TransportProfile;
import dev.waterbucket.waterbucketpe.network.connection.client.ClientConnection;
import dev.waterbucket.waterbucketpe.network.nethernet.NetherNetClientConnection;
import dev.waterbucket.waterbucketpe.network.serverinfo.ServerInfo;
import dev.waterbucket.waterbucketpe.player.ProxiedPlayer;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.util.concurrent.Promise;

/**
 * Downstream connections over NetherNet. Identical to the RakNet path above the frame codec.
 */
public class NetherNetClientSessionInitializer extends ProxiedClientSessionInitializer {

    public NetherNetClientSessionInitializer(ProxiedPlayer player, ServerInfo serverInfo, Promise<ClientConnection> promise) {
        super(player, serverInfo, promise);
    }

    @Override
    protected ChannelHandler getFrameCodec() {
        return ProxiedSessionInitializer.NETHERNET_FRAME_CODEC;
    }

    @Override
    protected TransportProfile getTransportProfile() {
        return TransportProfile.NETHERNET;
    }

    @Override
    protected ClientConnection createConnection(Channel channel) {
        return new NetherNetClientConnection(this.player, this.serverInfo, channel);
    }
}
