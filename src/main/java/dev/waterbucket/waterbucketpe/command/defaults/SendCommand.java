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
import org.cloudburstmc.protocol.bedrock.data.command.CommandOverloadData;
import org.cloudburstmc.protocol.bedrock.data.command.CommandParam;
import org.cloudburstmc.protocol.bedrock.data.command.CommandParamData;

public class SendCommand extends Command {

    public SendCommand() {
        super("wdsend", CommandSettings.builder()
                .setDescription("waterbucket.command.send.description")
                .setUsageMessage("waterbucket.command.send.usage")
                .setPermission("waterbucket.command.send.permission")
                .build());
    }

    @Override
    public boolean onExecute(CommandSender sender, String alias, String[] args) {
        if (args.length < 1 || !sender.isPlayer() && args.length < 2) {
            return false;
        }

        ServerInfo server = sender.getProxy().getServerInfo(args[0]);
        if (server == null) {
            sender.sendMessage(new TextContainer("§cServer {%0} was not found!", args[0]));
            return true;
        }

        ServerInfo targetServer;
        if (sender.isPlayer() && args.length < 2) {
            targetServer = ((ProxiedPlayer) sender).getServerInfo();
        } else {
            targetServer = sender.getProxy().getServerInfo(args[1]);
        }

        if (targetServer == null) {
            sender.sendMessage(new TextContainer("§cCould not find target server {%0}!", args[1]));
            return true;
        }

        for (ProxiedPlayer player : targetServer.getPlayers()) {
            player.connect(server);
        }
        return true;
    }

    @Override
    protected CommandOverloadData[] buildCommandOverloads() {
        CommandParamData destination = new CommandParamData();
        destination.setName("destination");
        destination.setOptional(false);
        destination.setType(CommandParam.TEXT);

        CommandParamData target = new CommandParamData();
        target.setName("target");
        target.setOptional(false);
        target.setType(CommandParam.TEXT);

        return new CommandOverloadData[]{new CommandOverloadData(false, new CommandParamData[]{destination, target})};
    }
}
