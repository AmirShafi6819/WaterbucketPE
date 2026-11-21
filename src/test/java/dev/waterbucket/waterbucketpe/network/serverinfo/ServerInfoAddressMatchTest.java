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

package dev.waterbucket.waterbucketpe.network.serverinfo;

import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression tests for address matching on the fast-transfer path.
 * <p>
 * Local setups commonly register several downstream servers on 127.0.0.1 with different ports.
 * A transfer target must resolve to exactly the server living on that port, regardless of how
 * the address is spelled (127.0.0.1, localhost, resolved forms), and matching must never
 * perform DNS because it runs on Netty event loops.
 */
class ServerInfoAddressMatchTest {

    private final ServerInfoMap map = new ServerInfoMap();

    private ServerInfo register(String name, String host, int port) {
        // Same shape the config converter produces: unresolved host string + explicit port
        ServerInfo info = new BedrockServerInfo(name, InetSocketAddress.createUnresolved(host, port), null);
        this.map.put(name, info);
        return info;
    }

    @Test
    void sameIpDifferentPortsResolveToTheirOwnServer() {
        register("lobby", "127.0.0.1", 19133);
        register("survival", "127.0.0.1", 19134);
        register("minigames", "127.0.0.1", 19135);

        assertEquals("lobby", this.map.get("127.0.0.1", 19133).getServerName());
        assertEquals("survival", this.map.get("127.0.0.1", 19134).getServerName());
        assertEquals("minigames", this.map.get("127.0.0.1", 19135).getServerName());
    }

    @Test
    void loopbackSpellingsAllMatchTheSameServer() {
        ServerInfo lobby = register("lobby", "127.0.0.1", 19133);

        assertTrue(lobby.matchAddress("127.0.0.1", 19133), "exact literal");
        assertTrue(lobby.matchAddress("localhost", 19133), "localhost is the same machine");
        assertTrue(lobby.matchAddress("LOCALHOST", 19133), "case-insensitive");
        assertTrue(lobby.matchAddress("127.4.19.7", 19133), "any 127.0.0.0/8 spelling is loopback");
        assertTrue(lobby.matchAddress(" 127.0.0.1 ", 19133), "whitespace tolerated");
    }

    @Test
    void serverConfiguredAsLocalhostMatchesLiteral() {
        ServerInfo lobby = register("lobby", "localhost", 19133);

        assertTrue(lobby.matchAddress("localhost", 19133));
        assertTrue(lobby.matchAddress("127.0.0.1", 19133));
        assertTrue(lobby.matchAddress("127.99.99.99", 19133));
    }

    @Test
    void wrongPortNeverMatchesEvenOnLoopback() {
        register("lobby", "127.0.0.1", 19133);

        assertNull(this.map.get("127.0.0.1", 19134), "different port on same IP must not match");
        assertNull(this.map.get("localhost", 19134), "localhost with wrong port must not match");
        assertFalse(this.map.get("127.0.0.1", 19133).matchAddress("127.0.0.1", 19199));
    }

    @Test
    void literalIpMatchesHostnameConfiguredServer() {
        // Server configured by hostname; the cached resolved address seeds synchronously with the
        // configured address, so a literal IP transfer target still resolves to it without DNS.
        ServerInfo info = register("hub", "127.0.0.1", 19140);

        assertTrue(info.matchAddress("127.0.0.1", 19140));
    }

    @Test
    void foreignHostnamesDoNotTriggerLookupsOrMatch() {
        register("lobby", "mc.example.com", 19133);

        // A different hostname is not verifiable without DNS and must not match
        assertNull(this.map.get("other.example.com", 19133));
    }

    @Test
    void malformedAddressesAreRejectedNotThrown() {
        ServerInfo lobby = register("lobby", "127.0.0.1", 19133);

        assertFalse(lobby.matchAddress(null, 19133));
        assertFalse(lobby.matchAddress("", 19133));
        assertFalse(lobby.matchAddress("   ", 19133));
        assertFalse(lobby.matchAddress("not an address", 19133));
        assertFalse(lobby.matchAddress("127.0.0.1", -1));
        assertFalse(lobby.matchAddress("127.0.0.1", 65536));
        assertNull(this.map.get(null, 19133));
    }

    @Test
    void unregisteredPortsReturnNull() {
        register("lobby", "127.0.0.1", 19133);

        assertNull(this.map.get("127.0.0.1", 12345));
        assertNull(this.map.get("example.com", 19133));
    }
}
