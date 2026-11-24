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
import dev.waterbucket.waterbucketpe.sleep.SleepModeManager;
import dev.waterbucket.waterbucketpe.utils.types.TranslationContainer;

/**
 * Shows the proxy's sleep-mode state, or forces a sleep/wake transition.
 * <p>
 * Usage: {@code /wbsleep} (status), {@code /wbsleep sleep} (fall asleep immediately - only
 * possible with no players online), {@code /wbsleep wake} (wake up now).
 */
public class SleepCommand extends Command {

    public SleepCommand() {
        super("wbsleep", CommandSettings.builder()
                .setDescription("waterbucket.command.sleep.description")
                .setUsageMessage("waterbucket.command.sleep.usage")
                .setPermission("waterbucket.command.sleep.permission")
                .setAliases("wdsleep").build());
    }

    @Override
    public boolean onExecute(CommandSender sender, String alias, String[] args) {
        ProxyServer proxy = sender.getProxy();
        SleepModeManager sleep = proxy.getSleepModeManager();

        if (args.length == 0 || args[0].equalsIgnoreCase("status")) {
            sender.sendMessage(new TranslationContainer("waterbucket.command.sleep.status",
                    sleep.isSleeping() ? "sleeping" : "awake",
                    String.valueOf(proxy.getPlayers().size())));
            return true;
        }
        if (args[0].equalsIgnoreCase("wake")) {
            sleep.onActivity();
            sender.sendMessage(new TranslationContainer("waterbucket.command.sleep.awake"));
            return true;
        }
        if (args[0].equalsIgnoreCase("sleep")) {
            if (!proxy.getPlayers().isEmpty()) {
                sender.sendMessage(new TranslationContainer("waterbucket.command.sleep.players-online"));
                return true;
            }
            sleep.onActivity(); // reset the idle window
            sleep.forceSleep();
            sender.sendMessage(new TranslationContainer("waterbucket.command.sleep.sleeping"));
            return true;
        }
        sender.sendMessage(new TranslationContainer("waterbucket.command.sleep.usage"));
        return true;
    }
}
