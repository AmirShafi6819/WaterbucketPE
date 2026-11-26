/*
 * Copyright 2026 WaterbucketTEAM
 * Licensed under the GNU General Public License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.gnu.org/licenses/old-licenses/gpl-2.0.html
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.waterbucket.waterbucketpe.player;

import dev.waterbucket.waterbucketpe.network.connection.client.ClientConnection;
import dev.waterbucket.waterbucketpe.network.connection.handler.ReconnectReason;
import dev.waterbucket.waterbucketpe.network.serverinfo.ServerInfo;
import dev.waterbucket.waterbucketpe.transfer.TransferTestHarness;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Real-IP forwarding is built in, so a backend that cannot parse the forwarding claims must
 * never block a join: the exact rejection signature marks the server and the same join is
 * retried with a clean login. Ordinary kicks must keep the regular recovery paths.
 */
public class ForwardingFallbackTest {

    private static final String POCKETMINE_REJECTION =
            "Bad packet: Error processing LoginPacket: JSON property \"Waterdog_IP\" does not exist in object of type "
                    + "pocketmine\\network\\mcpe\\protocol\\types\\login\\ClientData (Error ID: 71a2-dc77-454e)";

    @Test
    public void strictBackendRejectionSignatureIsRecognized() {
        // The exact PocketMine-MP wording the fork was reported to choke on
        assertTrue(ProxiedPlayer.isClaimRejection(POCKETMINE_REJECTION));
        assertTrue(ProxiedPlayer.isClaimRejection("Bad packet: Error processing LoginPacket: unknown property \"Waterdog_IP\""));
        // Deliberately narrow: ordinary kicks and other failures recover normally
        assertFalse(ProxiedPlayer.isClaimRejection(null));
        assertFalse(ProxiedPlayer.isClaimRejection("Server full"));
        assertFalse(ProxiedPlayer.isClaimRejection("Kicked by operator"));
        assertFalse(ProxiedPlayer.isClaimRejection("Bad packet: malformed packet"));
        assertFalse(ProxiedPlayer.isClaimRejection("JSON property \"SkinData\" does not exist"));
    }

    @Test
    public void claimRejectionMarksTheServerAndRetriesTheSameJoin() {
        try (TransferTestHarness harness = new TransferTestHarness()) {
            ServerInfo server = harness.newServer("lobby1");
            when(server.forwardClaims()).thenReturn(true);
            ClientConnection connection = harness.newDownstream(server);
            harness.setPendingConnection(connection);
            // The retry dial: left pending, so the join is in flight and nothing else fails
            harness.stubDial(server);

            harness.player.onDownstreamFailure(connection, ReconnectReason.SERVER_KICK, POCKETMINE_REJECTION);

            // The server is remembered as claim-rejecting, so later logins carry no claims
            verify(server).setForwardingSupport(ServerInfo.ForwardingSupport.UNSUPPORTED);
            // The failed attempt dies and the very same server is dialed again
            verify(connection).disconnect();
            verify(server).createConnection(harness.player);
            // The player stays online: no upstream disconnect, no fallback kick
            verify(harness.upstream, never()).disconnect(any(String.class));
            verify(harness.upstream, never()).disconnect();
        }
    }

    @Test
    public void ordinaryKickKeepsTheRegularRecoveryPath() {
        try (TransferTestHarness harness = new TransferTestHarness()) {
            ServerInfo server = harness.newServer("lobby1");
            ClientConnection connection = harness.newDownstream(server);
            harness.setPendingConnection(connection);

            harness.player.onDownstreamFailure(connection, ReconnectReason.SERVER_KICK, "Server is restarting");

            // Not a claim rejection: no forwarding state is touched and no retry dial starts
            verify(server, never()).setForwardingSupport(any());
            verify(server, never()).createConnection(any());
        }
    }

    @Test
    public void alreadyUnsupportedServerIsNotMarkedTwice() {
        try (TransferTestHarness harness = new TransferTestHarness()) {
            ServerInfo server = harness.newServer("lobby1");
            when(server.forwardClaims()).thenReturn(false);
            ClientConnection connection = harness.newDownstream(server);
            harness.setPendingConnection(connection);

            harness.player.onDownstreamFailure(connection, ReconnectReason.SERVER_KICK, POCKETMINE_REJECTION);

            // The server already runs without claims; the failure is not a forwarding matter
            verify(server, never()).setForwardingSupport(any());
            verify(server, never()).createConnection(any());
        }
    }
}
