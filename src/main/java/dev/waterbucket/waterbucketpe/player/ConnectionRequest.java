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

import dev.waterbucket.waterbucketpe.network.serverinfo.ServerInfo;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Velocity-style connection request: ask the proxy to move a player onto a downstream server
 * and learn the actual outcome instead of inferring it from events. Created through
 * {@link ProxiedPlayer#createConnectionRequest(ServerInfo)}.
 * <p>
 * The returned future always completes <em>normally</em> with a {@link ConnectionResult} -
 * failures are statuses, never exceptions - at the latest after
 * {@link #REQUEST_TIMEOUT_SECONDS} seconds, so a plugin can never leak a pending request.
 * A {@code SUCCESS} result means the proxy accepted the downstream connection; later
 * downstream failures still surface through the usual transfer events and fallback handling.
 * The result reflects the requested dial only: fallback reconnections after a failure are
 * reported through the transfer events, not through this future.
 * <p>
 * Typical usage:
 * <pre>{@code
 * proxy.getPlayer("Steve").createConnectionRequest(proxy.getServer("minigames"))
 *      .connectWithIndication()
 *      .thenAccept(result -> {
 *          if (result.getStatus() == ConnectionResultStatus.SUCCESS) { ... }
 *      });
 * }</pre>
 * Concurrent requests for one player: at most one dial is ever in flight; a second request
 * for the same target settles immediately with {@code CONNECTION_IN_PROGRESS}, so requests
 * are effectively serialized per player.
 */
public final class ConnectionRequest {

    /**
     * Backstop for the asynchronous dial chain (event hold-outs, dial timeouts already
     * handled by the proxy, StartGame timeout). Chosen above the proxy's internal
     * pending-connection timeout (60s) so real outcomes always win the race against this
     * guard; a request never stays pending longer than this.
     */
    public static final int REQUEST_TIMEOUT_SECONDS = 90;

    private final ProxiedPlayer player;
    private final ServerInfo targetServer;

    ConnectionRequest(ProxiedPlayer player, ServerInfo targetServer) {
        this.player = player;
        this.targetServer = targetServer;
    }

    /** @return the player this request moves. */
    public ProxiedPlayer getPlayer() {
        return this.player;
    }

    /** @return the downstream server this request dials. */
    public ServerInfo getTargetServer() {
        return this.targetServer;
    }

    /**
     * Starts the request and completes with the outcome, including negative outcomes
     * (already connected, connection in progress, cancelled, failed).
     *
     * @return a future that always completes normally with the terminal {@link ConnectionResult}
     */
    public CompletableFuture<ConnectionResult> connectWithIndication() {
        CompletableFuture<ConnectionResult> future = new CompletableFuture<>();
        if (!this.player.isConnected()) {
            future.complete(new ConnectionResult(ConnectionResultStatus.CONNECTION_CANCELLED, "player is offline"));
            return future;
        }

        // Registered before connect() so terminal proxy-side outcomes for this target
        // (dial success, dial failure, player disconnect) complete the future directly.
        Consumer<ConnectionResultStatus> terminalListener = status -> {
            if (!future.isDone()) {
                future.complete(new ConnectionResult(status));
            }
        };
        this.player.awaitConnectResult(this.targetServer, terminalListener);

        /*
         * Synchronous outcomes of connect() apply to THIS request only, never to every
         * pending request for the same target (another request may already be dialing).
         * connect() reports its early returns through the per-player observer slot; when it
         * returns without one, the dial was initiated and the terminal listener above waits
         * for the result.
         */
        AtomicReference<ConnectionResultStatus> syncOutcome = new AtomicReference<>();
        this.player.setConnectOutcomeObserver(outcome -> syncOutcome.compareAndSet(null, outcome));
        try {
            this.player.connect(this.targetServer);
        } catch (Throwable t) {
            // Contract violations (connect before login, unknown server state) surface as a
            // failed result instead of a thrown exception.
            String message = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
            future.complete(new ConnectionResult(ConnectionResultStatus.CONNECTION_FAILED, message));
        } finally {
            this.player.clearConnectOutcomeObserver();
        }
        if (syncOutcome.get() != null && !future.isDone()) {
            future.complete(new ConnectionResult(syncOutcome.get()));
        }

        return future
                .orTimeout(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .exceptionally(error -> new ConnectionResult(ConnectionResultStatus.CONNECTION_FAILED,
                        "request did not complete within " + REQUEST_TIMEOUT_SECONDS + "s"))
                .whenComplete((result, error) ->
                        this.player.detachConnectResult(this.targetServer, terminalListener));
    }

    /**
     * Alias of {@link #connectWithIndication()} kept for Velocity-familiar call sites.
     */
    public CompletableFuture<ConnectionResult> connect() {
        return this.connectWithIndication();
    }

    /**
     * Fire-and-forget variant: starts the request, logs a failure at debug level and never
     * throws.
     */
    public void fireAndForget() {
        this.connectWithIndication().whenComplete((result, error) -> {
            if (error != null || (result != null && !result.isSuccessful())) {
                this.player.getLogger().debug("[{}] Connection request to {} finished without success: {}",
                        this.player.getName(), this.targetServer.getServerName(),
                        error != null ? error.toString() : result.toString());
            }
        });
    }
}
