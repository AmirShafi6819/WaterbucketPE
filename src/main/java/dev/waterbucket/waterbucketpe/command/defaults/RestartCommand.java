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
import dev.waterbucket.waterbucketpe.utils.types.TranslationContainer;

/**
 * Gracefully restarts the proxy: plugins get their veto via {@code ProxyRestartEvent},
 * players are disconnected with the restart messages, plugins are disabled through the
 * normal shutdown path and a relaunch script brings the proxy back with new jars, packs
 * and config values picked up. The heavy lifting lives in {@code RestartManager}.
 */
public class RestartCommand extends Command {

    public RestartCommand() {
        super("restart", CommandSettings.builder()
                .setDescription("waterbucket.command.restart.description")
                .setUsageMessage("waterbucket.command.restart.usage")
                .setPermission("waterbucket.command.restart.permission")
                .setAliases("wdrestart").build());
    }

    @Override
    public boolean onExecute(CommandSender sender, String alias, String[] args) {
        boolean accepted = ProxyServer.getInstance().getRestartManager().requestRestart(sender.getName());
        sender.sendMessage(new TranslationContainer(accepted
                ? "waterbucket.restart.success"
                : "waterbucket.restart.busy"));
        return true;
    }
}
