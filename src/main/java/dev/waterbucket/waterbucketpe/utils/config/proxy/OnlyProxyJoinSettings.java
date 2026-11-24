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

package dev.waterbucket.waterbucketpe.utils.config.proxy;

import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;
import net.cubespace.Yamler.Config.Comment;
import net.cubespace.Yamler.Config.Comments;
import net.cubespace.Yamler.Config.Path;

/**
 * Only Proxy Join: when enabled, the proxy maintains host firewall rules that block direct
 * public access to every downstream server's UDP port, so the only way in is through the
 * proxy on its own port. Loopback traffic (the proxy itself connecting to the downstreams)
 * is explicitly allowed.
 */
@Getter
@Setter
public class OnlyProxyJoinSettings extends SettingsSection {

    @Path("enable")
    @Accessors(fluent = true)
    @Comments({
            "Only Proxy Join: block direct joins to downstream servers so players can only enter",
            "through the proxy port. The proxy keeps host firewall rules for every configured",
            "downstream port (loopback stays allowed). Requires admin/root privileges once to apply."
    })
    private boolean enable = false;

    @Path("proxy_port")
    @Comment("The public port players must use to join through the proxy (informational + status checks)")
    private int proxyPort = 19132;

    @Path("manage_firewall")
    @Accessors(fluent = true)
    @Comments({
            "Let the proxy manage the host firewall rules itself (iptables on Linux, Windows",
            "Defender Firewall on Windows). Set false to manage the rules with your own tooling:",
            "the /wbonlyproxy status command then only reports what is missing."
    })
    private boolean manageFirewall = true;
}
