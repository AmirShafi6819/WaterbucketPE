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

package dev.waterbucket.waterbucketpe.plugin.php;

import dev.waterbucket.waterbucketpe.ProxyServer;
import dev.waterbucket.waterbucketpe.command.Command;
import dev.waterbucket.waterbucketpe.command.CommandMap;
import dev.waterbucket.waterbucketpe.event.EventManager;
import dev.waterbucket.waterbucketpe.player.ProxiedPlayer;
import dev.waterbucket.waterbucketpe.plugin.PluginYAML;
import dev.waterbucket.waterbucketpe.plugin.compat.Server;
import dev.waterbucket.waterbucketpe.plugin.compat.event.PlayerChatEvent;
import dev.waterbucket.waterbucketpe.scheduler.WaterbucketScheduler;
import dev.waterbucket.waterbucketpe.utils.config.proxy.ProxyConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The compatibility plugin surface from inside the embedded PHP interpreter (Quercus): the
 * {@code $pmmp} global, subscribing the compatibility chat event through its class object and
 * driving the compatibility {@code Player} wrapper from PHP - with the message edit flowing
 * back to the proxy pipeline.
 */
class CompatPhpTest {

    @TempDir
    Path tempDir;

    private ProxyServer proxy;
    private EventManager eventManager;
    private ProxiedPlayer rawPlayer;
    private final List<Command> registeredCommands = new CopyOnWriteArrayList<>();

    private PhpPlugin plugin;

    @BeforeEach
    void setUp() throws Exception {
        this.proxy = mock(ProxyServer.class);
        ProxyConfig config = mock(ProxyConfig.class);
        WaterbucketScheduler scheduler = mock(WaterbucketScheduler.class);
        CommandMap commandMap = mock(CommandMap.class);

        when(this.proxy.getConfiguration()).thenReturn(config);
        when(config.getIdleThreads()).thenReturn(1);
        when(this.proxy.getPluginPath()).thenReturn(this.tempDir);
        when(this.proxy.getScheduler()).thenReturn(scheduler);
        when(this.proxy.getCommandMap()).thenReturn(commandMap);
        when(this.proxy.getDataPath()).thenReturn(this.tempDir);
        when(commandMap.registerCommand(any(Command.class))).thenAnswer(invocation -> {
            this.registeredCommands.add(invocation.getArgument(0));
            return true;
        });

        this.eventManager = new EventManager(this.proxy);
        when(this.proxy.getEventManager()).thenReturn(this.eventManager);

        // the compatibility server must exist before the plugin loads
        Server.init(this.proxy);

        this.rawPlayer = mock(ProxiedPlayer.class);
        when(this.rawPlayer.getName()).thenReturn("PhpPlayer");
        when(this.rawPlayer.getUniqueId()).thenReturn(UUID.randomUUID());
        when(this.rawPlayer.isConnected()).thenReturn(true);
        when(this.rawPlayer.getServerInfo()).thenReturn(null);
        doAnswer(invocation -> null).when(this.rawPlayer).sendMessage(anyString());

        Path pluginDir = this.tempDir.resolve("plugins").resolve("CompatPhp");
        Files.createDirectories(pluginDir);
        Files.walk(Path.of("src/test/resources/php-plugin-compat"), 1)
                .forEach(source -> {
                    if (Files.isRegularFile(source)) {
                        try {
                            Files.copy(source, pluginDir.resolve(source.getFileName().toString()));
                        } catch (Exception ignored) {
                        }
                    }
                });

        PluginYAML descriptor = new PluginYAML();
        descriptor.name = "CompatPhp";
        descriptor.version = "1.0.0";
        descriptor.author = "Test";
        descriptor.main = "main.php";

        this.plugin = new PhpPlugin(this.proxy);
        this.plugin.initPhp(descriptor, this.proxy, pluginDir.toFile());
    }

    @AfterEach
    void tearDown() {
        if (this.plugin != null && this.plugin.isEnabled()) {
            try {
                this.plugin.setEnabled(false);
            } catch (Exception ignored) {
            }
        }
    }

    private String state() throws Throwable {
        return this.plugin.eval("return json_encode(wb_pmmp_state());").toJavaString();
    }

    @Test
    void compatSurfaceWorksFromPhp() throws Throwable {
        this.plugin.onStartup();
        this.plugin.setEnabled(true);

        String state = this.state();
        assertTrue(state.contains("\"startup\":true"), state);
        assertTrue(state.contains("\"server_name\":\"WaterbucketPE\""), state);
        assertTrue(state.contains("\"api_version\":\"" + Server.API_VERSION + "\""), state);

        // fire the compatibility chat event: the PHP handler sees it, messages the player
        // through the compat wrapper and edits the message back
        PlayerChatEvent event = new PlayerChatEvent(
                new dev.waterbucket.waterbucketpe.plugin.compat.Player(this.rawPlayer), "salam world");
        this.eventManager.callEvent(event);

        state = this.state();
        assertTrue(state.contains("\"chat_seen\":\"salam world\""), state);
        assertTrue(state.contains("\"player_msg\":true"), state);
        org.mockito.Mockito.verify(this.rawPlayer).sendMessage("seen: salam world");
        assertEquals("salam world [compat]", event.getMessage(),
                "the PHP edit must land on the event the proxy pipeline reads");
    }
}
