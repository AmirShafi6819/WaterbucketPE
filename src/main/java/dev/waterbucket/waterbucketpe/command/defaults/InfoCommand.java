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

import dev.waterbucket.waterbucketpe.VersionInfo;
import dev.waterbucket.waterbucketpe.WaterbucketPE;
import dev.waterbucket.waterbucketpe.command.Command;
import dev.waterbucket.waterbucketpe.command.CommandSender;
import dev.waterbucket.waterbucketpe.command.CommandSettings;

public class InfoCommand extends Command {

    public InfoCommand() {
        super("wdinfo", CommandSettings.builder()
                .setDescription("waterbucket.command.info.description")
                .setUsageMessage("waterbucket.command.info.usage")
                .setPermission("waterbucket.command.info.permission")
                .build());
    }

    @Override
    public boolean onExecute(CommandSender sender, String alias, String[] args) {
        VersionInfo versionInfo = WaterbucketPE.version();
        sender.sendMessage("§bRunning WaterbucketPE version §3" + versionInfo.baseVersion() + "§b!\n" +
                "§3Build Version: §b" + versionInfo.buildVersion() + "\n" +
                "§3Latest Protocol: §b" + versionInfo.latestProtocolVersion() + "\n" +
                "§3Branch: §b" + versionInfo.branchName() + "§3 CommitId:§b " + versionInfo.commitId() + "\n" +
                "§3Author: §b" + versionInfo.author() + "\n" +
                "§3Developer Mode: " + (versionInfo.debug() ? "§cenabled" : "§adisabled"));
        return true;
    }
}
