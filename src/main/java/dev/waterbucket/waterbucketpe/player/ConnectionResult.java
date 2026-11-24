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

/**
 * Immutable terminal result of a {@link ConnectionRequest}: the {@link ConnectionResultStatus}
 * plus an optional human-readable detail (the dial error message, the timeout notice, ...).
 */
public final class ConnectionResult {

    private final ConnectionResultStatus status;
    private final String message;

    ConnectionResult(ConnectionResultStatus status) {
        this(status, null);
    }

    ConnectionResult(ConnectionResultStatus status, String message) {
        this.status = status;
        this.message = message;
    }

    public ConnectionResultStatus getStatus() {
        return this.status;
    }

    /**
     * @return extra detail about the outcome, or null when the status speaks for itself
     */
    public String getMessage() {
        return this.message;
    }

    /** Whether the requested connection is now the player's active downstream. */
    public boolean isSuccessful() {
        return this.status == ConnectionResultStatus.SUCCESS;
    }

    @Override
    public String toString() {
        return "ConnectionResult{status=" + this.status + (this.message != null ? ", message=" + this.message : "") + "}";
    }
}
