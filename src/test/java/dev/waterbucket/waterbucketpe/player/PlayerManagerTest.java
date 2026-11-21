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

package dev.waterbucket.waterbucketpe.player;

import dev.waterbucket.waterbucketpe.ProxyServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Covers the case-insensitive name index behind {@link PlayerManager#getPlayer(String)}.
 */
public class PlayerManagerTest {

    private PlayerManager manager;

    @BeforeEach
    void setUp() {
        this.manager = new PlayerManager(mock(ProxyServer.class));
    }

    private ProxiedPlayer player(String name, UUID uuid) {
        ProxiedPlayer player = mock(ProxiedPlayer.class);
        when(player.getName()).thenReturn(name);
        when(player.getUniqueId()).thenReturn(uuid);
        return player;
    }

    @Test
    void getPlayerIsCaseInsensitiveExactMatch() {
        ProxiedPlayer steve = this.player("Steve", UUID.randomUUID());
        this.manager.registerPlayer(steve);

        assertSame(steve, this.manager.getPlayer("Steve"));
        assertSame(steve, this.manager.getPlayer("steve"));
        assertSame(steve, this.manager.getPlayer("STEVE"));
        // Prefixes must not match, same as the previous scan-based implementation
        assertNull(this.manager.getPlayer("Ste"));
        assertNull(this.manager.getPlayer("SteveLongName"));
    }

    @Test
    void removePlayerClearsTheNameIndex() {
        ProxiedPlayer alex = this.player("Alex", UUID.randomUUID());
        this.manager.registerPlayer(alex);

        this.manager.removePlayer(alex);
        assertNull(this.manager.getPlayer("Alex"));
        assertNull(this.manager.getPlayer("alex"));
    }

    @Test
    void removingAnotherPlayerWithTheSameNameKeepsTheOnlineOne() {
        ProxiedPlayer online = this.player("Alex", UUID.randomUUID());
        this.manager.registerPlayer(online);

        ProxiedPlayer stale = this.player("Alex", UUID.randomUUID());
        this.manager.removePlayer(stale);

        assertSame(online, this.manager.getPlayer("alex"));
    }

    @Test
    void reRegisteringWithSameNameKeepsLookupCurrent() {
        ProxiedPlayer first = this.player("Jordan", UUID.randomUUID());
        this.manager.registerPlayer(first);

        ProxiedPlayer second = this.player("Jordan", UUID.randomUUID());
        this.manager.registerPlayer(second);

        assertSame(second, this.manager.getPlayer("JORDAN"));
    }

    @Test
    void matchPlayerFindsCaseInsensitivePrefix() {
        ProxiedPlayer notch = this.player("Notch", UUID.randomUUID());
        this.manager.registerPlayer(notch);

        assertSame(notch, this.manager.matchPlayer("not"));
        assertNull(this.manager.matchPlayer("hero"));
        assertNull(this.manager.matchPlayer(null));
    }

    @Test
    void nullAndUnknownLookupsAreSafe() {
        assertNull(this.manager.getPlayer((String) null));
        assertNull(this.manager.getPlayer("ghost"));
        this.manager.removePlayer(null);
        assertNull(this.manager.getPlayer(UUID.randomUUID()));
    }
}
