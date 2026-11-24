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

package dev.waterbucket.waterbucketpe.command.defaults;

import dev.waterbucket.waterbucketpe.ProxyServer;
import dev.waterbucket.waterbucketpe.command.Command;
import dev.waterbucket.waterbucketpe.command.CommandSender;
import dev.waterbucket.waterbucketpe.command.CommandSettings;
import dev.waterbucket.waterbucketpe.utils.types.TranslationContainer;

/**
 * Shows or changes the effective maximum player count at runtime. The value is persisted to
 * config.yml so it survives a restart; {@code 0} disables the limit. The same API is exposed
 * to plugins through {@code ProxyServer.setMaxPlayers(int)}.
 */
public class MaxPlayersCommand extends Command {

    public MaxPlayersCommand() {
        super("wbmaxplayers", CommandSettings.builder()
                .setDescription("waterbucket.command.maxplayers.description")
                .setUsageMessage("waterbucket.command.maxplayers.usage")
                .setPermission("waterbucket.command.maxplayers.permission")
                .setAliases("wdmaxplayers").build());
    }

    @Override
    public boolean onExecute(CommandSender sender, String alias, String[] args) {
        ProxyServer proxy = sender.getProxy();

        if (args.length == 0) {
            int max = proxy.getMaxPlayers();
            sender.sendMessage(new TranslationContainer("waterbucket.command.maxplayers.current",
                    String.valueOf(proxy.getPlayers().size()), String.valueOf(max),
                    max > 0 ? String.valueOf(max) : "unlimited"));
            return true;
        }

        int max;
        try {
            max = Integer.parseInt(args[0]);
        } catch (NumberFormatException e) {
            sender.sendMessage(new TranslationContainer("waterbucket.command.maxplayers.invalid", args[0]));
            return true;
        }

        if (max < 0) {
            sender.sendMessage(new TranslationContainer("waterbucket.command.maxplayers.invalid", args[0]));
            return true;
        }

        proxy.setMaxPlayers(max, true);
        sender.sendMessage(new TranslationContainer("waterbucket.command.maxplayers.set",
                max > 0 ? String.valueOf(max) : "unlimited"));
        return true;
    }

}
