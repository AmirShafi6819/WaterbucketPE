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

package dev.waterbucket.waterbucketpe.ping;

import dev.waterbucket.waterbucketpe.ProxyServer;
import dev.waterbucket.waterbucketpe.event.defaults.ProxyPingEvent;
import dev.waterbucket.waterbucketpe.network.serverinfo.BedrockServerInfo;
import dev.waterbucket.waterbucketpe.network.serverinfo.ServerInfo;
import dev.waterbucket.waterbucketpe.network.serverinfo.ServerInfoMap;
import dev.waterbucket.waterbucketpe.utils.config.proxy.ProxyConfig;
import org.cloudburstmc.protocol.bedrock.BedrockPong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests the Velocity-style ping passthrough: mode handling, the fallback to the proxy's own
 * motd while no backend pong is cached, live player counts in description mode and the
 * priority-server resolution that skips disabled servers.
 */
class PingPassthroughManagerTest {

    private ProxyServer proxy;
    private ProxyConfig config;
    private PingPassthroughManager manager;

    @BeforeEach
    void setUp() {
        this.proxy = mock(ProxyServer.class);
        this.config = mock(ProxyConfig.class);
        when(this.proxy.getConfiguration()).thenReturn(this.config);
        this.manager = new PingPassthroughManager(this.proxy);
    }

    private static ProxyPingEvent proxyEvent() {
        return new ProxyPingEvent("Proxy MOTD", "Proxy Sub", "Survival", "MCPE", "1.21.0",
                Collections.emptyList(), 100, null);
    }

    private static BedrockPong backendPong() {
        return new BedrockPong()
                .motd("Backend MOTD")
                .subMotd("Backend Sub")
                .gameType("Creative")
                .playerCount(7)
                .maximumPlayerCount(42);
    }

    @Test
    void disabledModeNeverTouchesTheEvent() {
        when(this.config.getPingPassthrough()).thenReturn(ProxyConfig.PingPassthrough.DISABLED);
        this.manager.setSnapshot(backendPong());

        ProxyPingEvent event = proxyEvent();
        this.manager.apply(event);

        assertEquals("Proxy MOTD", event.getMotd());
        assertEquals("Proxy Sub", event.getSubMotd());
        assertEquals("Survival", event.getGameType());
        assertEquals(100, event.getMaximumPlayerCount());
    }

    @Test
    void descriptionModeSwapsOnlyTheMotd() {
        when(this.config.getPingPassthrough()).thenReturn(ProxyConfig.PingPassthrough.DESCRIPTION);
        this.manager.setSnapshot(backendPong());

        ProxyPingEvent event = proxyEvent();
        this.manager.apply(event);

        assertEquals("Backend MOTD", event.getMotd());
        assertEquals("Backend Sub", event.getSubMotd());
        // Counts and game type stay live in description mode
        assertEquals("Survival", event.getGameType());
        assertEquals(100, event.getMaximumPlayerCount());
        assertEquals(0, event.getPlayerCount(), "live count comes from the proxy, not the snapshot");
    }

    @Test
    void allModeAlsoSwapsCountsAndGameType() {
        when(this.config.getPingPassthrough()).thenReturn(ProxyConfig.PingPassthrough.ALL);
        this.manager.setSnapshot(backendPong());

        ProxyPingEvent event = proxyEvent();
        this.manager.apply(event);

        assertEquals("Backend MOTD", event.getMotd());
        assertEquals("Backend Sub", event.getSubMotd());
        assertEquals("Creative", event.getGameType());
        assertEquals(7, event.getPlayerCount());
        assertEquals(42, event.getMaximumPlayerCount());
    }

    @Test
    void missingSnapshotFallsBackToTheProxyMotd() {
        when(this.config.getPingPassthrough()).thenReturn(ProxyConfig.PingPassthrough.ALL);

        ProxyPingEvent event = proxyEvent();
        this.manager.apply(event);

        assertEquals("Proxy MOTD", event.getMotd(), "no cached backend pong means proxy defaults");
        assertEquals(100, event.getMaximumPlayerCount());
    }

    @Test
    void nullBackendFieldsGetSafeDefaults() {
        when(this.config.getPingPassthrough()).thenReturn(ProxyConfig.PingPassthrough.DESCRIPTION);
        BedrockPong sparse = new BedrockPong().playerCount(1);
        assertNull(sparse.motd());
        this.manager.setSnapshot(sparse);

        ProxyPingEvent event = proxyEvent();
        this.manager.apply(event);

        assertEquals("", event.getMotd(), "a null backend motd is served as empty, never null");
        assertEquals("", event.getSubMotd());
    }

    @Test
    void passthroughServerFollowsThePriorityList() {
        ServerInfoMap map = new ServerInfoMap();
        InetSocketAddress addr = new InetSocketAddress("127.0.0.1", 19133);
        BedrockServerInfo lobby = new BedrockServerInfo("lobby1", addr, addr);
        BedrockServerInfo survival = new BedrockServerInfo("survival", addr, addr);
        map.putIfAbsent("lobby1", lobby);
        map.putIfAbsent("survival", survival);
        when(this.proxy.getServerInfoMap()).thenReturn(map);
        when(this.config.getPriorities()).thenReturn(List.of("survival", "lobby1"));

        assertSame(survival, this.manager.resolvePassthroughServer());
    }

    @Test
    void disabledPriorityEntriesAreSkipped() {
        ServerInfoMap map = new ServerInfoMap();
        InetSocketAddress addr = new InetSocketAddress("127.0.0.1", 19133);
        BedrockServerInfo lobby = new BedrockServerInfo("lobby1", addr, addr);
        BedrockServerInfo survival = new BedrockServerInfo("survival", addr, addr);
        survival.setDisabled(true);
        map.putIfAbsent("lobby1", lobby);
        map.putIfAbsent("survival", survival);
        when(this.proxy.getServerInfoMap()).thenReturn(map);
        when(this.config.getPriorities()).thenReturn(new ArrayList<>(List.of("survival", "lobby1")));

        assertSame(lobby, this.manager.resolvePassthroughServer(),
                "a disabled priority server must not feed the server list");
    }

    @Test
    void unknownPriorityNameFallsBackToAnyEnabledServer() {
        ServerInfoMap map = new ServerInfoMap();
        InetSocketAddress addr = new InetSocketAddress("127.0.0.1", 19133);
        BedrockServerInfo lobby = new BedrockServerInfo("lobby1", addr, addr);
        map.putIfAbsent("lobby1", lobby);
        when(this.proxy.getServerInfoMap()).thenReturn(map);
        when(this.config.getPriorities()).thenReturn(List.of("does-not-exist"));

        assertSame(lobby, this.manager.resolvePassthroughServer());
    }

    @Test
    void noServersAtAllResolvesToNull() {
        when(this.proxy.getServerInfoMap()).thenReturn(new ServerInfoMap());
        when(this.config.getPriorities()).thenReturn(List.of());

        assertNull(this.manager.resolvePassthroughServer());
    }
}
