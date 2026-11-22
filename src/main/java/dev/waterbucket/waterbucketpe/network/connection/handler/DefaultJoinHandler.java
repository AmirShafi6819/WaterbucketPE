/*
 * Copyright 2023 WaterbucketTEAM
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

package dev.waterbucket.waterbucketpe.network.connection.handler;

import dev.waterbucket.waterbucketpe.ProxyServer;
import dev.waterbucket.waterbucketpe.network.serverinfo.ServerInfo;
import dev.waterbucket.waterbucketpe.player.ProxiedPlayer;

import java.util.List;

/**
 * This is a default implementation of join handler.
 * The determined server will always be the first one from the server priorities list defined in the config file.
 */
public class DefaultJoinHandler implements IJoinHandler {

    @Override
    public ServerInfo determineServer(ProxiedPlayer player) {
        ProxyServer proxy = ProxyServer.getInstance();
        List<String> priorities = proxy.getConfiguration().getPriorities();
        // An empty priorities list used to throw IndexOutOfBoundsException out of the join path,
        // which disconnected the client and additionally blocked its IP; null is the documented
        // "no server" answer and fails the login with a clean message.
        return priorities.isEmpty() ? null : proxy.getServerInfo(priorities.get(0));
    }
}
