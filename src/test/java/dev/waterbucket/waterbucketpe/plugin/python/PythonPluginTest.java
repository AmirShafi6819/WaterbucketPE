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
import dev.waterbucket.waterbucketpe.command.CommandSender;
import dev.waterbucket.waterbucketpe.event.Event;
import dev.waterbucket.waterbucketpe.event.EventManager;
import dev.waterbucket.waterbucketpe.plugin.PluginYAML;
import dev.waterbucket.waterbucketpe.scheduler.WaterbucketScheduler;
import dev.waterbucket.waterbucketpe.utils.config.proxy.ProxyConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentMatchers;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * End-to-end test of the embedded Python plugin runtime: descriptor loading, lifecycle hooks,
 * event subscription from Python, command registration, sibling-module imports and
 * multi-threaded context access.
 */
class PythonPluginTest {

    public static class TestSyncEvent extends Event {
        private volatile boolean wbFlag;
        private volatile boolean healthyFlag;

        public boolean isWbFlag() {
            return this.wbFlag;
        }

        public void setWbFlag(boolean flag) {
            this.wbFlag = flag;
        }

        public boolean isHealthyFlag() {
            return this.healthyFlag;
        }

        public void setHealthyFlag(boolean flag) {
            this.healthyFlag = flag;
        }
    }

    /** Mirrors the login/transfer events: completable, so a broken handler used to kick players. */
    @dev.waterbucket.waterbucketpe.event.CompletableEvent
    public static class TestCompletableEvent extends Event {
        private volatile boolean touched;

        public boolean isTouched() {
            return this.touched;
        }

        public void setTouched(boolean touched) {
            this.touched = touched;
        }
    }

    @TempDir
    Path tempDir;

    private ProxyServer proxy;
    private EventManager eventManager;
    private final List<Command> registeredCommands = new CopyOnWriteArrayList<>();

    private PythonPlugin plugin;

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
        when(commandMap.registerCommand(any(Command.class))).thenAnswer(invocation -> {
            this.registeredCommands.add(invocation.getArgument(0));
            return true;
        });
        when(commandMap.unregisterCommand(anyString())).thenAnswer(invocation -> {
            this.registeredCommands.removeIf(command -> command.getName().equalsIgnoreCase(invocation.getArgument(0, String.class)));
            return true;
        });

        // A real EventManager so subscribed Python handlers actually run
        this.eventManager = new EventManager(this.proxy);
        when(this.proxy.getEventManager()).thenReturn(this.eventManager);

        Path pluginDir = this.tempDir.resolve("plugins").resolve("HelloPy");
        Files.createDirectories(pluginDir);
        Files.walk(Path.of("src/test/resources/python-plugin"), 1)
                .forEach(source -> {
                    if (Files.isRegularFile(source)) {
                        try {
                            Files.copy(source, pluginDir.resolve(source.getFileName().toString()));
                        } catch (Exception ignored) {
                        }
                    }
                });

        PluginYAML descriptor = new PluginYAML();
        descriptor.name = "HelloPy";
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
    void lifecycleHooksAndSiblingImportsWork() throws Exception {
        this.plugin.onStartup();
        this.plugin.setEnabled(true);

        Map<String, Object> state = this.state();
        assertEquals(true, state.get("startup"), "on_startup must run");
        assertEquals(true, state.get("enabled"), "on_enable must run");
        assertEquals(true, state.get("helper"), "sibling module import must work");

        this.plugin.setEnabled(false);
        // The module is closed on disable; read the flag from the map captured before shutdown
        assertTrue((Boolean) state.get("disabled"), "on_disable must run");
    }

    @Test
    void pythonEventSubscriptionsReceiveEvents() throws Exception {
        this.plugin.setEnabled(true);

        TestSyncEvent event = new TestSyncEvent();
        this.eventManager.callEvent(event);

        assertEquals(1, ((Number) this.state().get("events")).intValue(), "python handler must see the event");
        assertTrue(event.isWbFlag(), "python handler must be able to mutate the event synchronously");
    }

    @Test
    void pythonCommandsRegisterAndExecute() throws Exception {
        this.plugin.setEnabled(true);

        assertEquals(1, this.registeredCommands.size(), "command must be registered on the proxy");
        Command command = this.registeredCommands.get(0);
        assertEquals("pyhello", command.getName());

        CommandSender sender = mock(CommandSender.class);
        assertTrue(command.onExecute(sender, "pyhello", new String[]{"one", "two"}));

        assertEquals("pyhello|one,two", this.state().get("command"), "python executor must receive sender/alias/args");
    }

    @Test
    void brokenCompletableHandlerDoesNotFailTheEventPipeline() throws Exception {
        // A throwing Python handler used to fail the event's completable future, which the
        // login/transfer paths turn into a player disconnect. Java handler errors are logged
        // and swallowed, and the Python bridge must behave the same way.
        this.plugin.setEnabled(true);
        this.plugin.getModule().getMember("install_broken_completable_handler").execute();
        this.plugin.getModule().getMember("install_healthy_completable_handler").execute();

        TestCompletableEvent event = new TestCompletableEvent();
        CompletableFuture<?> future = this.eventManager.callEvent(event);
        future.get(5, TimeUnit.SECONDS); // must complete normally, not exceptionally

        assertTrue(event.isTouched(), "the healthy handler must still run after the broken one");
    }

    @Test
    void disableDetachesEventSubscriptionsAndCommands() throws Exception {
        this.plugin.setEnabled(true);
        assertEquals(1, this.eventManager.subscriberCount(TestSyncEvent.class),
                "the enabled plugin holds one subscription");
        assertEquals(1, this.registeredCommands.size(), "the enabled plugin holds one command");

        this.plugin.setEnabled(false);

        assertEquals(0, this.eventManager.subscriberCount(TestSyncEvent.class),
                "disable must detach every event subscription before the context closes");
        assertEquals(0, this.registeredCommands.size(),
                "disable must unregister every command the plugin registered");

        // Events fired after disable must not reach into the closed context
        assertDoesNotThrow(() -> this.eventManager.callEvent(new TestSyncEvent()));
        assertDoesNotThrow(() -> this.eventManager.callEvent(new TestCompletableEvent()));
    }

    @Test
    void brokenPythonHandlersDoNotBreakEventDispatch() throws Exception {
        this.plugin.setEnabled(true);
        this.plugin.getModule().getMember("install_broken_handler").execute();

        TestSyncEvent event = new TestSyncEvent();
        this.eventManager.callEvent(event); // must not throw

        // a healthy handler registered after the broken one still runs
        this.plugin.getModule().getMember("install_healthy_handler").execute();
        TestSyncEvent second = new TestSyncEvent();
        this.eventManager.callEvent(second);
        assertTrue(second.isHealthyFlag());
    }

    @Test
    void contextIsThreadSafeForConcurrentHandlers() throws Exception {
        this.plugin.setEnabled(true);
        this.plugin.getModule().getMember("install_counting_handler").execute();
        this.plugin.getModule().getMember("reset_events").execute();

        int threads = 4;
        int callsPerThread = 25;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch done = new CountDownLatch(threads * callsPerThread);
        AtomicReference<Throwable> error = new AtomicReference<>();
        try {
            for (int i = 0; i < threads * callsPerThread; i++) {
                pool.execute(() -> {
                    try {
                        this.eventManager.callEvent(new TestSyncEvent());
                    } catch (Throwable t) {
                        error.compareAndSet(null, t);
                    } finally {
                        done.countDown();
                    }
                });
            }
            assertTrue(done.await(60, TimeUnit.SECONDS), "all event calls finished");
            assertTrue(error.get() == null, "no thread may observe a polyglot error: " + error.get());
            assertEquals(threads * callsPerThread,
                    this.plugin.getModule().getMember("get_counted").execute().asInt(),
                    "every concurrent event must reach the python handler exactly once");
        } finally {
            pool.shutdownNow();
        }
    }
}
