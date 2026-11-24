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

package dev.waterbucket.waterbucketpe.plugin;

import dev.waterbucket.waterbucketpe.ProxyServer;
import dev.waterbucket.waterbucketpe.command.Command;
import dev.waterbucket.waterbucketpe.command.CommandMap;
import dev.waterbucket.waterbucketpe.event.EventManager;
import dev.waterbucket.waterbucketpe.event.defaults.PlayerChatEvent;
import dev.waterbucket.waterbucketpe.logger.MainLogger;
import dev.waterbucket.waterbucketpe.player.ProxiedPlayer;
import dev.waterbucket.waterbucketpe.scheduler.WaterbucketScheduler;
import dev.waterbucket.waterbucketpe.utils.config.proxy.ProxyConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Phase 3 Stage D: the shipped example plugins are the compatibility targets. The Java
 * example is compiled from its real source against the production classes and packaged as a
 * jar; the Python example folder is copied verbatim. Both are then loaded by the real
 * PluginManager, enabled together, and exercised through the real event/command paths.
 */
class PluginCompatibilityTest {

    @TempDir
    Path tempDir;

    private ProxyServer proxy;
    private EventManager eventManager;
    private final List<Command> registeredCommands = new CopyOnWriteArrayList<>();

    private PluginManager manager;
    private Path pluginsDir;

    @BeforeEach
    void setUp() throws Exception {
        this.proxy = mock(ProxyServer.class);
        ProxyConfig config = mock(ProxyConfig.class);
        WaterbucketScheduler scheduler = mock(WaterbucketScheduler.class);
        CommandMap commandMap = mock(CommandMap.class);

        when(this.proxy.getConfiguration()).thenReturn(config);
        when(config.getIdleThreads()).thenReturn(1);
        this.pluginsDir = this.tempDir.resolve("plugins");
        Files.createDirectories(pluginsDir);
        when(this.proxy.getPluginPath()).thenReturn(pluginsDir);
        when(this.proxy.getLogger()).thenReturn(mock(MainLogger.class));
        when(this.proxy.getScheduler()).thenReturn(scheduler);
        // The example uses the Velocity-style TaskBuilder chain; stub the fluent builder to
        // return itself so the enable path exercises the same call sequence as production.
        dev.waterbucket.waterbucketpe.scheduler.TaskBuilder taskBuilder =
                mock(dev.waterbucket.waterbucketpe.scheduler.TaskBuilder.class);
        when(scheduler.buildTask(any(), any(Runnable.class))).thenReturn(taskBuilder);
        when(taskBuilder.delay(anyLong(), any())).thenReturn(taskBuilder);
        when(taskBuilder.repeat(anyLong(), any())).thenReturn(taskBuilder);
        when(taskBuilder.schedule()).thenReturn(mock(dev.waterbucket.waterbucketpe.scheduler.ScheduledTask.class));
        when(this.proxy.getCommandMap()).thenReturn(commandMap);
        when(commandMap.registerCommand(any(Command.class))).thenAnswer(invocation -> {
            this.registeredCommands.add(invocation.getArgument(0));
            return true;
        });
        when(commandMap.unregisterCommand(anyString())).thenAnswer(invocation -> {
            this.registeredCommands.removeIf(c -> c.getName().equalsIgnoreCase(invocation.getArgument(0, String.class)));
            return true;
        });

        this.eventManager = new EventManager(this.proxy);
        when(this.proxy.getEventManager()).thenReturn(this.eventManager);
    }

    /** Compiles the shipped Java example against the production classpath and jars it. */
    private void buildJavaExampleJar() throws Exception {
        Path exampleSource = Path.of("examples/plugins/java-example/src/main/java/example/waterbucket/ExampleJavaPlugin.java");
        assertTrue(Files.isRegularFile(exampleSource), "the java example source must be present");

        Path outDir = this.tempDir.resolve("java-example-out");
        Files.createDirectories(outDir);
        javax.tools.JavaCompiler compiler = javax.tools.ToolProvider.getSystemJavaCompiler();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int result = compiler.run(null, null, err, "-classpath", System.getProperty("java.class.path"),
                "-d", outDir.toString(), exampleSource.toString());
        assertEquals(0, result, "the java example must compile against the production API: " + err);

        Path jar = pluginsDir.resolve("ExampleJava.jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            out.putNextEntry(new JarEntry("waterbucket.yml"));
            out.write(Files.readAllBytes(Path.of("examples/plugins/java-example/src/main/resources/waterbucket.yml")));
            out.closeEntry();
            try (var walk = Files.walk(outDir)) {
                for (Path classFile : walk.filter(p -> p.toString().endsWith(".class")).toList()) {
                    out.putNextEntry(new JarEntry(outDir.relativize(classFile).toString().replace('\\', '/')));
                    out.write(Files.readAllBytes(classFile));
                    out.closeEntry();
                }
            }
        }
    }

    /** Copies the shipped Python example folder into the plugin path verbatim. */
    private void copyPythonExample() throws Exception {
        Path sourceDir = Path.of("examples/plugins/python-example");
        Path targetDir = pluginsDir.resolve("python-example");
        Files.createDirectories(targetDir);
        try (var walk = Files.walk(sourceDir, 1)) {
            for (Path file : walk.filter(Files::isRegularFile).toList()) {
                Files.copy(file, targetDir.resolve(file.getFileName().toString()), StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    @Test
    void javaAndPythonExamplePluginsRunTogether() throws Exception {
        this.buildJavaExampleJar();
        this.copyPythonExample();

        PluginManager localManager = new PluginManager(this.proxy);
        localManager.loadAllPlugins();
        localManager.enableAllPlugins();

        assertNotNull(localManager.getPluginByName("ExampleJava"), "java example must load");
        assertNotNull(localManager.getPluginByName("ExamplePython"), "python example must load");
        assertTrue(localManager.getPluginByName("ExampleJava").isEnabled());
        assertTrue(localManager.getPluginByName("ExamplePython").isEnabled());

        // All runtimes subscribed to the same event through the real pipeline: the java
        // example holds two subscriptions (functional chat observer + @Subscribe listener),
        // the python example one.
        assertEquals(3, this.eventManager.subscriberCount(PlayerChatEvent.class),
                "java example: functional + @Subscribe, python example: one subscription");

        // Both commands were registered under their documented names
        assertEquals(2, this.registeredCommands.size(), "pyhello and javahello must be registered");
        assertTrue(this.registeredCommands.stream().anyMatch(c -> c.getName().equalsIgnoreCase("pyhello")));
        assertTrue(this.registeredCommands.stream().anyMatch(c -> c.getName().equalsIgnoreCase("javahello")));

        // Dispatch through the real pipeline: both handlers run without breaking the pipeline
        ProxiedPlayer player = mock(ProxiedPlayer.class);
        when(player.getName()).thenReturn("Steve");
        PlayerChatEvent chat = new PlayerChatEvent(player, "hello world");
        this.eventManager.callEvent(chat);
        assertFalse(chat.isCancelled(), "neither example cancels chat");
        assertEquals("hello world", chat.getMessage(), "message payload must pass through unchanged");

        // The python command exercises the full interop path (sibling import + callable)
        Command pyHello = this.registeredCommands.stream()
                .filter(c -> c.getName().equalsIgnoreCase("pyhello")).findFirst().orElseThrow();
        assertTrue(pyHello.onExecute(mock(dev.waterbucket.waterbucketpe.command.CommandSender.class),
                "pyhello", new String[0]), "the python command must execute through the interpreter");

        // The java command executes through the compiled plugin class
        Command javaHello = this.registeredCommands.stream()
                .filter(c -> c.getName().equalsIgnoreCase("javahello")).findFirst().orElseThrow();
        assertTrue(javaHello.onExecute(mock(dev.waterbucket.waterbucketpe.command.CommandSender.class),
                "javahello", new String[0]), "the java command must execute");

        // Disable everything: python resources must detach, java resources stay as documented
        localManager.disableAllPlugins();

        assertEquals(1, this.eventManager.subscriberCount(PlayerChatEvent.class),
                "the python plugin must detach its subscription on disable; the java plugin keeps its own by contract");
        assertTrue(this.registeredCommands.stream().noneMatch(c -> c.getName().equalsIgnoreCase("pyhello")),
                "the python command must be unregistered on disable");
        assertTrue(this.registeredCommands.stream().anyMatch(c -> c.getName().equalsIgnoreCase("javahello")),
                "the java plugin keeps its command (documented: java plugins clean up themselves)");
    }
}
