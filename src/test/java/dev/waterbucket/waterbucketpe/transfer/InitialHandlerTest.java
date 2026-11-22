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

package dev.waterbucket.waterbucketpe.transfer;

import dev.waterbucket.waterbucketpe.event.defaults.ServerTransferFailedEvent;
import dev.waterbucket.waterbucketpe.event.defaults.InitialServerConnectedEvent;
import dev.waterbucket.waterbucketpe.network.connection.client.ClientConnection;
import dev.waterbucket.waterbucketpe.network.connection.handler.ReconnectReason;
import dev.waterbucket.waterbucketpe.network.protocol.handler.downstream.InitialHandler;
import dev.waterbucket.waterbucketpe.network.serverinfo.ServerInfo;
import org.cloudburstmc.math.vector.Vector2f;
import org.cloudburstmc.math.vector.Vector3f;
import org.cloudburstmc.protocol.bedrock.data.AuthoritativeMovementMode;
import org.cloudburstmc.protocol.bedrock.data.GameType;
import org.cloudburstmc.protocol.bedrock.packet.DisconnectPacket;
import org.cloudburstmc.protocol.bedrock.packet.PlayStatusPacket;
import org.cloudburstmc.protocol.bedrock.packet.StartGamePacket;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Failure handling during the initial (first join) connection, where no previous
 * downstream exists to stay on.
 */
public class InitialHandlerTest {

    private TransferTestHarness harness;
    private ServerInfo initialServer;
    private ClientConnection initialConnection;
    private InitialHandler handler;

    @BeforeEach
    void setUp() {
        this.harness = new TransferTestHarness();
        this.initialServer = this.harness.newServer("hub");
        this.initialConnection = this.harness.newDownstream(this.initialServer);
        this.harness.setPendingConnection(this.initialConnection);
        this.handler = new InitialHandler(this.harness.player, this.initialConnection);
    }

    @AfterEach
    void tearDown() {
        this.harness.close();
    }

    private PlayStatusPacket loginFailed() {
        PlayStatusPacket packet = new PlayStatusPacket();
        packet.setStatus(PlayStatusPacket.Status.LOGIN_FAILED_SERVER_OLD);
        return packet;
    }

    private static StartGamePacket newStartGame() {
        StartGamePacket packet = new StartGamePacket();
        packet.setRuntimeEntityId(100);
        packet.setUniqueEntityId(100);
        packet.setPlayerPosition(Vector3f.from(0, 64, 0));
        packet.setRotation(Vector2f.ZERO);
        packet.setDimensionId(0);
        packet.setPlayerGameType(GameType.SURVIVAL);
        packet.setLevelGameType(GameType.SURVIVAL);
        packet.setDifficulty(1);
        packet.setDayCycleStopTime(0);
        packet.setAuthoritativeMovementMode(AuthoritativeMovementMode.SERVER);
        packet.setRewindHistorySize(20);
        packet.setServerAuthoritativeBlockBreaking(true);
        packet.setInventoriesServerAuthoritative(true);
        packet.setBlockNetworkIdsHashed(false);
        return packet;
    }

    @Test
    void startGameJoinsTheInitialServer() {
        this.handler.handle(newStartGame());

        assertSame(this.initialConnection, this.harness.player.getDownstreamConnection());
        assertNull(this.harness.player.getPendingConnection(), "a completed join must clear the pending slot");
        assertEquals(1, this.harness.events(InitialServerConnectedEvent.class).size());
        assertTrue(this.initialConnection == this.harness.player.getPendingConnection()
                || this.harness.player.getPendingConnection() == null);
    }

    @Test
    void abortsStartGameFromDiscardedConnection() {
        // Mirrors the fallback race: onTransferFailure ran while the dial's START_GAME was in
        // flight and the pending slot no longer names this connection.
        this.harness.setPendingConnection(null);

        this.handler.handle(newStartGame());

        verify(this.initialConnection).disconnect();
        assertNull(this.harness.player.getDownstreamConnection(),
                "a discarded dial must not hijack the player's downstream");
        assertEquals(0, this.harness.events(InitialServerConnectedEvent.class).size());
        assertTrue(this.harness.player.isConnected());
    }

    @Test
    void abortsStartGameWhenAnotherConnectionIsPending() {
        ServerInfo newer = this.harness.newServer("fallback");
        ClientConnection newerConnection = this.harness.newDownstream(newer);
        this.harness.setPendingConnection(newerConnection);

        this.handler.handle(newStartGame());

        verify(this.initialConnection).disconnect();
        assertNull(this.harness.player.getDownstreamConnection());
        assertSame(newerConnection, this.harness.player.getPendingConnection(),
                "the newer dial must keep the pending slot");
    }

    @Test
    void loginFailureWithoutFallbackKicksWithoutNpe() {
        assertDoesNotThrow(() -> this.handler.handle(loginFailed()));

        assertFalse(this.harness.player.isConnected());
        verify(this.harness.reconnectHandler).getFallbackServer(this.harness.player, this.initialServer,
                ReconnectReason.TRANSFER_FAILED, "Incompatible version");
    }

    @Test
    void loginFailureUsesFallbackWhenAvailable() {
        ServerInfo fallback = this.harness.newServer("fallback");
        this.harness.stubDial(fallback);
        when(this.harness.reconnectHandler.getFallbackServer(any(), any(), any(), anyString())).thenReturn(fallback);

        this.handler.handle(loginFailed());

        verify(fallback).createConnection(this.harness.player);
        assertTrue(this.harness.player.isConnected());
    }

    @Test
    void initialKickCarriesTheKickMessage() {
        DisconnectPacket packet = new DisconnectPacket();
        packet.setKickMessage("You are not whitelisted");

        this.handler.handle(packet);

        assertFalse(this.harness.player.isConnected());
        assertEquals(1, this.harness.events(ServerTransferFailedEvent.class).size());
        ServerTransferFailedEvent event = this.harness.events(ServerTransferFailedEvent.class).get(0);
        assertSame(ReconnectReason.SERVER_KICK, event.getReason());
        assertEquals("You are not whitelisted", event.getMessage());
        assertTrue(this.harness.sentMessages.contains("waterbucket.downstream.transfer.failed"),
                "kick reason must reach the player instead of being lost on channel close");
    }
}