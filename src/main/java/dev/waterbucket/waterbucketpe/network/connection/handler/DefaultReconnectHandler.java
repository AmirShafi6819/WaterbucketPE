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
 * This is a default reconnect handler.
 * The fallback server will always be the next one from the priority list.
 * If the old server is not defined in priorities, the fallback server will be the first server from the same list.
 */
public class DefaultReconnectHandler implements IReconnectHandler {

    @Override
    public ServerInfo getFallbackServer(ProxiedPlayer player, ServerInfo oldServer, ReconnectReason reason, String kickMessage) {
        // From the player rather than the static instance: same object at runtime, testable.
        ProxyServer proxy = player.getProxy();
        List<String> servers = proxy.getConfiguration().getPriorities();
        if (servers.isEmpty()) {
            // Same guard as the join handler: an empty list used to escape as
            // IndexOutOfBoundsException and block the client's IP instead of a clean refusal.
            return null;
        }

        // Walk forward from the old server, skipping names that are not registered or are
        // marked offline (/wbserveroffline) - a fallback must be a server that can accept
        // the player, otherwise the loop returns an entry that fails the same way again.
        int start = oldServer == null ? -1 : servers.indexOf(oldServer.getServerName());
        for (int i = Math.max(start + 1, 0); i < servers.size(); i++) {
            ServerInfo candidate = proxy.getServerInfo(servers.get(i));
            if (candidate != null && !candidate.isDisabled() && candidate != oldServer) {
                return candidate;
            }
        }
        // Nothing usable after the old server: fall back to the first available entry
        for (String name : servers) {
            ServerInfo candidate = proxy.getServerInfo(name);
            if (candidate != null && !candidate.isDisabled() && candidate != oldServer) {
                return candidate;
            }
        }
        return null;
    }
}
