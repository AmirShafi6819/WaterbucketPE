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

import dev.waterbucket.waterbucketpe.ProxyServer;
import dev.waterbucket.waterbucketpe.command.Command;
import dev.waterbucket.waterbucketpe.command.CommandMap;
import dev.waterbucket.waterbucketpe.event.EventManager;
import dev.waterbucket.waterbucketpe.player.ProxiedPlayer;
import dev.waterbucket.waterbucketpe.plugin.PluginYAML;
import dev.waterbucket.waterbucketpe.plugin.compat.CompatApi;
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
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The compatibility plugin surface from inside a real embedded Python interpreter: the
 * {@code pmmp} global, subscribing the compatibility chat event and driving the compatibility
 * {@code Player} wrapper - with the message edit flowing back to the proxy pipeline.
 */
class CompatPythonTest {

    @TempDir
    Path tempDir;

    private ProxyServer proxy;
    private EventManager eventManager;
    private ProxiedPlayer rawPlayer;
    private PythonPlugin plugin;

    @BeforeEach
    void setUp() throws Exception {
        this.proxy = mock(ProxyServer.class);
        ProxyConfig config = mock(ProxyConfig.class);
        CommandMap commandMap = mock(CommandMap.class);
        WaterbucketScheduler scheduler = mock(WaterbucketScheduler.class);

        when(this.proxy.getConfiguration()).thenReturn(config);
        when(config.getIdleThreads()).thenReturn(1);
        when(this.proxy.getPluginPath()).thenReturn(this.tempDir.resolve("plugins"));
        when(this.proxy.getScheduler()).thenReturn(scheduler);
        when(this.proxy.getCommandMap()).thenReturn(commandMap);
        when(this.proxy.getDataPath()).thenReturn(this.tempDir);

        this.eventManager = new EventManager(this.proxy);
        when(this.proxy.getEventManager()).thenReturn(this.eventManager);

        // the compatibility server must exist before the plugin loads
        Server.init(this.proxy);

        this.rawPlayer = mock(ProxiedPlayer.class);
        when(this.rawPlayer.getName()).thenReturn("PyPlayer");
        when(this.rawPlayer.getUniqueId()).thenReturn(UUID.randomUUID());
        when(this.rawPlayer.isConnected()).thenReturn(true);
        when(this.rawPlayer.getServerInfo()).thenReturn(null);
        doAnswer(invocation -> null).when(this.rawPlayer).sendMessage(anyString());

        Path pluginDir = this.tempDir.resolve("plugins").resolve("CompatPy");
        Files.createDirectories(pluginDir);
        Files.walk(Path.of("src/test/resources/python-plugin-compat"), 1)
                .forEach(source -> {
                    if (Files.isRegularFile(source)) {
                        try {
                            Files.copy(source, pluginDir.resolve(source.getFileName().toString()));
                        } catch (Exception ignored) {
                        }
                    }
                });

        PluginYAML descriptor = new PluginYAML();
        descriptor.name = "CompatPy";
        descriptor.version = "1.0.0";
        descriptor.author = "Test";
        descriptor.main = "main.py";

        this.plugin = new PythonPlugin(this.proxy);
        this.plugin.initPython(descriptor, this.proxy, pluginDir.toFile());
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

    @SuppressWarnings("unchecked")
    private Map<String, Object> state() {
        return (Map<String, Object>) this.plugin.getModule().getMember("get_state").execute().as(Map.class);
    }

    @Test
    void compatSurfaceWorksFromPython() throws Exception {
        this.plugin.onStartup();
        this.plugin.setEnabled(true);

        Map<String, Object> state = this.state();
        assertEquals(true, state.get("startup"));
        assertEquals("WaterbucketPE", state.get("server_name"));
        assertEquals(Server.API_VERSION, state.get("api_version"));

        // fire the compatibility chat event: the Python handler sees it, messages the player
        // through the compat wrapper and edits the message back
        PlayerChatEvent event = new PlayerChatEvent(new dev.waterbucket.waterbucketpe.plugin.compat.Player(this.rawPlayer), "salam world");
        this.eventManager.callEvent(event);

        assertEquals("salam world", state.get("chat_seen"));
        assertEquals(true, state.get("player_msg"));
        verifyMessageSent("seen: salam world");
        assertEquals("salam world [compat]", event.getMessage(),
                "the Python edit must land on the event the proxy pipeline reads");
    }

    private void verifyMessageSent(String expected) {
        org.mockito.Mockito.verify(this.rawPlayer).sendMessage(expected);
    }
}
