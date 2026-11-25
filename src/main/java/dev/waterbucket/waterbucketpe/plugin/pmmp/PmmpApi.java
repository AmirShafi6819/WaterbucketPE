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

package dev.waterbucket.waterbucketpe.plugin.pmmp;

/**
 * The facade object the interpreted plugin languages (Python, PHP) receive as their
 * {@code pmmp} global: one entry to the compatibility server, its event classes and the
 * compatibility config utility. Java plugins reach everything directly through this package
 * instead.
 * <p>
 * Event classes are exposed as accessor methods ({@code pmmp.playerJoinEvent()}) so scripts
 * can subscribe with {@code plugin.subscribe(pmmp.playerJoinEvent(), handler)} without any
 * Java import syntax; snake_case aliases exist for the same reason.
 */
public class PmmpApi {

    /**
     * The compatibility server facade.
     */
    public Server getServer() {
        return Server.getInstance();
    }

    // ---- event classes (for plugin.subscribe(...)) ----

    public Class<dev.waterbucket.waterbucketpe.plugin.pmmp.event.PlayerJoinEvent> playerJoinEvent() {
        return dev.waterbucket.waterbucketpe.plugin.pmmp.event.PlayerJoinEvent.class;
    }

    public Class<dev.waterbucket.waterbucketpe.plugin.pmmp.event.PlayerQuitEvent> playerQuitEvent() {
        return dev.waterbucket.waterbucketpe.plugin.pmmp.event.PlayerQuitEvent.class;
    }

    public Class<dev.waterbucket.waterbucketpe.plugin.pmmp.event.PlayerChatEvent> playerChatEvent() {
        return dev.waterbucket.waterbucketpe.plugin.pmmp.event.PlayerChatEvent.class;
    }

    public Class<dev.waterbucket.waterbucketpe.plugin.pmmp.event.PlayerCommandPreprocessEvent> playerCommandPreprocessEvent() {
        return dev.waterbucket.waterbucketpe.plugin.pmmp.event.PlayerCommandPreprocessEvent.class;
    }

    public Class<dev.waterbucket.waterbucketpe.plugin.pmmp.event.PlayerKickEvent> playerKickEvent() {
        return dev.waterbucket.waterbucketpe.plugin.pmmp.event.PlayerKickEvent.class;
    }

    public Class<dev.waterbucket.waterbucketpe.plugin.pmmp.event.PlayerLoginEvent> playerLoginEvent() {
        return dev.waterbucket.waterbucketpe.plugin.pmmp.event.PlayerLoginEvent.class;
    }

    // ---- snake_case aliases (Python/PHP ergonomics) ----

    public Server get_server() {
        return this.getServer();
    }

    public Class<dev.waterbucket.waterbucketpe.plugin.pmmp.event.PlayerJoinEvent> player_join_event() {
        return this.playerJoinEvent();
    }

    public Class<dev.waterbucket.waterbucketpe.plugin.pmmp.event.PlayerQuitEvent> player_quit_event() {
        return this.playerQuitEvent();
    }

    public Class<dev.waterbucket.waterbucketpe.plugin.pmmp.event.PlayerChatEvent> player_chat_event() {
        return this.playerChatEvent();
    }

    public Class<dev.waterbucket.waterbucketpe.plugin.pmmp.event.PlayerCommandPreprocessEvent> player_command_preprocess_event() {
        return this.playerCommandPreprocessEvent();
    }

    public Class<dev.waterbucket.waterbucketpe.plugin.pmmp.event.PlayerKickEvent> player_kick_event() {
        return this.playerKickEvent();
    }

    public Class<dev.waterbucket.waterbucketpe.plugin.pmmp.event.PlayerLoginEvent> player_login_event() {
        return this.playerLoginEvent();
    }

    /**
     * The compatibility config utility constructor for scripts:
     * {@code cfg = pmmp.newConfig(plugin.getDataFolder() + "/data.yml")}.
     */
    public Config newConfig(String path) {
        return new Config(java.nio.file.Path.of(path));
    }

    public Config newConfig(String path, String type) {
        return new Config(java.nio.file.Path.of(path),
                "json".equalsIgnoreCase(type) ? Config.Type.JSON : Config.Type.YAML);
    }

    public Config new_config(String path) {
        return this.newConfig(path);
    }

    public Config new_config(String path, String type) {
        return this.newConfig(path, type);
    }
}
