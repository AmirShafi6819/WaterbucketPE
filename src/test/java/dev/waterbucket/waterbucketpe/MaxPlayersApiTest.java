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

package dev.waterbucket.waterbucketpe;

import dev.waterbucket.waterbucketpe.command.ConsoleCommandSender;
import dev.waterbucket.waterbucketpe.logger.MainLogger;
import dev.waterbucket.waterbucketpe.player.ProxiedPlayer;
import dev.waterbucket.waterbucketpe.utils.config.proxy.ProxyConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers the runtime max-player API exposed to plugins and the /wbmaxplayers command:
 * override vs. config resolution, login enforcement (isFull), the bypass permission and
 * broadcastMessage reaching every player plus the console.
 */
class MaxPlayersApiTest {

    private ProxyServer proxy;
    private ProxyConfig config;

    @BeforeEach
    void setUp() throws Exception {
        this.proxy = mock(ProxyServer.class);
        this.config = mock(ProxyConfig.class);
        when(this.config.getMaxPlayerCount()).thenReturn(20);
        when(this.proxy.getConfiguration()).thenReturn(this.config);
        when(this.proxy.getLogger()).thenReturn(mock(MainLogger.class));
        // On a Mockito mock the override field defaults to 0, not to the constructor's -1;
        // restore the real "no override" default so the config path is exercised.
        Field overrideField = ProxyServer.class.getDeclaredField("maxPlayersOverride");
        overrideField.setAccessible(true);
        overrideField.setInt(this.proxy, -1);

        doCallRealMethod().when(this.proxy).getMaxPlayers();
        doCallRealMethod().when(this.proxy).setMaxPlayers(org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyBoolean());
        doCallRealMethod().when(this.proxy).isFull(any());
        doCallRealMethod().when(this.proxy).broadcastMessage(anyString());
    }

    @Test
    void overrideFallsBackToConfiguredValueWhenNotSet() {
        assertEquals(20, ProxyServer.resolveMaxPlayers(-1, 20), "no override means the config value");
        assertEquals(0, ProxyServer.resolveMaxPlayers(0, 20), "an explicit 0 override disables the limit");
        assertEquals(50, ProxyServer.resolveMaxPlayers(50, 20), "an override wins over the config");
    }

    @Test
    void getMaxPlayersUsesConfigUntilAnOverrideIsSet() {
        assertEquals(20, this.proxy.getMaxPlayers(), "fresh proxy reports the config value");

        this.proxy.setMaxPlayers(50, false);
        assertEquals(50, this.proxy.getMaxPlayers(), "the runtime override wins");
    }

    @Test
    void setMaxPlayersRejectsNegativeValues() {
        assertThrows(IllegalArgumentException.class, () -> this.proxy.setMaxPlayers(-1, false));
    }

    @Test
    void zeroDisablesTheLimit() {
        this.proxy.setMaxPlayers(0, false);
        assertFalse(this.proxy.isFull(null), "max players 0 means unlimited, never full");
    }

    private Map<UUID, ProxiedPlayer> playersOf(int count) {
        Map<UUID, ProxiedPlayer> players = new HashMap<>();
        for (int i = 0; i < count; i++) {
            players.put(UUID.randomUUID(), mock(ProxiedPlayer.class));
        }
        return players;
    }

    @Test
    void fullProxyRejectsPlayersWithoutBypassPermission() {
        when(this.proxy.getPlayers()).thenReturn(playersOf(2));
        this.proxy.setMaxPlayers(2, false);

        ProxiedPlayer regular = mock(ProxiedPlayer.class);
        when(regular.hasPermission("waterbucket.bypass.maxplayers")).thenReturn(false);
        assertTrue(this.proxy.isFull(regular), "a reached limit must reject regular players");

        ProxiedPlayer staff = mock(ProxiedPlayer.class);
        when(staff.hasPermission("waterbucket.bypass.maxplayers")).thenReturn(true);
        assertFalse(this.proxy.isFull(staff), "the bypass permission must exempt staff");

        when(this.proxy.getPlayers()).thenReturn(playersOf(1));
        assertFalse(this.proxy.isFull(regular), "a not-reached limit must accept everyone");
    }

    @Test
    void broadcastReachesEveryPlayerAndTheConsole() throws Exception {
        ProxiedPlayer player = mock(ProxiedPlayer.class);
        Map<UUID, ProxiedPlayer> players = new HashMap<>();
        players.put(UUID.randomUUID(), player);
        when(this.proxy.getPlayers()).thenReturn(players);

        ConsoleCommandSender console = mock(ConsoleCommandSender.class);
        Field consoleField = ProxyServer.class.getDeclaredField("commandSender");
        consoleField.setAccessible(true);
        consoleField.set(this.proxy, console);

        this.proxy.broadcastMessage("§aHello network");

        verify(player).sendMessage("§aHello network");
        verify(console).sendMessage("§aHello network");
    }
}
