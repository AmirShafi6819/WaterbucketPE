/*
 * Copyright 2022 WaterbucketTEAM
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
import dev.waterbucket.waterbucketpe.utils.types.TextContainer;
import dev.waterbucket.waterbucketpe.utils.types.Permission;

import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Base Player Manager, managing the registration, binding and removal of instances of ProxiedPlayer.
 */
public class PlayerManager {

    private final ProxyServer proxy;

    private final Map<UUID, ProxiedPlayer> players = new ConcurrentHashMap<>();
    /**
     * Case-insensitive name index, kept in sync on register/remove. Turns player name lookups
     * (commands, login throttle checks, plugins) from O(players) scans into O(1) map hits.
     */
    private final Map<String, ProxiedPlayer> playersByName = new ConcurrentHashMap<>();

    public PlayerManager(ProxyServer proxy) {
        this.proxy = proxy;
    }

    public boolean registerPlayer(ProxiedPlayer player) {
        if (player == null) {
            return false;
        }

        ProxiedPlayer previousSession = this.players.remove(player.getUniqueId());
        if (previousSession != null && previousSession.getConnection().isConnected()) {
            previousSession.disconnect("disconnectionScreen.loggedinOtherLocation");
        }
        this.playersByName.remove(player.getName().toLowerCase(Locale.ROOT));
        this.players.put(player.getUniqueId(), player);
        this.playersByName.put(player.getName().toLowerCase(Locale.ROOT), player);
        return true;
    }

    public void subscribePermissions(ProxiedPlayer player) {
        this.proxy.getConfiguration().getDefaultPermissions().forEach(perm -> player.addPermission(new Permission(perm, true)));
        List<String> permissions = this.proxy.getConfiguration().getPlayerPermissions().get(player.getName());
        if (permissions == null) {
            return;
        }

        for (String perm : permissions) {
            player.addPermission(new Permission(perm, true));
        }
    }

    public void removePlayer(ProxiedPlayer player) {
        if (player != null) {
            this.players.remove(player.getUniqueId(), player);
            this.playersByName.remove(player.getName().toLowerCase(Locale.ROOT), player);
        }
    }

    /**
     * Evicts ghost registrations: players whose upstream connection died but whose disconnect
     * event was never processed (glitched clients, lost packets). Such instances stay registered
     * forever, block reconnects with "logged in from other location" and leak memory (reported
     * upstream as issue #266). Runs periodically from the proxy scheduler.
     * <p>
     * Only the stale instance is evicted: if a fresh session for the same identity re-registered
     * in the meantime, it stays untouched.
     */
    public void sweepDisconnectedPlayers() {
        for (ProxiedPlayer player : this.players.values()) {
            if (player.isConnected()) {
                continue;
            }
            if (this.players.remove(player.getUniqueId(), player)) {
                this.playersByName.remove(player.getName().toLowerCase(Locale.ROOT), player);
                try {
                    // Make sure any cleanup the disconnect path performs still runs; it is
                    // idempotent and the registry entry is already gone.
                    player.disconnect(new TextContainer("disconnectionScreen.timeout"));
                } catch (Throwable t) {
                    // The ghost is already out of the registry; nothing else can leak here.
                }
            }
        }
    }

    public ProxiedPlayer getPlayer(UUID uuid) {
        return uuid == null ? null : this.players.get(uuid);
    }

    /**
     * Exact, case-insensitive lookup by player name, matching the previous scan-based behavior.
     *
     * @return the player with exactly this name, or null
     */
    public ProxiedPlayer getPlayer(String playerName) {
        if (playerName == null) {
            return null;
        }
        return this.playersByName.get(playerName.toLowerCase(Locale.ROOT));
    }

    /**
     * Case-insensitive prefix search over all online players. Useful for commands that
     * complete partially typed names.
     *
     * @return the first player whose name starts with the given prefix, or null
     */
    public ProxiedPlayer matchPlayer(String playerName) {
        if (playerName == null) {
            return null;
        }
        String lower = playerName.toLowerCase(Locale.ROOT);
        for (ProxiedPlayer player : this.players.values()) {
            if (player.getName().toLowerCase(Locale.ROOT).startsWith(lower)) {
                return player;
            }
        }
        return null;
    }
    
    public int getPlayerCount() {
        return this.players.size();
    }

    public Map<UUID, ProxiedPlayer> getPlayers() {
        return Collections.unmodifiableMap(this.players);
    }
}
