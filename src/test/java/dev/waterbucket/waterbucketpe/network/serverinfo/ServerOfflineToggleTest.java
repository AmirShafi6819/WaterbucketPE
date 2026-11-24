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

package dev.waterbucket.waterbucketpe.network.serverinfo;

import dev.waterbucket.waterbucketpe.ProxyServer;
import dev.waterbucket.waterbucketpe.network.connection.handler.DefaultJoinHandler;
import dev.waterbucket.waterbucketpe.network.connection.handler.DefaultReconnectHandler;
import dev.waterbucket.waterbucketpe.network.connection.handler.ReconnectReason;
import dev.waterbucket.waterbucketpe.player.ProxiedPlayer;
import dev.waterbucket.waterbucketpe.utils.config.ServerEntry;
import dev.waterbucket.waterbucketpe.utils.config.serializer.ServerEntryConverter;
import net.cubespace.Yamler.Config.ConfigSection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests the operator-controlled offline flag: ping/connection refusal, join and fallback
 * handler skipping, and the config round-trip of the {@code disabled} entry field.
 */
class ServerOfflineToggleTest {

    private ProxyServer proxy;
    private BedrockServerInfo server;

    @BeforeEach
    void setUp() {
        this.proxy = mock(ProxyServer.class, Mockito.withSettings().stubOnly());
        when(this.proxy.getConfiguration()).thenReturn(mock(dev.waterbucket.waterbucketpe.utils.config.proxy.ProxyConfig.class, Mockito.withSettings().stubOnly()));
        // A loop stub whose promises come from the global executor: the disabled guards build
        // their failed promises on the player's loop but never touch a real transport.
        io.netty.util.concurrent.EventExecutor globals = io.netty.util.concurrent.GlobalEventExecutor.INSTANCE;
        io.netty.channel.EventLoop loop = mock(io.netty.channel.EventLoop.class, Mockito.withSettings().stubOnly());
        when(loop.newPromise()).thenAnswer(invocation -> globals.newPromise());
        io.netty.channel.EventLoopGroup group = mock(io.netty.channel.EventLoopGroup.class, Mockito.withSettings().stubOnly());
        when(group.next()).thenReturn(loop);
        when(this.proxy.getWorkerEventLoopGroup()).thenReturn(group);
        this.server = new BedrockServerInfo("lobby1",
                new InetSocketAddress("127.0.0.1", 30101), null);
    }

    @Test
    void disabledServerRefusesPingAndConnection() {
        assertFalse(this.server.isDisabled(), "servers start available");
        this.server.setDisabled(true);

        // Ping fails immediately (offline appearance) without touching the network stack
        var ping = this.server.ping(500, TimeUnit.MILLISECONDS);
        ping.awaitUninterruptibly(50);
        assertTrue(ping.cause() instanceof ServerDisabledException,
                "a disabled server must fail its ping with the disabled reason");

        // Connection attempts fail before any channel work - no event loop is touched
        ProxiedPlayer player = mock(ProxiedPlayer.class);
        when(player.getProxy()).thenReturn(this.proxy);
        var connect = this.server.createConnection(player);
        connect.awaitUninterruptibly(50);
        assertTrue(connect.cause() instanceof ServerDisabledException,
                "a disabled server must refuse connection attempts");
    }

    @Test
    void reEnabledServerAcceptsAgain() {
        this.server.setDisabled(true);
        this.server.setDisabled(false);
        assertFalse(this.server.isDisabled());
        // The refusal was flag-driven only; with the flag cleared the ping would need the
        // network stack, so only the flag state is asserted here.
    }

    @Test
    void joinHandlerSkipsDisabledServers() {
        BedrockServerInfo first = new BedrockServerInfo("lobby1",
                new InetSocketAddress("127.0.0.1", 30101), null);
        BedrockServerInfo second = new BedrockServerInfo("lobby2",
                new InetSocketAddress("127.0.0.1", 30102), null);
        first.setDisabled(true);

        when(this.proxy.getConfiguration().getPriorities()).thenReturn(List.of("lobby1", "lobby2"));
        when(this.proxy.getServerInfo(anyString())).thenAnswer(invocation -> switch (invocation.getArgument(0, String.class)) {
            case "lobby1" -> first;
            case "lobby2" -> second;
            default -> null;
        });

        ProxiedPlayer player = mock(ProxiedPlayer.class);
        when(player.getProxy()).thenReturn(this.proxy);
        ServerInfo chosen = new DefaultJoinHandler().determineServer(player);
        assertNotNull(chosen, "an available lower-priority server must be chosen");
        assertEquals("lobby2", chosen.getServerName(), "disabled servers are skipped in the priority list");

        // With everything disabled there is no server answer, never a crash
        second.setDisabled(true);
        assertEquals(null, new DefaultJoinHandler().determineServer(player), "all disabled = no server");
    }

    @Test
    void reconnectHandlerSkipsDisabledServers() {
        BedrockServerInfo first = new BedrockServerInfo("lobby1",
                new InetSocketAddress("127.0.0.1", 30101), null);
        BedrockServerInfo second = new BedrockServerInfo("lobby2",
                new InetSocketAddress("127.0.0.1", 30102), null);
        second.setDisabled(true);

        when(this.proxy.getConfiguration().getPriorities()).thenReturn(List.of("lobby1", "lobby2", "lobby3"));
        BedrockServerInfo third = new BedrockServerInfo("lobby3",
                new InetSocketAddress("127.0.0.1", 30103), null);
        when(this.proxy.getServerInfo(anyString())).thenAnswer(invocation -> switch (invocation.getArgument(0, String.class)) {
            case "lobby1" -> first;
            case "lobby2" -> second;
            case "lobby3" -> third;
            default -> null;
        });

        ProxiedPlayer player = mock(ProxiedPlayer.class);
        when(player.getProxy()).thenReturn(this.proxy);
        ServerInfo fallback = new DefaultReconnectHandler()
                .getFallbackServer(player, first, ReconnectReason.TIMEOUT, "kicked");
        assertNotNull(fallback, "a fallback must exist past the disabled entry");
        assertEquals("lobby3", fallback.getServerName(), "the disabled lobby2 must be skipped");

        // Re-enabling lobby2 makes it the fallback again
        second.setDisabled(false);
        fallback = new DefaultReconnectHandler()
                .getFallbackServer(player, first, ReconnectReason.TIMEOUT, "kicked");
        assertEquals("lobby2", fallback.getServerName());
    }

    @Test
    void disabledFlagRoundTripsThroughTheConverter() throws Exception {
        net.cubespace.Yamler.Config.InternalConverter internal = new net.cubespace.Yamler.Config.InternalConverter();
        internal.addConverter(dev.waterbucket.waterbucketpe.utils.config.serializer.InetSocketAddressConverter.class);
        ServerEntryConverter converter = new ServerEntryConverter(internal);

        ServerEntry disabled = new ServerEntry("lobby1",
                new InetSocketAddress("127.0.0.1", 30101), null, "bedrock", true);
        java.util.Map<String, Object> serialized = (java.util.Map<String, Object>) converter.toConfig(
                ServerEntry.class, disabled, null);
        assertEquals(Boolean.TRUE, serialized.get("disabled"), "disabled entries serialize the flag");

        // Round-trip through the ConfigSection branch
        ConfigSection section = new ConfigSection();
        section.set("name", "lobby1");
        section.set("address", "127.0.0.1:30101");
        section.set("server_type", "bedrock");
        section.set("disabled", true);
        ServerEntry parsed = (ServerEntry) converter.fromConfig(ServerEntry.class, section, null);
        assertTrue(parsed.isDisabled(), "a disabled entry must parse back as disabled");

        // Absent flag = enabled (byte-identical old configs)
        ConfigSection plain = new ConfigSection();
        plain.set("name", "lobby1");
        plain.set("address", "127.0.0.1:30101");
        plain.set("server_type", "bedrock");
        ServerEntry enabled = (ServerEntry) converter.fromConfig(ServerEntry.class, plain, null);
        assertFalse(enabled.isDisabled(), "entries without the flag stay enabled");

        // Enabled entries do not write the flag at all
        ServerEntry enabledEntry = new ServerEntry("lobby1",
                new InetSocketAddress("127.0.0.1", 30101), null, "bedrock", false);
        java.util.Map<String, Object> enabledSerialized = (java.util.Map<String, Object>) converter.toConfig(
                ServerEntry.class, enabledEntry, null);
        assertFalse(enabledSerialized.containsKey("disabled"),
                "enabled entries must not add the flag, keeping old config files unchanged");
    }
}
