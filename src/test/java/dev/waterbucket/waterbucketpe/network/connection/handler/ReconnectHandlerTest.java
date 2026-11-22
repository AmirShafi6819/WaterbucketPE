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

package dev.waterbucket.waterbucketpe.network.connection.handler;

import dev.waterbucket.waterbucketpe.ProxyServer;
import dev.waterbucket.waterbucketpe.logger.MainLogger;
import dev.waterbucket.waterbucketpe.network.serverinfo.ServerInfo;
import dev.waterbucket.waterbucketpe.player.ProxiedPlayer;
import dev.waterbucket.waterbucketpe.utils.config.proxy.ProxyConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Regression tests for the fallback selection: an all-duplicate priority list used to spin the
 * round-robin loop forever on an event loop thread, an unregistered name nullified the whole
 * fallback, and an empty priority list escaped as IndexOutOfBoundsException.
 */
public class ReconnectHandlerTest {

    private static Field instanceField;

    private ProxyServer proxy;

    @BeforeEach
    void setUp() throws Exception {
        if (instanceField == null) {
            instanceField = ProxyServer.class.getDeclaredField("instance");
            instanceField.setAccessible(true);
        }
        this.proxy = mock(ProxyServer.class, RETURNS_DEEP_STUBS);
        when(this.proxy.getLogger()).thenReturn(mock(MainLogger.class));
        instanceField.set(null, this.proxy);
    }

    @AfterEach
    void tearDown() throws Exception {
        instanceField.set(null, null);
    }

    private void priorities(String... names) {
        ProxyConfig config = mock(ProxyConfig.class);
        when(config.getPriorities()).thenReturn(Arrays.asList(names));
        when(this.proxy.getConfiguration()).thenReturn(config);
    }

    private ServerInfo server(String name) {
        ServerInfo info = mock(ServerInfo.class);
        when(info.getServerName()).thenReturn(name);
        when(this.proxy.getServerInfo(name)).thenReturn(info);
        return info;
    }

    @Test
    void allDuplicatePrioritiesMustNotSpinForever() {
        // ["lobby", "lobby"] passes the size>=2 guard but every entry equals the old server;
        // the old while(hasNext()) loop never terminated and wedged the event loop.
        priorities("lobby", "lobby");
        RoundRobinReconnectHandler handler = new RoundRobinReconnectHandler();

        ServerInfo result = handler.getFallbackServer(mock(ProxiedPlayer.class), this.server("lobby"),
                ReconnectReason.SERVER_KICK, null);

        assertNull(result, "no candidate differs from the old server, so the fallback is null - not a hang");
    }

    @Test
    void roundRobinStillSelectsRegisteredServers() {
        priorities("lobby", "survival", "creative");
        RoundRobinReconnectHandler handler = new RoundRobinReconnectHandler();
        this.server("survival");
        this.server("creative");
        ProxiedPlayer player = mock(ProxiedPlayer.class);

        ServerInfo first = handler.getFallbackServer(player, this.server("lobby"), ReconnectReason.SERVER_KICK, null);
        ServerInfo second = handler.getFallbackServer(player, this.server("survival"), ReconnectReason.SERVER_KICK, null);
        ServerInfo third = handler.getFallbackServer(player, this.server("creative"), ReconnectReason.SERVER_KICK, null);

        // Round-robin semantics are preserved; only termination and stale-name handling changed
        assertNotNull(first);
        assertNotNull(second);
        assertNotNull(third);
    }

    @Test
    void stalePriorityNameSkippedInsteadOfNullingTheFallback() {
        // "ghost" is no longer registered; the old code returned proxy.getServerInfo("ghost")
        // (null) immediately even though another candidate existed.
        priorities("ghost", "survival");
        when(this.proxy.getServerInfo("ghost")).thenReturn(null);
        RoundRobinReconnectHandler handler = new RoundRobinReconnectHandler();
        ServerInfo survival = this.server("survival");

        ServerInfo result = handler.getFallbackServer(mock(ProxiedPlayer.class), this.server("lobby"),
                ReconnectReason.SERVER_KICK, null);

        assertEquals(survival, result);
    }

    @Test
    void emptyPrioritiesReturnNullFromJoinHandler() {
        ProxyConfig config = mock(ProxyConfig.class);
        when(config.getPriorities()).thenReturn(Collections.emptyList());
        when(this.proxy.getConfiguration()).thenReturn(config);
        DefaultJoinHandler handler = new DefaultJoinHandler();

        assertNull(handler.determineServer(mock(ProxiedPlayer.class)));
    }

    @Test
    void emptyPrioritiesReturnNullFromReconnectHandler() {
        ProxyConfig config = mock(ProxyConfig.class);
        when(config.getPriorities()).thenReturn(Collections.emptyList());
        when(this.proxy.getConfiguration()).thenReturn(config);
        DefaultReconnectHandler handler = new DefaultReconnectHandler();

        assertNull(handler.getFallbackServer(mock(ProxiedPlayer.class), null, ReconnectReason.SERVER_KICK, null));
    }

    @Test
    void firstPriorityServerIsChosenOnJoin() {
        priorities("lobby", "survival");
        DefaultJoinHandler handler = new DefaultJoinHandler();
        ServerInfo lobby = this.server("lobby");

        assertEquals(lobby, handler.determineServer(mock(ProxiedPlayer.class)));
    }
}
