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

import dev.waterbucket.waterbucketpe.ProxyServer;
import dev.waterbucket.waterbucketpe.command.CommandSender;
import dev.waterbucket.waterbucketpe.command.ConsoleCommandSender;
import dev.waterbucket.waterbucketpe.player.ProxiedPlayer;
import dev.waterbucket.waterbucketpe.plugin.Plugin;
import lombok.extern.log4j.Log4j2;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The compatibility server facade: the server object plugins of the original software expect,
 * backed by the live proxy. Every method is a real operation on {@link ProxyServer} - player
 * lookups, broadcasts, command dispatch, motd, scheduler, plugin manager - nothing here is a
 * stub. World-side calls of the original software have no meaning on a proxy and are not faked.
 */
@Log4j2
public final class Server {

    /**
     * The API generation of the original server software this surface mirrors. Compatibility
     * plugins check this exactly like they do there.
     */
    public static final String API_VERSION = "5.0.0";

    private static volatile Server instance;

    private final ProxyServer handle;
    private final AtomicInteger formIds = new AtomicInteger(100000);

    private Server(ProxyServer handle) {
        this.handle = handle;
    }

    /**
     * Initializes the singleton; called once during proxy boot and safe to re-call (it swaps
     * the instance for a reload).
     */
    public static Server init(ProxyServer proxy) {
        Server server = new Server(proxy);
        instance = server;
        return server;
    }

    public static Server getInstance() {
        Server server = instance;
        if (server == null) {
            throw new IllegalStateException("The compatibility server facade is not initialized yet");
        }
        return server;
    }

    /**
     * True once boot created the facade - lets early plugin code probe availability.
     */
    public static boolean isAvailable() {
        return instance != null;
    }

    /**
     * The underlying proxy; the escape hatch for everything beyond the compatibility surface.
     */
    public ProxyServer getHandle() {
        return this.handle;
    }

    // ---- identity ----

    public String getName() {
        return "WaterbucketPE";
    }

    /**
     * The running proxy version, exactly as the start banner prints it.
     */
    public String getVersion() {
        return dev.waterbucket.waterbucketpe.WaterbucketPE.version().baseVersion();
    }

    public String getApiVersion() {
        return API_VERSION;
    }

    public String getDataPath() {
        return this.handle.getDataPath().toString();
    }

    public String getPluginPath() {
        return this.handle.getPluginPath().toString();
    }

    public String getIp() {
        return this.handle.getConfiguration().getBindAddress().getHostString();
    }

    public int getPort() {
        return this.handle.getConfiguration().getBindAddress().getPort();
    }

    public String getMotd() {
        return this.handle.getConfiguration().getMotd();
    }

    /**
     * Changes the first MOTD line the server list shows from now on (the same value the ping
     * path reads).
     */
    public void setMotd(String motd) {
        this.handle.getConfiguration().setMotd(motd == null ? "" : motd);
    }

    public String getSubMotd() {
        return this.handle.getConfiguration().getSubMotd();
    }

    public void setSubMotd(String subMotd) {
        this.handle.getConfiguration().setSubMotd(subMotd == null ? "" : subMotd);
    }

    public int getMaxPlayers() {
        return this.handle.getConfiguration().getMaxPlayerCount();
    }

    public int getOnlineCount() {
        return this.handle.getPlayerManager().getPlayerCount();
    }

    /**
     * All online players, keyed by their exact name.
     */
    public Map<String, Player> getOnlinePlayers() {
        Map<String, Player> players = new LinkedHashMap<>();
        for (ProxiedPlayer player : this.handle.getPlayerManager().getPlayers().values()) {
            players.put(player.getName(), new Player(player));
        }
        return java.util.Collections.unmodifiableMap(players);
    }

    /**
     * Case-insensitive exact lookup by name; null when nobody with that name is online.
     */
    public Player getPlayer(String name) {
        if (name == null || name.isEmpty()) {
            return null;
        }
        ProxiedPlayer player = this.handle.getPlayerManager().getPlayer(name);
        return player == null ? null : new Player(player);
    }

    /**
     * Exact, case-sensitive lookup; null when not found.
     */
    public Player getPlayerExact(String name) {
        if (name == null) {
            return null;
        }
        for (ProxiedPlayer player : this.handle.getPlayerManager().getPlayers().values()) {
            if (player.getName().equals(name)) {
                return new Player(player);
            }
        }
        return null;
    }

    /**
     * The first online player whose name starts with the prefix; null when none.
     */
    public Player matchPlayer(String prefix) {
        if (prefix == null || prefix.isEmpty()) {
            return null;
        }
        ProxiedPlayer player = this.handle.getPlayerManager().matchPlayer(prefix);
        return player == null ? null : new Player(player);
    }

    // ---- broadcasts ----

    public void broadcastMessage(String message) {
        this.broadcastMessage(message, null);
    }

    public void broadcastMessage(String message, List<Player> recipients) {
        for (Player player : this.targets(recipients)) {
            player.sendMessage(message);
        }
    }

    public void broadcastTip(String tip) {
        this.broadcastTip(tip, null);
    }

    public void broadcastTip(String tip, List<Player> recipients) {
        for (Player player : this.targets(recipients)) {
            player.sendTip(tip);
        }
    }

    public void broadcastPopup(String popup) {
        this.broadcastPopup(popup, "", null);
    }

    public void broadcastPopup(String popup, List<Player> recipients) {
        this.broadcastPopup(popup, "", recipients);
    }

    public void broadcastPopup(String popup, String subtitle, List<Player> recipients) {
        for (Player player : this.targets(recipients)) {
            player.sendPopup(popup, subtitle);
        }
    }

    public void broadcastTitle(String title) {
        this.broadcastTitle(title, "", null);
    }

    public void broadcastTitle(String title, String subtitle, List<Player> recipients) {
        for (Player player : this.targets(recipients)) {
            player.sendTitle(title, subtitle);
        }
    }

    private Iterable<Player> targets(List<Player> recipients) {
        return recipients == null ? this.getOnlinePlayers().values() : recipients;
    }

    // ---- commands ----

    /**
     * Dispatches a command line (slash optional) as the console. Returns false when no command
     * handled it.
     */
    public boolean dispatchCommand(String commandLine) {
        CommandSender sender = new ConsoleCommandSender(this.handle);
        return this.handle.dispatchCommand(sender, commandLine.startsWith("/")
                ? commandLine.substring(1) : commandLine);
    }

    /**
     * Dispatches a command line as the given player, exactly as if they typed it.
     */
    public boolean dispatchCommand(String commandLine, Player sender) {
        return this.handle.handlePlayerCommand(sender.getHandle(),
                commandLine.startsWith("/") ? commandLine : "/" + commandLine);
    }

    // ---- infrastructure facades ----

    public Scheduler getScheduler() {
        return new Scheduler(this.handle.getScheduler());
    }

    public PluginManagerCompat getPluginManager() {
        return new PluginManagerCompat(this.handle.getPluginManager());
    }

    public dev.waterbucket.waterbucketpe.logger.MainLogger getLogger() {
        return this.handle.getLogger();
    }

    public boolean isRunning() {
        return this.handle.isRunning();
    }

    /**
     * Stops the proxy cleanly, exactly like the console command does.
     */
    public void shutdown() {
        this.handle.shutdown();
    }

    /**
     * Allocates the next free form id for {@link Player#sendFormJson(String)}.
     */
    int nextFormId() {
        int next;
        do {
            next = this.formIds.getAndUpdate(v -> v >= Integer.MAX_VALUE - 1 ? 100000 : v + 1);
        } while (next >= Integer.MAX_VALUE - 1);
        return next;
    }

    /**
     * Resolves a player by any of the accepted spellings (exact name first, then case-
     * insensitive), or null.
     */
    public Player resolvePlayer(String name) {
        Player player = this.getPlayerExact(name);
        return player != null ? player : this.getPlayer(name.toLowerCase(Locale.ROOT));
    }
}
