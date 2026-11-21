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

package dev.waterbucket.waterbucketpe.network.serverinfo;

import dev.waterbucket.waterbucketpe.utils.config.ServerEntry;

import java.net.InetSocketAddress;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentSkipListMap;

/**
 * This class holds runtime information about available ServerInfo instances and known implementations.
 * Custom ServerInfo implementations should be registered by providing ServerInfoType and ServerInfoFactory.
 * Plugins should do this using onStartup() method which is triggered before serialization from the configuration file.
 */
public class ServerInfoMap {
    public static final ServerInfoType DEFAULT_TYPE = ServerInfoType.BEDROCK;

    private final Map<String, ServerInfo> serverList = new ConcurrentSkipListMap<>(String.CASE_INSENSITIVE_ORDER);

    public ServerInfoMap() {
    }

    public ServerInfo get(String name) {
        return this.serverList.get(name);
    }

    /**
     * Looks a server up by the address and port a transfer (or plugin) points at.
     * Matching is done by {@link ServerInfo#matchAddress(String, int)}, which never blocks,
     * so this is safe to call from Netty event loops.
     *
     * @param address host name or literal IP, may be null
     * @param port    port of the target server
     * @return the registered server living at that address and port, or null
     */
    public ServerInfo get(String address, int port) {
        for (ServerInfo serverInfo : this.serverList.values()) {
            if (serverInfo.matchAddress(address, port)) {
                return serverInfo;
            }
        }
        return null;
    }

    public ServerInfo putIfAbsent(String name, ServerInfo info) {
        return this.serverList.putIfAbsent(name, info);
    }

    public ServerInfo remove(String name) {
        return this.serverList.remove(name);
    }

    public ServerInfo put(String name, ServerInfo info) {
        return this.serverList.put(name, info);
    }

    public Collection<ServerInfo> values() {
        return Collections.unmodifiableCollection(this.serverList.values());
    }

    /**
     * To create new ServerInfo instance, this method should be used
     * @param serverName name of the server
     * @param address address used to access the server
     * @param publicAddress address which can accessed from upstream session or null
     * @param serverType ServerInfoType, which refers to ServerInfoFactory
     * @return new instance of ServerInfo
     */
    public ServerInfo createServerInfo(String serverName, InetSocketAddress address, InetSocketAddress publicAddress, ServerInfoType serverType) {
        return serverType.getServerInfoFactory().createServerInfo(serverName, address, publicAddress);
    }

    public ServerInfo fromServerEntry(ServerEntry entry) {
        return this.createServerInfo(entry.getServerName(), entry.getAddress(), entry.getPublicAddress(), entry.getServerInfoType());
    }
}
