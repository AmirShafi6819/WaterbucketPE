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
import dev.waterbucket.waterbucketpe.utils.config.proxy.ProxyConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers the case-insensitive name index behind {@link PlayerManager#getPlayer(String)}.
 */
public class PlayerManagerTest {

    private static final String KICK_OLD_KEY = "waterbucket.duplicate-login.kick-old";

    @TempDir
    Path tempDir;

    private ProxyServer proxy;

    private PlayerManager manager;

    @BeforeEach
    void setUp() {
        // Mockito defaults kickExistingPlayers() to false, which is the shipped default:
        // same-name sessions coexist and only the name lookup changes hands.
        this.proxy = mock(ProxyServer.class);
        when(this.proxy.getConfiguration()).thenReturn(mock(ProxyConfig.class));
        this.manager = new PlayerManager(this.proxy);
    }

    private ProxiedPlayer player(String name, UUID uuid) {
        ProxiedPlayer player = mock(ProxiedPlayer.class);
        when(player.getName()).thenReturn(name);
        when(player.getUniqueId()).thenReturn(uuid);
        return player;
    }

    @Test
    void reconnectWithChangedNameClearsTheOldNameEntry() {
        // The same UUID reconnecting under a new profile name used to leave the old name key
        // pointing at the dead instance until the 15-second ghost sweep
        UUID uuid = UUID.randomUUID();
        ProxiedPlayer first = this.player("OldName", uuid);
        when(first.getConnection()).thenReturn(mock(dev.waterbucket.waterbucketpe.network.connection.peer.BedrockServerSession.class));
        this.manager.registerPlayer(first);
        ProxiedPlayer second = this.player("NewName", uuid);
        this.manager.registerPlayer(second);

        assertNull(this.manager.getPlayer("OldName"), "the old name must not resolve to the dead instance");
        assertSame(second, this.manager.getPlayer("NewName"));
        assertSame(second, this.manager.getPlayer(uuid));
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
    void duplicateNameRegistrationKeepsTheDisplacedPlayerReachableAfterTheNewcomerLeaves() {
        // Two different identities can carry the same display name. The newcomer displaces the
        // first player's name entry (last writer wins); when the newcomer leaves, the displaced
        // player used to stay registered but unreachable via getPlayer(String) - invisible to
        // commands and the panel kick.
        ProxiedPlayer first = this.player("Sam", UUID.randomUUID());
        ProxiedPlayer second = this.player("Sam", UUID.randomUUID());
        this.manager.registerPlayer(first);
        this.manager.registerPlayer(second);
        assertSame(second, this.manager.getPlayer("sam"));

        this.manager.removePlayer(second);
        assertSame(first, this.manager.getPlayer("Sam"), "the displaced player must become reachable again after the newcomer left");
        assertNull(this.manager.getPlayer(second.getUniqueId()));
        assertSame(first, this.manager.getPlayer(first.getUniqueId()));
        assertEquals(1, this.manager.getPlayerCount());
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

    @Test
    void sweepEvictsDisconnectedGhosts() {
        ProxiedPlayer ghost = this.player("Ghost", UUID.randomUUID());
        when(ghost.isConnected()).thenReturn(false);
        this.manager.registerPlayer(ghost);

        this.manager.sweepDisconnectedPlayers();

        assertNull(this.manager.getPlayer("ghost"));
        assertNull(this.manager.getPlayer(ghost.getUniqueId()));
    }

    @Test
    void sweepKeepsConnectedPlayers() {
        ProxiedPlayer live = this.player("Live", UUID.randomUUID());
        when(live.isConnected()).thenReturn(true);
        this.manager.registerPlayer(live);

        this.manager.sweepDisconnectedPlayers();

        assertSame(live, this.manager.getPlayer("live"));
    }

    @Test
    void sweepDoesNotEvictFreshSessionReusingGhostIdentity() {
        ProxiedPlayer ghost = this.player("Alex", UUID.randomUUID());
        when(ghost.isConnected()).thenReturn(false);
        this.manager.registerPlayer(ghost);

        // The same identity reconnects before the sweep runs; the fresh session wins the registry
        ProxiedPlayer fresh = this.player("Alex", UUID.randomUUID());
        when(fresh.isConnected()).thenReturn(true);
        this.manager.registerPlayer(fresh);

        this.manager.sweepDisconnectedPlayers();

        assertNull(this.manager.getPlayer(ghost.getUniqueId()), "stale instance must go");
        assertSame(fresh, this.manager.getPlayer("ALEX"), "fresh session must stay");
    }

    @Test
    void sweepWithEmptyRegistryIsSafe() {
        this.manager.sweepDisconnectedPlayers();
        assertEquals(0, this.manager.getPlayerCount());
    }

    @Test
    void kickExistingPlayersIsOffByDefault() throws Exception {
        // Velocity parity: the duplicate-kick is an opt-in switch, so existing setups keep
        // the coexist behavior pinned by the tests above.
        File file = this.tempDir.resolve("config.yml").toFile();
        Files.writeString(file.toPath(), "listener:\n  motd: test\n");
        assertFalse(new ProxyConfig(file).kickExistingPlayers());
    }

    private ProxiedPlayer connected(String name, UUID uuid) {
        ProxiedPlayer player = this.player(name, uuid);
        // registerPlayer reads the SESSION's connected state, not the player's
        dev.waterbucket.waterbucketpe.network.connection.peer.BedrockServerSession session =
                mock(dev.waterbucket.waterbucketpe.network.connection.peer.BedrockServerSession.class);
        when(session.isConnected()).thenReturn(true);
        when(player.getConnection()).thenReturn(session);
        return player;
    }

    private void enableKickExisting() {
        ProxyConfig config = mock(ProxyConfig.class);
        when(config.kickExistingPlayers()).thenReturn(true);
        when(this.proxy.getConfiguration()).thenReturn(config);
    }

    @Test
    void kickExistingClosesTheOldSessionAndHandsTheNameToTheNewcomer() {
        this.enableKickExisting();
        ProxiedPlayer old = this.connected("Sam", UUID.randomUUID());
        this.manager.registerPlayer(old);

        ProxiedPlayer newcomer = this.connected("Sam", UUID.randomUUID());
        this.manager.registerPlayer(newcomer);

        verify(old).disconnect(KICK_OLD_KEY);
        assertSame(newcomer, this.manager.getPlayer("SAM"));
        assertNull(this.manager.getPlayer(old.getUniqueId()), "the closed session must leave the registry immediately");
        assertEquals(1, this.manager.getPlayerCount());
    }

    @Test
    void kickExistingLeavesDifferentNamesAlone() {
        this.enableKickExisting();
        ProxiedPlayer alex = this.connected("Alex", UUID.randomUUID());
        this.manager.registerPlayer(alex);

        ProxiedPlayer jordan = this.connected("Jordan", UUID.randomUUID());
        this.manager.registerPlayer(jordan);

        verify(alex, org.mockito.Mockito.never()).disconnect(org.mockito.Mockito.anyString());
        assertEquals(2, this.manager.getPlayerCount());
    }

    @Test
    void kickExistingIgnoresADeadSameNameSession() {
        this.enableKickExisting();
        ProxiedPlayer dead = this.player("Sam", UUID.randomUUID());
        when(dead.isConnected()).thenReturn(false);
        when(dead.getConnection()).thenReturn(mock(dev.waterbucket.waterbucketpe.network.connection.peer.BedrockServerSession.class));
        this.manager.registerPlayer(dead);

        ProxiedPlayer newcomer = this.connected("Sam", UUID.randomUUID());
        this.manager.registerPlayer(newcomer);

        verify(dead, org.mockito.Mockito.never()).disconnect(org.mockito.Mockito.anyString());
        assertSame(newcomer, this.manager.getPlayer("sam"));
        assertNull(this.manager.getPlayer(dead.getUniqueId()));
    }

    @Test
    void kickExistingKeepsTheSameIdentityReconnectPath() {
        // A reconnect of the SAME identity already closes the old session; the flag must not
        // change that path or add a second disconnect.
        this.enableKickExisting();
        UUID uuid = UUID.randomUUID();
        ProxiedPlayer old = this.connected("Alex", uuid);
        this.manager.registerPlayer(old);

        ProxiedPlayer fresh = this.connected("Alex", uuid);
        this.manager.registerPlayer(fresh);

        assertSame(fresh, this.manager.getPlayer("ALEX"));
        assertEquals(1, this.manager.getPlayerCount());
    }
}
