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

import java.util.Collection;
import java.util.Iterator;

/**
 * This is a default reconnect handler.
 * The fallback server is chosen from the priority list using a round-robin algorithm.
 */
public class RoundRobinReconnectHandler implements IReconnectHandler {

    private volatile RoundRobinIterator<String> iterator;

    @Override
    public ServerInfo getFallbackServer(ProxiedPlayer player, ServerInfo oldServer, ReconnectReason reason, String kickMessage) {
        ProxyServer proxy = ProxyServer.getInstance();
        if (proxy.getConfiguration().getPriorities().size() < 2) {
            throw new IllegalStateException("RoundRobinReconnectHandler required at least two priority servers set");
        }

        if (this.iterator == null) {
            this.iterator = new RoundRobinIterator<>(proxy.getConfiguration().getPriorities());
        }

        // Bounded selection: the old while(hasNext()) loop could spin forever on the event loop
        // when every priority entry equals the old server (e.g. duplicated names), because the
        // round-robin hasNext() never turns false for a non-empty list. Each server is tried at
        // most once per call; stale names are skipped instead of nulling the whole fallback.
        RoundRobinIterator<String> current = this.iterator;
        for (int i = 0; i < proxy.getConfiguration().getPriorities().size(); i++) {
            String server = current.next();
            if (oldServer != null && server.equals(oldServer.getServerName())) {
                continue;
            }
            ServerInfo info = proxy.getServerInfo(server);
            if (info != null) {
                return info;
            }
        }
        return null;
    }

    private static class RoundRobinIterator<E> implements Iterator<E> {
        private final Collection<E> collection;
        private Iterator<E> iterator;

        public RoundRobinIterator(Collection<E> collection) {
            this.collection = collection;
            this.iterator = this.collection.iterator();
        }

        public synchronized boolean hasNext() {
            return !this.collection.isEmpty();
        }

        public synchronized E next() {
            if (!this.iterator.hasNext()) {
                this.iterator = this.collection.iterator();
            }
            return this.iterator.next();
        }
    }
}
