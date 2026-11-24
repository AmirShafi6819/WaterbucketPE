/*
 * Copyright 2026 WaterbucketTEAM
 * Licensed under the GNU General Public License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.gnu.org/licenses/old-licenses/gpl-2.0.html
 *
 * Unless required by applicable law or agreed to in writing, software
 * distribution under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.waterbucket.waterbucketpe.network.serverinfo;

/**
 * Thrown when a player tries to reach a server the operator marked offline
 * ({@code /wbserveroffline} or {@code servers.<name>.disabled: true} in config.yml).
 * <p>
 * The proxy deliberately fails the connection attempt instead of lying about reachability:
 * the failure flows through the normal connect-failure paths (reconnect handler, transfer
 * failure handling), so a disabled server behaves exactly like a genuinely offline one even
 * though its process is still running.
 */
public class ServerDisabledException extends RuntimeException {

    public ServerDisabledException(String serverName) {
        super("Server '" + serverName + "' is marked offline (wbserveroffline)");
    }
}
