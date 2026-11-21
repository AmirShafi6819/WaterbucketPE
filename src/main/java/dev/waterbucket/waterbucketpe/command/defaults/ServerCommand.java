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

import dev.waterbucket.waterbucketpe.command.Command;
import dev.waterbucket.waterbucketpe.command.CommandSender;
import dev.waterbucket.waterbucketpe.command.CommandSettings;
import dev.waterbucket.waterbucketpe.network.serverinfo.ServerInfo;
import dev.waterbucket.waterbucketpe.player.ProxiedPlayer;
import dev.waterbucket.waterbucketpe.utils.types.TextContainer;
import dev.waterbucket.waterbucketpe.utils.types.TranslationContainer;
import org.cloudburstmc.protocol.bedrock.data.command.CommandOverloadData;
import org.cloudburstmc.protocol.bedrock.data.command.CommandParam;
import org.cloudburstmc.protocol.bedrock.data.command.CommandParamData;


public class ServerCommand extends Command {

    public ServerCommand() {
        super("server", CommandSettings.builder()
                .setDescription("waterbucket.command.server.description")
                .setUsageMessage("waterbucket.command.server.usage")
                .setPermission("waterbucket.command.server.permission")
                .build());
    }

    @Override
    public boolean onExecute(CommandSender sender, String alias, String[] args) {
        if (args.length < 1 || !sender.isPlayer() && args.length < 2) {
            return false;
        }

        ServerInfo server = sender.getProxy().getServerInfo(args[0]);
        if (server == null) {
            sender.sendMessage("§cServer not found!");
            return true;
        }

        ProxiedPlayer player;
        if (sender.isPlayer() && args.length < 2) {
            player = (ProxiedPlayer) sender;
        } else {
            if (!sender.hasPermission(new TranslationContainer("waterbucket.command.server.permission.other").getTranslated())) {
                sender.sendMessage(new TextContainer("§cYou don't have the permission to move other players."));
                return false;
            }

            player = sender.getProxy().getPlayer(args[1]);
            if (player == null) {
                sender.sendMessage("§cPlayer not found!");
                return true;
            }
            sender.sendMessage(new TextContainer("§aPlayer {%0} was sent to {%1} server!", player.getName(), server.getServerName()));
        }

        player.connect(server);
        return true;
    }

    @Override
    protected CommandOverloadData[] buildCommandOverloads() {
        CommandParamData server = new CommandParamData();
        server.setName("server");
        server.setOptional(false);
        server.setType(CommandParam.TEXT);

        CommandParamData player = new CommandParamData();
        player.setName("player");
        player.setOptional(true);
        player.setType(CommandParam.TARGET);
        return new CommandOverloadData[]{new CommandOverloadData(false, new CommandParamData[]{server, player})};
    }
}
