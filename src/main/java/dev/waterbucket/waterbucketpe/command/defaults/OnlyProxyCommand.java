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
import dev.waterbucket.waterbucketpe.onlyproxy.OnlyProxyJoinManager;
import dev.waterbucket.waterbucketpe.utils.types.TranslationContainer;

import java.util.Map;

/**
 * Controls Only Proxy Join at runtime: shows which downstream ports are protected, or
 * applies/removes the host firewall rules immediately.
 * <p>
 * Usage: {@code /wbonlyproxy} (status), {@code /wbonlyproxy apply}, {@code /wbonlyproxy remove}.
 */
public class OnlyProxyCommand extends Command {

    public OnlyProxyCommand() {
        super("wbonlyproxy", CommandSettings.builder()
                .setDescription("waterbucket.command.onlyproxy.description")
                .setUsageMessage("waterbucket.command.onlyproxy.usage")
                .setPermission("waterbucket.command.onlyproxy.permission")
                .setAliases("wdonlyproxy").build());
    }

    @Override
    public boolean onExecute(CommandSender sender, String alias, String[] args) {
        ProxyServer proxy = sender.getProxy();
        OnlyProxyJoinManager manager = proxy.getOnlyProxyJoinManager();

        if (args.length == 0 || args[0].equalsIgnoreCase("status")) {
            Map<Integer, Boolean> status = manager.status();
            StringBuilder ports = new StringBuilder();
            for (Map.Entry<Integer, Boolean> entry : status.entrySet()) {
                if (ports.length() > 0) {
                    ports.append(", ");
                }
                ports.append(entry.getKey()).append(entry.getValue() ? " §a✔" : " §c✘");
            }
            sender.sendMessage(new TranslationContainer("waterbucket.command.onlyproxy.status",
                    manager.isApplied() ? "on" : "off",
                    String.valueOf(proxy.getConfiguration().getOnlyProxyJoinSettings().getProxyPort()),
                    ports.length() == 0 ? "-" : ports.toString()));
            return true;
        }
        if (args[0].equalsIgnoreCase("apply")) {
            boolean ok = manager.apply();
            sender.sendMessage(new TranslationContainer(ok
                    ? "waterbucket.command.onlyproxy.applied"
                    : "waterbucket.command.onlyproxy.failed",
                    String.valueOf(proxy.getConfiguration().getOnlyProxyJoinSettings().getProxyPort())));
            return true;
        }
        if (args[0].equalsIgnoreCase("remove")) {
            manager.remove();
            sender.sendMessage(new TranslationContainer("waterbucket.command.onlyproxy.removed"));
            return true;
        }
        sender.sendMessage(new TranslationContainer("waterbucket.command.onlyproxy.usage"));
        return true;
    }
}
