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

package dev.waterbucket.waterbucketpe.command;

import dev.waterbucket.waterbucketpe.ProxyServer;
import dev.waterbucket.waterbucketpe.utils.types.TextContainer;
import it.unimi.dsi.fastutil.objects.Object2ObjectMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectMaps;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import dev.waterbucket.waterbucketpe.utils.types.TranslationContainer;

public class SimpleCommandMap implements CommandMap {

    public static final String DEFAULT_PREFIX = "/";

    private final ProxyServer proxy;
    private final String commandPrefix;

    /*
     * Registrations can arrive at runtime (Python plugins may register commands from event
     * handlers or scheduled tasks) while every event loop reads the maps for
     * AvailableCommandsPacket building and dispatch, so both maps are synchronized wrappers.
     * Iteration through getCommands() still requires external synchronization on the map, as
     * before; the wrapper protects the mutation paths the proxy itself owns.
     */
    private final Object2ObjectMap<String, Command> commandsMap =
            Object2ObjectMaps.synchronize(new Object2ObjectOpenHashMap<>());
    private final Object2ObjectMap<String, Command> aliasesMap =
            Object2ObjectMaps.synchronize(new Object2ObjectOpenHashMap<>());

    public SimpleCommandMap(ProxyServer proxy, String prefix) {
        this.proxy = proxy;
        this.commandPrefix = prefix;
    }

    @Override
    @Deprecated
    public boolean registerCommand(String name, Command command) {
        if (this.commandsMap.putIfAbsent(name.toLowerCase(), command) != null) {
            return false;
        }
        for (String alias : command.getAliases()) {
            this.registerAlias(alias, command);
        }
        return true;
    }

    @Override
    public boolean registerCommand(Command command) {
        if (this.commandsMap.putIfAbsent(command.getName().toLowerCase(), command) != null) {
            return false;
        }
        for (String alias : command.getAliases()) {
            this.registerAlias(alias, command);
        }
        return true;
    }

    @Override
    public boolean registerAlias(String name, Command command) {
        return this.aliasesMap.putIfAbsent(name.toLowerCase(), command) == null;
    }

    @Override
    public boolean unregisterCommand(String name) {
        Command command = this.commandsMap.remove(name.toLowerCase());
        if (command == null) return false;

        for (String alias : command.getAliases()) {
            // Remove an alias only when this command actually owns it: registerAlias is
            // putIfAbsent, so a conflicting alias still resolves to whichever command claimed
            // the name first. The previous unconditional remove deleted other commands'
            // aliases and left them unreachable.
            this.aliasesMap.remove(alias.toLowerCase(), command);
        }
        return true;
    }

    @Override
    public Command getCommand(String name) {
        Command result = this.commandsMap.get(name.toLowerCase());

        if(result == null){
            result = this.aliasesMap.get(name.toLowerCase());
        }

        return result;
    }

    @Override
    public boolean isRegistered(String name) {
        return this.commandsMap.containsKey(name.toLowerCase());
    }

    @Override
    public boolean handleMessage(CommandSender sender, String message) {
        return !message.trim().isEmpty() && message.startsWith(this.commandPrefix);
    }

    @Override
    public boolean handleCommand(CommandSender sender, String commandName, String[] args) {
        return this.handleCommand(sender, this.getCommand(commandName), commandName, args);
    }

    @Override
    public boolean handleCommand(CommandSender sender, Command command, String alias, String[] args) {
        if (command == null) {
            if (!sender.isPlayer()) { // Player commands may be handled by servers
                sender.sendMessage(new TranslationContainer("waterbucket.command.unknown"));
            }
            return false;
        }
        // Alias stays null when the command was invoked by its registered name
        this.executeInternal(sender, command, this.isRegistered(alias) ? null : alias, args);
        return true;
    }

    private void executeInternal(CommandSender sender, Command command, String alias, String[] args) {
        boolean permission = sender.hasPermission(command.getPermission());
        if (!permission) {
            sender.sendMessage(new TextContainer(command.getPermissionMessage(), command.getName(), command.getPermission()));
            return;
        }

        try {
            boolean success = command.onExecute(sender, alias, args);
            if (!success) {
                sender.sendMessage("§cCommand usage: " + command.getUsageMessage());
            }
        } catch (Exception e) {
            this.proxy.getLogger().error("Error appeared while processing command!", e);
        }
    }

    @Override
    public String getCommandPrefix() {
        return this.commandPrefix;
    }

    @Override
    public Object2ObjectMap<String, Command> getCommands() {
        return this.commandsMap;
    }
}
