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

package dev.waterbucket.waterbucketpe.network.goruntime;

import com.google.gson.Gson;
import dev.waterbucket.waterbucketpe.ProxyServer;
import dev.waterbucket.waterbucketpe.command.CommandSender;
import dev.waterbucket.waterbucketpe.command.ConsoleCommandSender;
import dev.waterbucket.waterbucketpe.player.ProxiedPlayer;
import lombok.extern.log4j.Log4j2;

import java.util.ArrayList;
import java.util.List;

/**
 * Dispatches the Go runtime's RPC calls onto real proxy operations. The
 * method table is a fixed whitelist - no reflection - so a buggy plugin can
 * reach exactly the documented surface and nothing else.
 */
@Log4j2
public final class ProxyApiFacade {

    private static final Gson GSON = new Gson();

    private final ProxyServer proxy;

    public ProxyApiFacade(ProxyServer proxy) {
        this.proxy = proxy;
    }

    public GoRuntimeManager.RpcResult dispatch(String obj, String method, List<BridgeCodec.Field> args) {
        try {
            if ("player".equals(obj)) {
                return dispatchPlayer(method, args);
            }
            if ("server".equals(obj)) {
                return dispatchServer(method, args);
            }
            return GoRuntimeManager.RpcResult.fail("unknown object " + obj);
        } catch (Exception e) {
            log.debug("[GoRuntime] rpc {}.{} failed: {}", obj, method, e.getMessage());
            return GoRuntimeManager.RpcResult.fail(e.getMessage() == null ? e.toString() : e.getMessage());
        }
    }

    private GoRuntimeManager.RpcResult dispatchPlayer(String method, List<BridgeCodec.Field> args) {
        if (args.isEmpty() || args.get(0).type() != BridgeCodec.TYPE_STR) {
            return GoRuntimeManager.RpcResult.fail("missing player name");
        }
        String name = args.get(0).str();
        ProxiedPlayer player = this.proxy.getPlayerManager().getPlayer(name);
        if (player == null) {
            return GoRuntimeManager.RpcResult.fail("player " + name + " is not online");
        }
        String a1 = args.size() > 1 && args.get(1).type() == BridgeCodec.TYPE_STR ? args.get(1).str() : "";
        String a2 = args.size() > 2 && args.get(2).type() == BridgeCodec.TYPE_STR ? args.get(2).str() : "";
        switch (method) {
            case "sendMessage" -> player.sendMessage(a1);
            case "sendTip" -> player.sendTip(a1);
            case "sendPopup" -> player.sendPopup(a1, a2);
            case "sendTitle" -> player.sendTitle(a1, a2);
            case "kick" -> player.disconnect(a1.isEmpty() ? "Disconnected by the proxy" : a1);
            case "transfer" -> {
                var serverInfo = this.proxy.getServerInfo(a1);
                if (serverInfo == null) {
                    return GoRuntimeManager.RpcResult.fail("unknown server " + a1);
                }
                player.connect(serverInfo);
            }
            default -> {
                return GoRuntimeManager.RpcResult.fail("unknown player method " + method);
            }
        }
        return GoRuntimeManager.RpcResult.ok(List.of());
    }

    private GoRuntimeManager.RpcResult dispatchServer(String method, List<BridgeCodec.Field> args) {
        switch (method) {
            case "getOnlinePlayers" -> {
                List<String> names = new ArrayList<>();
                for (ProxiedPlayer player : this.proxy.getPlayerManager().getPlayers().values()) {
                    names.add(player.getName());
                }
                return GoRuntimeManager.RpcResult.ok(List.of(BridgeCodec.Field.ofStr(GSON.toJson(names))));
            }
            case "getOnlineCount" -> {
                return GoRuntimeManager.RpcResult.ok(List.of(
                        BridgeCodec.Field.ofI32(this.proxy.getPlayerManager().getPlayerCount())));
            }
            case "broadcastMessage" -> {
                String message = args.isEmpty() ? "" : args.get(0).str();
                this.proxy.broadcastMessage(message);
                return GoRuntimeManager.RpcResult.ok(List.of());
            }
            case "dispatchCommand" -> {
                String line = args.isEmpty() ? "" : args.get(0).str();
                if (line.startsWith("/")) {
                    line = line.substring(1);
                }
                CommandSender sender = new ConsoleCommandSender(this.proxy);
                boolean handled = this.proxy.dispatchCommand(sender, line);
                return GoRuntimeManager.RpcResult.ok(List.of(BridgeCodec.Field.ofBool(handled)));
            }
            case "dispatchCommandAs" -> {
                if (args.size() < 2) {
                    return GoRuntimeManager.RpcResult.fail("dispatchCommandAs needs player + command");
                }
                ProxiedPlayer player = this.proxy.getPlayerManager().getPlayer(args.get(0).str());
                if (player == null) {
                    return GoRuntimeManager.RpcResult.fail("player not online");
                }
                String line = args.get(1).str();
                if (!line.startsWith("/")) {
                    line = "/" + line;
                }
                boolean handled = this.proxy.handlePlayerCommand(player, line);
                return GoRuntimeManager.RpcResult.ok(List.of(BridgeCodec.Field.ofBool(handled)));
            }
            case "getProxyVersion" -> {
                return GoRuntimeManager.RpcResult.ok(List.of(
                        BridgeCodec.Field.ofStr(dev.waterbucket.waterbucketpe.WaterbucketPE.version().baseVersion())));
            }
            default -> {
                return GoRuntimeManager.RpcResult.fail("unknown server method " + method);
            }
        }
    }
}
