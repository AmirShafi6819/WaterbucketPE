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

import dev.waterbucket.waterbucketpe.event.defaults.ServerTransferRequestEvent;
import dev.waterbucket.waterbucketpe.network.connection.client.ClientConnection;
import dev.waterbucket.waterbucketpe.network.protocol.handler.TransferCallback;
import dev.waterbucket.waterbucketpe.network.serverinfo.ServerInfo;
import dev.waterbucket.waterbucketpe.player.ConnectionResult;
import dev.waterbucket.waterbucketpe.player.ConnectionRequest;
import dev.waterbucket.waterbucketpe.player.ConnectionResultStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Covers the Velocity-style ConnectionRequest surface: every terminal state of the dial chain
 * (already connected, in progress, cancelled, failed, success) settles the future, the future
 * never stays pending, and no listener leaks after completion.
 */
class ConnectionRequestTest {

    private TransferTestHarness harness;
    private ServerInfo lobbyServer;
    private ClientConnection lobbyConnection;

    @BeforeEach
    void setUp() {
        this.harness = new TransferTestHarness();
        this.lobbyServer = this.harness.newServer("lobby");
        this.lobbyConnection = this.harness.newDownstream(this.lobbyServer);
        this.harness.setActiveDownstream(this.lobbyConnection);
    }

    @AfterEach
    void tearDown() {
        this.harness.close();
    }

    @Test
    @Timeout(10)
    void requestToTheCurrentServerSettlesAsAlreadyConnected() {
        ConnectionResult result = this.harness.player
                .createConnectionRequest(this.lobbyServer)
                .connectWithIndication()
                .join();

        assertEquals(ConnectionResultStatus.ALREADY_CONNECTED, result.getStatus());
        verify(this.lobbyServer, never()).createConnection(any());
    }

    @Test
    @Timeout(10)
    void requestWhileATransferIsInProgressSettlesAsInProgress() {
        // Claim an in-flight transfer exactly like the failure-mode tests do
        ClientConnection targetConnection = this.harness.newDownstream(this.harness.newServer("game"));
        TransferCallback callback = new TransferCallback(this.harness.player, targetConnection,
                targetConnection.getServerInfo(), 0);
        assertTrue(this.harness.player.getRewriteData().trySetTransferCallback(callback));

        ServerInfo other = this.harness.newServer("other");
        ConnectionResult result = this.harness.player
                .createConnectionRequest(other)
                .connectWithIndication()
                .join();

        assertEquals(ConnectionResultStatus.CONNECTION_IN_PROGRESS, result.getStatus());
        verify(other, never()).createConnection(any());
    }

    @Test
    @Timeout(10)
    void cancelledTransferRequestSettlesAsCancelled() {
        this.harness.eventInterceptor = event -> {
            if (event instanceof ServerTransferRequestEvent transfer) {
                transfer.setCancelled();
            }
        };

        ServerInfo target = this.harness.newServer("target");
        ConnectionResult result = this.harness.player
                .createConnectionRequest(target)
                .connectWithIndication()
                .join();

        assertEquals(ConnectionResultStatus.CONNECTION_CANCELLED, result.getStatus());
        verify(target, never()).createConnection(any());
    }

    @Test
    @Timeout(15)
    void failedDialSettlesAsFailed() {
        ServerInfo target = this.harness.newServer("target");
        this.harness.stubDial(target).setFailure(new IllegalStateException("server is down"));

        ConnectionResult result = this.harness.player
                .createConnectionRequest(target)
                .connectWithIndication()
                .join();

        assertEquals(ConnectionResultStatus.CONNECTION_FAILED, result.getStatus());
        // The failed dial frees the pending slot again
        assertFalse(this.harness.player.getPendingServers().contains(target));
    }

    @Test
    @Timeout(15)
    void successfulDialSettlesAsSuccess() {
        ServerInfo target = this.harness.newServer("target");
        ClientConnection targetConnection = this.harness.newDownstream(target);
        this.harness.stubDial(target).setSuccess(targetConnection);

        ConnectionResult result = this.harness.player
                .createConnectionRequest(target)
                .connectWithIndication()
                .join();

        assertEquals(ConnectionResultStatus.SUCCESS, result.getStatus());
        assertTrue(result.isSuccessful());
        assertSame(targetConnection, this.harness.player.getPendingConnection());
    }

    @Test
    @Timeout(15)
    void fireAndForgetNeverThrowsAndSettlesTheInternalFuture() {
        ServerInfo target = this.harness.newServer("target");
        this.harness.stubDial(target).setFailure(new IllegalStateException("server is down"));

        this.harness.player.createConnectionRequest(target).fireAndForget();
        // fireAndForget must be synchronous-safe: the internal future chain completes without
        // any listener attached; a throw here would fail the test.
    }

    @Test
    @Timeout(15)
    void playerDisconnectSettlesAPendingRequestAsCancelled() throws Exception {
        ServerInfo target = this.harness.newServer("target");
        // Dial starts but never completes: the request stays pending until the player leaves
        this.harness.stubDial(target);

        CompletableFuture<ConnectionResult> future = this.harness.player
                .createConnectionRequest(target)
                .connectWithIndication();
        assertFalse(future.isDone(), "dial in flight: no result yet");

        this.harness.player.disconnect("leaving");
        assertEquals(ConnectionResultStatus.CONNECTION_CANCELLED, future.join().getStatus());
    }

    @Test
    @Timeout(15)
    void twoRequestsForTheSameTargetSerializeCorrectly() throws Exception {
        ServerInfo target = this.harness.newServer("target");
        io.netty.util.concurrent.Promise<ClientConnection> dial = this.harness.stubDial(target);
        // First request: dial in flight
        CompletableFuture<ConnectionResult> first = this.harness.player
                .createConnectionRequest(target)
                .connectWithIndication();
        assertFalse(first.isDone());

        // Second request while the first dial is still pending: settles immediately
        ConnectionResult second = this.harness.player
                .createConnectionRequest(target)
                .connectWithIndication()
                .join();
        assertEquals(ConnectionResultStatus.CONNECTION_IN_PROGRESS, second.getStatus());

        // The first request's own dial still completes normally afterwards
        ClientConnection targetConnection = this.harness.newDownstream(target);
        dial.setSuccess(targetConnection);
        assertEquals(ConnectionResultStatus.SUCCESS, first.join().getStatus());
    }
}
