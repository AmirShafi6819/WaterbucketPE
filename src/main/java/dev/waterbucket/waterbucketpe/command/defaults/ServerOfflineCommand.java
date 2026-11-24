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

package dev.waterbucket.waterbucketpe.command.defaults;

import dev.waterbucket.waterbucketpe.ProxyServer;
import dev.waterbucket.waterbucketpe.command.Command;
import dev.waterbucket.waterbucketpe.command.CommandSender;
import dev.waterbucket.waterbucketpe.command.CommandSettings;
import dev.waterbucket.waterbucketpe.network.serverinfo.ServerInfo;
import dev.waterbucket.waterbucketpe.utils.config.ServerEntry;
import dev.waterbucket.waterbucketpe.utils.config.proxy.ProxyConfig;
import net.cubespace.Yamler.Config.InvalidConfigurationException;
import dev.waterbucket.waterbucketpe.utils.types.TranslationContainer;

/**
 * Marks a downstream server offline or online at runtime: a disabled server still runs, but
 * the proxy reports it offline (panel, pings), skips it in join/fallback priority lists and
 * refuses new joins with the normal offline failure. The state is persisted to config.yml
 * ({@code servers.<name>.disabled: true}) so it survives restarts; players currently on the
 * server are not kicked.
 * <p>
 * Usage: {@code /wbserveroffline <server> [on|off]}. Without the second argument the command
 * toggles the current state.
 */
public class ServerOfflineCommand extends Command {

    public ServerOfflineCommand() {
        super("wbserveroffline", CommandSettings.builder()
                .setDescription("waterbucket.command.serveroffline.description")
                .setUsageMessage("waterbucket.command.serveroffline.usage")
                .setPermission("waterbucket.command.serveroffline.permission")
                .setAliases("wdserveroffline").build());
    }

    @Override
    public boolean onExecute(CommandSender sender, String alias, String[] args) {
        ProxyServer proxy = sender.getProxy();

        if (args.length < 1) {
            sender.sendMessage(new TranslationContainer("waterbucket.command.serveroffline.usage"));
            return true;
        }

        ServerInfo server = proxy.getServerInfo(args[0]);
        if (server == null) {
            sender.sendMessage(new TranslationContainer("waterbucket.command.serveroffline.unknown", args[0]));
            return true;
        }

        boolean target;
        if (args.length >= 2) {
            if (args[1].equalsIgnoreCase("on") || args[1].equalsIgnoreCase("true")) {
                target = true;
            } else if (args[1].equalsIgnoreCase("off") || args[1].equalsIgnoreCase("false")) {
                target = false;
            } else {
                sender.sendMessage(new TranslationContainer("waterbucket.command.serveroffline.usage"));
                return true;
            }
        } else {
            target = !server.isDisabled();
        }

        if (target == server.isDisabled()) {
            sender.sendMessage(new TranslationContainer(target
                            ? "waterbucket.command.serveroffline.already-offline"
                            : "waterbucket.command.serveroffline.already-online",
                    server.getServerName()));
            return true;
        }

        server.setDisabled(target);
        this.persist(proxy, server.getServerName(), target);

        sender.sendMessage(new TranslationContainer(
                target ? "waterbucket.command.serveroffline.marked-offline" : "waterbucket.command.serveroffline.marked-online",
                server.getServerName()));
        if (target) {
            proxy.getLogger().info("Server '{}' was marked offline by {} - joins are refused while the "
                    + "downstream keeps running", server.getServerName(), sender.getName());
        }
        return true;
    }

    /**
     * Writes the flag back into the servers section of config.yml. The upstream entry is
     * rebuilt (entries are immutable) and the config saved with the same error handling as
     * the max-players persistence path.
     */
    private void persist(ProxyServer proxy, String serverName, boolean disabled) {
        ProxyConfig config = proxy.getConfiguration();
        ServerEntry entry = config.getServerList().get(serverName);
        if (entry == null) {
            // Runtime-only registration without a config entry: nothing to persist
            return;
        }
        config.getServerList().put(serverName, new ServerEntry(
                entry.getServerName(), entry.getAddress(), entry.getPublicAddress(),
                entry.getServerType(), disabled));
        try {
            config.save();
        } catch (InvalidConfigurationException e) {
            proxy.getLogger().error("Could not persist servers.{}.disabled to config.yml!", serverName, e);
        }
    }
}
