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

package dev.waterbucket.waterbucketpe.plugin.compat;

import dev.waterbucket.waterbucketpe.ProxyServer;
import dev.waterbucket.waterbucketpe.event.EventManager;
import dev.waterbucket.waterbucketpe.event.defaults.PlayerChatEvent;
import dev.waterbucket.waterbucketpe.event.defaults.PlayerCommandEvent;
import dev.waterbucket.waterbucketpe.event.defaults.PlayerDisconnectedEvent;
import dev.waterbucket.waterbucketpe.event.defaults.PlayerLoginEvent;
import dev.waterbucket.waterbucketpe.plugin.compat.event.PlayerJoinEvent;
import dev.waterbucket.waterbucketpe.plugin.compat.event.PlayerQuitEvent;
import lombok.extern.log4j.Log4j2;

/**
 * Bridges real proxy moments into the compatibility events: login -> login + join, chat ->
 * chat, command -> command preprocess, disconnect -> quit. The bridge runs LAST on every
 * proxy event (proxy-native handlers see raw data first, compatibility plugins see the same
 * moment afterwards), and every cancellation or edit is written back into the proxy event
 * synchronously, so cancelling a compatibility chat really stops the line at the pipeline.
 */
@Log4j2
public final class CompatEventBridge {

    private CompatEventBridge() {
    }

    /**
     * Subscribes the bridge to the proxy events once per boot/reload.
     */
    public static void register(ProxyServer proxy) {
        EventManager events = proxy.getEventManager();
        Server server = Server.init(proxy);

        // Login: fire the compatibility login, propagate cancellation, then raise the join
        // event with the default join message. Runs last: proxy-native handlers already had
        // their say about the login.
        events.subscribe(PlayerLoginEvent.class, event -> {
            Player player = new Player(event.getPlayer());
            dev.waterbucket.waterbucketpe.plugin.compat.event.PlayerLoginEvent compat =
                    new dev.waterbucket.waterbucketpe.plugin.compat.event.PlayerLoginEvent(player,
                            "§cYou are not allowed to join this server.");
            events.callEvent(compat);
            if (compat.isCancelled()) {
                event.setCancelled(true);
                event.setCancelReason(compat.getKickMessage());
                return;
            }
            PlayerJoinEvent join = new PlayerJoinEvent(player, "§e" + player.getName() + " joined the game");
            events.callEvent(join);
            if (join.getJoinMessage() != null && !join.getJoinMessage().isEmpty()) {
                server.broadcastMessage(join.getJoinMessage());
            }
        }, dev.waterbucket.waterbucketpe.event.EventPriority.HIGHEST);

        // Disconnect: the quit event with the disconnect reason; the quit message broadcasts
        // to whoever is left online.
        events.subscribe(PlayerDisconnectedEvent.class, event -> {
            Player player = new Player(event.getPlayer());
            PlayerQuitEvent quit = new PlayerQuitEvent(player, event.getReason(),
                    "§e" + player.getName() + " left the game");
            events.callEvent(quit);
            if (quit.getQuitMessage() != null && !quit.getQuitMessage().isEmpty()) {
                server.broadcastMessage(quit.getQuitMessage());
            }
        }, dev.waterbucket.waterbucketpe.event.EventPriority.HIGHEST);

        // Chat: same moment, compatibility shape; edits and cancellation flow back into the
        // proxy event before the pipeline continues.
        events.subscribe(PlayerChatEvent.class, event -> {
            Player player = new Player(event.getPlayer());
            dev.waterbucket.waterbucketpe.plugin.compat.event.PlayerChatEvent compat =
                    new dev.waterbucket.waterbucketpe.plugin.compat.event.PlayerChatEvent(player, event.getMessage());
            events.callEvent(compat);
            if (compat.isCancelled()) {
                event.setCancelled(true);
                return;
            }
            if (!compat.getMessage().equals(event.getMessage())) {
                event.setMessage(compat.getMessage());
            }
        }, dev.waterbucket.waterbucketpe.event.EventPriority.HIGHEST);

        // Commands: the raw line including the leading slash.
        events.subscribe(PlayerCommandEvent.class, event -> {
            Player player = new Player(event.getPlayer());
            dev.waterbucket.waterbucketpe.plugin.compat.event.PlayerCommandPreprocessEvent compat =
                    new dev.waterbucket.waterbucketpe.plugin.compat.event.PlayerCommandPreprocessEvent(player,
                            event.getCommand());
            events.callEvent(compat);
            if (compat.isCancelled()) {
                event.setCancelled(true);
                return;
            }
            if (!compat.getMessage().equals(event.getCommand())) {
                event.setCommand(compat.getMessage());
            }
        }, dev.waterbucket.waterbucketpe.event.EventPriority.HIGHEST);
    }
}
