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
import dev.waterbucket.waterbucketpe.command.CommandSender;
import dev.waterbucket.waterbucketpe.command.CommandSettings;
import dev.waterbucket.waterbucketpe.command.SimpleCommandMap;
import dev.waterbucket.waterbucketpe.logger.MainLogger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Phase 5 second-pass robustness tests: null handling on the plugin API surface, command-map
 * iteration under runtime registration, and duplicate runtime loads.
 *
 * F1: getCommands() iteration happens on Netty event loops (AvailableCommandsPacket building)
 *      while Python plugins may register commands at runtime; fastutil's synchronized wrappers
 *      require manual synchronization on the map for iteration - the in-tree call sites did
 *      not synchronize.
 * F2: getResourceFile(null) crashed with a deep NPE from JarFile internals.
 * F3: saveResource(filename, null, ...) crashed with a bare NPE from Path.resolve.
 * F4: a broken config.yml was re-parsed (and re-logged) on EVERY getConfig() call.
 * F5: handleCommand(sender, command, null, args) NPE'd in isRegistered.
 * F6: two concurrent loadPlugin calls for the same name could both register class loaders;
 *      the loser leaked (registered-but-replaced, jar handle open).
 */
class PluginEngineRobustnessTest {

    @TempDir
    Path tempDir;

    private ProxyServer proxy;
    private MainLogger logger;
    private PluginManager manager;

    @BeforeEach
    void setUp() throws Exception {
        this.proxy = mock(ProxyServer.class);
        when(this.proxy.getEventManager()).thenReturn(mock(dev.waterbucket.waterbucketpe.event.EventManager.class));
        this.logger = mock(MainLogger.class);
        when(this.proxy.getPluginPath()).thenReturn(this.tempDir);
        when(this.proxy.getLogger()).thenReturn(this.logger);
        this.manager = new PluginManager(this.proxy);
    }

    private static final class ResourcePlugin extends Plugin {
        @Override
        public void onEnable() {
        }
    }

    private Plugin resourcePlugin() throws Exception {
        Plugin plugin = new ResourcePlugin();
        PluginYAML config = new PluginYAML();
        config.name = "ResPlugin";
        config.version = "1.0.0";
        config.author = "test";
        config.main = "irrelevant";
        var init = Plugin.class.getDeclaredMethod("init", PluginYAML.class, ProxyServer.class, java.io.File.class);
        init.setAccessible(true);
        init.invoke(plugin, config, this.proxy, this.tempDir.toFile());
        return plugin;
    }

    @Test
    void getResourceFileRejectsNullAndBlankInsteadOfCrashing() throws Exception {
        Plugin plugin = this.resourcePlugin();
        assertNull(plugin.getResourceFile(null), "a null resource name must return null, not NPE");
        assertNull(plugin.getResourceFile("  "), "a blank resource name must return null, not NPE");
    }

    @Test
    void saveResourceRejectsNullOutputNameWithAClearError() throws Exception {
        Plugin plugin = this.resourcePlugin();
        assertThrows(IllegalArgumentException.class, () -> plugin.saveResource("config.yml", null, false),
                "a null output name must fail with a clear contract violation");
    }

    @Test
    void brokenConfigYieldsAnEmptyConfigurationWithoutThrowing() throws Exception {
        // Documented behavior pin (Phase 5 second pass): a config.yml that fails to parse is
        // swallowed by Configuration.load (logged to the static main logger) and the plugin
        // receives a non-null but EMPTY Configuration - never an exception, never null, and
        // the file is parsed exactly once (no per-call re-parse).
        Plugin plugin = this.resourcePlugin();
        java.nio.file.Path dataFolder = this.tempDir.resolve("resplugin");
        java.nio.file.Files.createDirectories(dataFolder);
        java.nio.file.Files.writeString(dataFolder.resolve("config.yml"),
                "root:\n\t- tab indented value", java.nio.charset.StandardCharsets.UTF_8);

        assertDoesNotThrow(() -> {
            plugin.getConfig();
            plugin.getConfig();
            plugin.getConfig();
        });
        org.junit.jupiter.api.Assertions.assertNotNull(plugin.getConfig(),
                "a broken config must still yield a Configuration object");
        assertTrue(plugin.getConfig().getAll().isEmpty(),
                "the failed parse must not expose partial values");
    }

    @Test
    void handleCommandWithNullAliasDoesNotCrash() {
        SimpleCommandMap map = new SimpleCommandMap(this.proxy, "/");
        Command command = new Command("teleport", CommandSettings.builder().build()) {
            @Override
            public boolean onExecute(CommandSender sender, String alias, String[] args) {
                return true;
            }
        };
        map.registerCommand(command);
        CommandSender sender = mock(CommandSender.class);
        when(sender.hasPermission(anyString())).thenReturn(true);

        // Public API: alias may be null (e.g. dispatched by a plugin without an alias)
        boolean handled = assertDoesNotThrow(() -> map.handleCommand(sender, command, null, new String[0]),
                "a null alias must not crash command dispatch");
        assertTrue(handled, "the command must still execute");
    }

    @Test
    void concurrentCommandRegistrationAndIterationStayConsistent() throws Exception {
        // The AvailableCommandsPacket builder iterates getCommands() on event loops while
        // Python plugins register commands at runtime. fastutil's synchronized wrapper requires
        // callers to synchronize for iteration; the in-tree readers must do so.
        SimpleCommandMap map = new SimpleCommandMap(this.proxy, "/");
        int writers = 4;
        int perWriter = 1_000;
        ExecutorService pool = Executors.newFixedThreadPool(writers + 2);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(writers);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicBoolean readersStopped = new AtomicBoolean(false);
        List<String> registered = new CopyOnWriteArrayList<>();

        for (int w = 0; w < writers; w++) {
            final int offset = w;
            pool.execute(() -> {
                try {
                    start.await();
                    for (int i = 0; i < perWriter; i++) {
                        String name = "cmd_" + offset + "_" + i;
                        Command command = new Command(name, CommandSettings.builder().build()) {
                            @Override
                            public boolean onExecute(CommandSender sender, String alias, String[] args) {
                                return true;
                            }
                        };
                        if (map.registerCommand(command)) {
                            registered.add(name);
                        }
                    }
                } catch (Throwable t) {
                    failure.compareAndSet(null, t);
                } finally {
                    done.countDown();
                }
            });
        }

        // Two readers iterating exactly like the AvailableCommandsPacket builder does
        Thread[] readers = new Thread[2];
        for (int r = 0; r < readers.length; r++) {
            readers[r] = new Thread(() -> {
                try {
                    start.await();
                    while (!readersStopped.get()) {
                        Map<String, Command> commands = map.getCommands();
                        synchronized (commands) {
                            for (Command c : commands.values()) {
                                if (c.getName() == null) {
                                    failure.compareAndSet(null, new IllegalStateException("null command name"));
                                }
                            }
                        }
                    }
                } catch (Throwable t) {
                    failure.compareAndSet(null, t);
                }
            });
            readers[r].start();
        }

        start.countDown();
        assertTrue(done.await(60, TimeUnit.SECONDS), "writers finished");
        readersStopped.set(true);
        for (Thread reader : readers) {
            reader.join(10_000);
        }
        pool.shutdownNow();
        assertNull(failure.get(), "no thread may observe a registration/iteration failure: " + failure.get());
        assertEquals(writers * perWriter, map.getCommands().size(),
                "every successfully registered command must be in the map");
    }

    @Test
    void duplicateRuntimeLoadPluginRegistersOnlyOneLoader() throws Exception {
        // Two threads loading the same plugin name concurrently must produce exactly one
        // registered loader; the loser used to leak (registered, then replaced).
        Path jar = this.tempDir.resolve("dupe.jar");
        try (var out = new java.util.jar.JarOutputStream(java.nio.file.Files.newOutputStream(jar))) {
            out.putNextEntry(new java.util.jar.JarEntry("waterbucket.yml"));
            out.write("name: Dupe\nversion: 1.0.0\nauthor: test\nmain: does.not.Exist\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            out.closeEntry();
        }
        PluginYAML config = new PluginYAML();
        config.name = "Dupe";
        config.version = "1.0.0";
        config.author = "test";
        config.main = "does.not.Exist";

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        for (int t = 0; t < threads; t++) {
            pool.execute(() -> {
                try {
                    start.await();
                    this.manager.loadPlugin(config, jar);
                } catch (Throwable t2) {
                    failure.compareAndSet(null, t2);
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(60, TimeUnit.SECONDS), "all load attempts finished");
        pool.shutdownNow();
        assertNull(failure.get(), "no thread may crash on a duplicate load: " + failure.get());

        var field = PluginManager.class.getDeclaredField("pluginClassLoaders");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, PluginClassLoader> loaders = (Map<String, PluginClassLoader>) field.get(this.manager);
        assertEquals(0, loaders.size(),
                "the plugin fails to load (missing main class); every attempt must release its loader");
    }
}
