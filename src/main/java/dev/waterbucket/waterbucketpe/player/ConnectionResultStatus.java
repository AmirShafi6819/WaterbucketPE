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
 * Terminal states of a {@link ConnectionRequest}. Mirrors the Velocity contract: exactly one
 * status completes a request, and the future given to the plugin always completes normally
 * with a {@link ConnectionResult} - failures are a status, never a thrown exception.
 */
public enum ConnectionResultStatus {

    /** The dial, handshake and StartGame sequence against the target server completed. */
    SUCCESS,

    /** The player is already connected to the requested server; nothing was dialed. */
    ALREADY_CONNECTED,

    /** A connection attempt to the requested server is already in flight; nothing new started. */
    CONNECTION_IN_PROGRESS,

    /** The request did not proceed: a plugin cancelled a transfer event or the player went offline. */
    CONNECTION_CANCELLED,

    /** The requested connection failed (dial error, downstream refused, StartGame timeout). */
    CONNECTION_FAILED
}
