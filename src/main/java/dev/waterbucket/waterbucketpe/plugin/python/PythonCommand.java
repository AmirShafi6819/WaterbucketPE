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

package dev.waterbucket.waterbucketpe.plugin.python;

import dev.waterbucket.waterbucketpe.command.Command;
import dev.waterbucket.waterbucketpe.command.CommandSender;
import dev.waterbucket.waterbucketpe.command.CommandSettings;

import java.util.List;
import java.util.Set;

/**
 * A proxy command executed by a Python callable of a {@link PythonPlugin}.
 */
class PythonCommand extends Command {

    private final PythonPlugin plugin;
    private final PythonCommandExecutor handler;

    PythonCommand(PythonPlugin plugin, String name, String description, String usage,
                  List<String> aliases, String permission, PythonCommandExecutor handler) {
        super(name, CommandSettings.builder()
                .setDescription(description == null ? "" : description)
                .setUsageMessage(usage == null ? "/" + name : usage)
                .setPermission(permission == null ? "" : permission)
                .setAliases(aliases == null ? new String[0] : aliases.toArray(new String[0]))
                .build());
        this.plugin = plugin;
        this.handler = handler;
    }

    @Override
    public boolean onExecute(CommandSender sender, String alias, String[] args) {
        try {
            Boolean result = this.handler.execute(sender, alias, args);
            return result == null || result;
        } catch (Throwable t) {
            this.plugin.getLogger().error("Python command /" + this.getName() + " failed", t);
            return false;
        }
    }
}
