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
import dev.waterbucket.waterbucketpe.logger.MainLogger;
import dev.waterbucket.waterbucketpe.scheduler.WaterbucketScheduler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers plugin manager lifecycle decisions: dependency cycles of any length are rejected
 * without crashing boot, a failed enable never runs onDisable against half-initialized state,
 * jar resources are readable and missing entries return null, and plugin names can not escape
 * the plugin directory.
 */
class PluginLifecycleTest {

    @TempDir
    Path tempDir;

    private ProxyServer proxy;
    private PluginManager manager;

    @BeforeEach
    void setUp() throws Exception {
        this.proxy = mock(ProxyServer.class);
        when(this.proxy.getPluginPath()).thenReturn(this.tempDir);
        when(this.proxy.getLogger()).thenReturn(mock(MainLogger.class));
        // Plugin.setEnabled(false) hands its scheduled tasks to the scheduler; the mock keeps
        // the lifecycle path NPE-free (cancelTasks itself is covered by SchedulerPluginTasksTest)
        when(this.proxy.getScheduler()).thenReturn(mock(WaterbucketScheduler.class));
        // setEnabled(false) now also detaches @Subscribe listeners through the event manager
        when(this.proxy.getEventManager()).thenReturn(mock(dev.waterbucket.waterbucketpe.event.EventManager.class));
        // Scans the (empty) plugin folder; no plugins discovered
        this.manager = new PluginManager(this.proxy);
    }

    private PluginYAML descriptor(String name, List<String> depends) {
        PluginYAML yaml = new PluginYAML();
        yaml.name = name;
        yaml.version = "1.0.0";
        yaml.author = "test";
        yaml.main = "dev.waterbucket.waterbucketpe.plugin.PluginLifecycleTest$TestPlugin";
        yaml.depends = depends;
        return yaml;
    }

    private static final class TestPlugin extends Plugin {
        final AtomicInteger enableCalls = new AtomicInteger();
        final AtomicInteger disableCalls = new AtomicInteger();
        volatile boolean failOnEnable;

        @Override
        public void onEnable() {
            this.enableCalls.incrementAndGet();
            if (this.failOnEnable) {
                throw new IllegalStateException("broken plugin");
            }
        }

        @Override
        public void onDisable() {
            this.disableCalls.incrementAndGet();
        }
    }

    private void register(Plugin plugin, PluginYAML descriptor) throws Exception {
        var init = Plugin.class.getDeclaredMethod("init", PluginYAML.class, ProxyServer.class, java.io.File.class);
        init.setAccessible(true);
        init.invoke(plugin, descriptor, this.proxy, this.tempDir.toFile());

        var pluginMap = PluginManager.class.getDeclaredField("pluginMap");
        pluginMap.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, Plugin> map = (Map<String, Plugin>) pluginMap.get(this.manager);
        map.put(descriptor.getName(), plugin);
    }

    @Test
    void longDependencyCycleIsRejectedWithoutCrashingBoot() throws Exception {
        // A -> B -> C -> A: the previous 1-hop parent check recursed to StackOverflowError,
        // an Error that escaped boot and killed the whole proxy start.
        TestPlugin a = new TestPlugin();
        TestPlugin b = new TestPlugin();
        TestPlugin c = new TestPlugin();
        this.register(a, descriptor("CycleA", List.of("CycleB")));
        this.register(b, descriptor("CycleB", List.of("CycleC")));
        this.register(c, descriptor("CycleC", List.of("CycleA")));

        assertDoesNotThrow(() -> this.manager.enableAllPlugins());

        assertEquals(0, a.enableCalls.get() + b.enableCalls.get() + c.enableCalls.get(),
                "no plugin of a cyclic set may end up enabled");
        assertFalse(a.isEnabled() || b.isEnabled() || c.isEnabled());
    }

    @Test
    void failedEnableLeavesPluginDisabledAndNeverRunsOnDisable() throws Exception {
        TestPlugin broken = new TestPlugin();
        broken.failOnEnable = true;
        this.register(broken, descriptor("Broken", null));

        assertFalse(this.manager.enablePlugin(broken, null));
        assertFalse(broken.isEnabled(), "a failed enable must not leave the plugin marked enabled");
        assertEquals(1, broken.enableCalls.get(), "onEnable must have run exactly once");
        assertEquals(0, broken.disableCalls.get(),
                "onDisable must not run against a plugin whose onEnable never completed");

        // A healthy enable still works after the failure was handled
        broken.failOnEnable = false;
        assertTrue(this.manager.enablePlugin(broken, null));
        assertTrue(broken.isEnabled());
    }

    @Test
    void healthyEnableOrderIsRecordedForReverseDisable() throws Exception {
        TestPlugin a = new TestPlugin();
        TestPlugin b = new TestPlugin();
        this.register(a, descriptor("Base", null));
        this.register(b, descriptor("Ext", List.of("Base")));

        assertTrue(this.manager.enablePlugin(b, null));
        assertTrue(this.manager.enablePlugin(a, null));
        assertTrue(a.isEnabled() && b.isEnabled());
        assertEquals(0, b.disableCalls.get());

        this.manager.disableAllPlugins();
        assertEquals(1, a.disableCalls.get());
        assertEquals(1, b.disableCalls.get());
    }

    @Test
    void jarResourcesAreReadableAndMissingEntriesReturnNull() throws Exception {
        // Builds a real jar with one resource, binds a plugin to it and exercises getResourceFile
        Path jar = this.tempDir.resolve("resource-plugin.jar");
        byte[] payload = "config: true\n".getBytes(StandardCharsets.UTF_8);
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            out.putNextEntry(new JarEntry("config.yml"));
            out.write(payload);
            out.closeEntry();
        }

        TestPlugin plugin = new TestPlugin();
        PluginYAML descriptor = descriptor("ResPlugin", null);
        this.register(plugin, descriptor);
        // Rebind the plugin file to the jar we built
        var field = Plugin.class.getDeclaredField("pluginFile");
        field.setAccessible(true);
        field.set(plugin, jar.toFile());

        try (InputStream stream = plugin.getResourceFile("config.yml")) {
            assertNotNull(stream, "an existing resource must be readable");
            assertArrayEquals(payload, stream.readAllBytes());
        }

        // The missing entry used to throw a bare NullPointerException out of getInputStream(null)
        assertNull(plugin.getResourceFile("does/not/exist.yml"));
        // Repeated reads used to leak one JarFile handle per call; many reads must keep working
        for (int i = 0; i < 32; i++) {
            try (InputStream stream = plugin.getResourceFile("config.yml")) {
                assertArrayEquals(payload, stream.readAllBytes());
            }
        }
    }

    /** A TestPlugin whose onEnable blocks until released; a separate class (TestPlugin is final). */
    private static final class BlockingPlugin extends Plugin {
        final AtomicInteger enableCalls = new AtomicInteger();
        volatile CountDownLatch insideOnEnable;
        volatile CountDownLatch gate;

        @Override
        public void onEnable() {
            this.enableCalls.incrementAndGet();
            if (this.insideOnEnable != null) {
                this.insideOnEnable.countDown();
            }
            if (this.gate != null) {
                try {
                    this.gate.await(10, java.util.concurrent.TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        @Override
        public void onDisable() {
        }
    }

    @Test
    void concurrentEnableChainsDoNotRejectHealthyDependencies() throws Exception {
        // The cycle detector was a shared HashSet: while one thread was mid-enable of a
        // dependency (its name in the set), another thread enabling a plugin that depends on
        // it got a bogus "circular dependency" rejection. Enable chains must not interfere.
        TestPlugin dependent = new TestPlugin();
        this.register(dependent, descriptor("Ext", List.of("SlowBase")));

        BlockingPlugin blocking = new BlockingPlugin();
        blocking.insideOnEnable = new CountDownLatch(1);
        blocking.gate = new CountDownLatch(1);
        this.register(blocking, descriptor("SlowBase", null));

        Thread enabler = new Thread(() -> {
            try {
                this.manager.enablePlugin(blocking, null);
            } catch (Exception ignored) {
            }
        });
        enabler.start();
        assertTrue(blocking.insideOnEnable.await(5, java.util.concurrent.TimeUnit.SECONDS),
                "the first enable must be inside onEnable");

        // Second chain on its own thread: depends on the plugin that is mid-enable in the
        // first chain. Serialized on the manager, it must wait for the first chain and then
        // succeed; the old shared cycle set rejected it instead.
        java.util.concurrent.atomic.AtomicBoolean dependentResult = new java.util.concurrent.atomic.AtomicBoolean(false);
        Thread dependentEnabler = new Thread(() -> {
            try {
                dependentResult.set(this.manager.enablePlugin(dependent, null));
            } catch (Exception ignored) {
            }
        });
        dependentEnabler.start();
        blocking.gate.countDown();
        enabler.join(10_000);
        dependentEnabler.join(10_000);

        assertTrue(dependentResult.get(),
                "a healthy dependency mid-enable in another chain must not be rejected as a cycle");
        assertTrue(dependent.isEnabled(), "the dependent must end up enabled");
        assertTrue(blocking.isEnabled(), "the blocking dependency must end up enabled");
        assertEquals(1, dependent.enableCalls.get(), "the dependent must have enabled exactly once");
        assertEquals(1, blocking.enableCalls.get(), "the blocking dependency must have enabled exactly once");
    }

    @Test
    void pluginNamesCanNotEscapeThePluginDirectory() {
        assertFalse(PluginLoader.isValidPluginName("../../evil"), "traversal names must be rejected");
        assertFalse(PluginLoader.isValidPluginName("a/b"), "path separators must be rejected");
        assertFalse(PluginLoader.isValidPluginName(".hidden"), "dot-leading names must be rejected");
        assertFalse(PluginLoader.isValidPluginName(".."), "bare .. must be rejected");
        assertFalse(PluginLoader.isValidPluginName(""), "empty names must be rejected");
        assertFalse(PluginLoader.isValidPluginName(null), "missing names must be rejected");
        assertTrue(PluginLoader.isValidPluginName("HelloPy"));
        assertTrue(PluginLoader.isValidPluginName("My-Plugin_2.0"));
        assertTrue(PluginLoader.isValidPluginName("Some Plugin With Spaces"));
    }

    /** A TestPlugin recording its disable order in a shared list (TestPlugin is final). */
    private static final class OrderedPlugin extends Plugin {
        private final java.util.List<String> order;
        private final String name;

        OrderedPlugin(String name, java.util.List<String> order) {
            this.name = name;
            this.order = order;
        }

        @Override
        public void onEnable() {
        }

        @Override
        public void onDisable() {
            this.order.add(this.name);
        }
    }

    @Test
    void disablePluginShutsDependentsDownFirst() throws Exception {
        java.util.List<String> order = new java.util.ArrayList<>();
        OrderedPlugin base = new OrderedPlugin("Base", order);
        OrderedPlugin dependent = new OrderedPlugin("Dependent", order);
        this.register(base, descriptor("OrderBase", null));
        this.register(dependent, descriptor("OrderDependent", java.util.List.of("OrderBase")));

        assertTrue(this.manager.enablePlugin(dependent, null), "the dependent enables its dependency first");
        assertTrue(base.isEnabled() && dependent.isEnabled());

        this.manager.disablePlugin(base);

        assertFalse(base.isEnabled(), "disabling a dependency must disable the dependency");
        assertFalse(dependent.isEnabled(), "disabling a dependency must disable every dependent");
        assertEquals(java.util.List.of("Dependent", "Base"), order,
                "dependents must shut down before the dependency they depend on");
    }

    @Test
    void disablePluginHandlesCyclesNullAndUnknownPlugins() throws Exception {
        java.util.List<String> order = new java.util.ArrayList<>();
        OrderedPlugin a = new OrderedPlugin("LoopA", order);
        OrderedPlugin b = new OrderedPlugin("LoopB", order);
        this.register(a, descriptor("LoopA", java.util.List.of("LoopB")));
        this.register(b, descriptor("LoopB", java.util.List.of("LoopA")));
        // A depends cycle can not be enabled normally; force both enabled to reach the walk
        a.setEnabled(true);
        b.setEnabled(true);

        // Depends cycle: the walk must terminate instead of recursing forever
        assertDoesNotThrow(() -> this.manager.disablePlugin(a));
        assertFalse(a.isEnabled() || b.isEnabled(), "both cycle members must end up disabled");
        assertEquals(2, order.size(), "every plugin of the cycle must disable exactly once");

        // Null and unknown plugins are no-ops
        assertDoesNotThrow(() -> this.manager.disablePlugin(null));
        assertDoesNotThrow(() -> this.manager.disablePlugin(new OrderedPlugin("Unknown", order)));
    }

    @Test
    void disablingAPluginCancelsItsScheduledTasks() throws Exception {
        WaterbucketScheduler scheduler = mock(WaterbucketScheduler.class);
        when(this.proxy.getScheduler()).thenReturn(scheduler);

        TestPlugin plugin = new TestPlugin();
        this.register(plugin, descriptor("Tasked", null));
        assertTrue(this.manager.enablePlugin(plugin, null));
        verify(scheduler, never()).cancelTasks(any());

        this.manager.disablePlugin(plugin);
        verify(scheduler).cancelTasks(plugin);
    }
}
