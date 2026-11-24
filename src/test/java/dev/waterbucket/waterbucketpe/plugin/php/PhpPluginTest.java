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

package dev.waterbucket.waterbucketpe.plugin.php;

import com.caucho.quercus.env.Env;
import com.caucho.quercus.env.Value;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * End-to-end test of the embedded PHP plugin runtime (Quercus): descriptor loading, lifecycle
 * hooks, event subscription from PHP, command registration, multi-threaded dispatch and the
 * disable contract.
 */
class PhpPluginTest {

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
        when(commandMap.registerCommand(any(Command.class))).thenAnswer(invocation -> {
            this.registeredCommands.add(invocation.getArgument(0));
            return true;
        });
        when(commandMap.unregisterCommand(anyString())).thenAnswer(invocation -> {
            this.registeredCommands.removeIf(command -> command.getName().equalsIgnoreCase(invocation.getArgument(0, String.class)));
            return true;
        });

        // A real EventManager so subscribed PHP handlers actually run
        this.eventManager = new EventManager(this.proxy);
        when(this.proxy.getEventManager()).thenReturn(this.eventManager);

        // The event resolver falls back to the plugin class loader scan; stub it so the test
        // event class is found by simple name without a real manager.
        dev.waterbucket.waterbucketpe.plugin.PluginManager pluginManager =
                mock(dev.waterbucket.waterbucketpe.plugin.PluginManager.class);
        when(this.proxy.getPluginManager()).thenReturn(pluginManager);
        when(pluginManager.getClassFromCache(anyString())).thenAnswer(invocation -> {
            String name = invocation.getArgument(0, String.class);
            if (TestSyncEvent.class.getSimpleName().equals(name)) {
                return TestSyncEvent.class;
            }
            if (TestCompletableEvent.class.getSimpleName().equals(name)) {
                return TestCompletableEvent.class;
            }
            return null;
        });

        Path pluginDir = this.tempDir.resolve("plugins").resolve("HelloPhp");
        Files.createDirectories(pluginDir);
        Files.walk(Path.of("src/test/resources/php-plugin"), 1)
                .forEach(source -> {
                    if (Files.isRegularFile(source)) {
                        try {
                            Files.copy(source, pluginDir.resolve(source.getFileName().toString()));
                        } catch (Exception ignored) {
                        }
                    }
                });

        PluginYAML descriptor = new PluginYAML();
        descriptor.name = "HelloPhp";
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

    @SuppressWarnings("unchecked")
    private Map<String, Object> state() {
        try {
            Env env = this.plugin.environment();
            // Read the state as JSON: value conversions (including strings) must run inside
            // the engine execution region, and json_encode keeps the conversion trivial.
            String json = PhpEngineAccess.execute(env, () -> {
                try {
                    return this.plugin.eval("return json_encode(wb_test_state());").toJavaString();
                } catch (Throwable e) {
                    throw new RuntimeException(e);
                }
            });
            return (Map<String, Object>) new com.google.gson.Gson().fromJson(json, Map.class);
        } catch (java.lang.reflect.InvocationTargetException e) {
            throw new RuntimeException(e.getCause());
        } catch (Throwable e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void lifecycleHooksRun() throws Throwable {
        this.plugin.onStartup();
        this.plugin.setEnabled(true);

        Map<String, Object> state = this.state();
        assertEquals(true, state.get("startup"), "on_startup must run");
        assertEquals(true, state.get("enabled"), "on_enable must run");

        this.plugin.setEnabled(false);
        // The engine is closed after disable; the hook trace is the durable record
        assertEquals("on_disable", this.plugin.getLastCompletedHook(), "on_disable must run");
    }

    @Test
    void phpEventSubscriptionsReceiveEvents() throws Throwable {
        this.plugin.setEnabled(true);

        TestSyncEvent event = new TestSyncEvent();
        this.eventManager.callEvent(event);

        assertEquals(1, ((Number) this.state().get("events")).intValue(), "php handler must see the event");
        assertTrue(event.isWbFlag(), "php handler must be able to mutate the event synchronously");
    }

    @Test
    void phpCommandsRegisterAndExecute() throws Throwable {
        this.plugin.setEnabled(true);

        assertEquals(1, this.registeredCommands.size(), "command must be registered on the proxy");
        Command command = this.registeredCommands.get(0);
        assertEquals("phphello", command.getName());
        assertFalse(command.getSettings().getAliases().isEmpty(), "aliases must survive the marshaling");

        CommandSender sender = mock(CommandSender.class);
        assertTrue(command.onExecute(sender, "phphello", new String[]{"one", "two"}));

        assertEquals("phphello|one,two", this.state().get("command"), "php executor must receive sender/alias/args");
    }

    @Test
    void scheduledTasksRunOnTheProxyScheduler() throws Throwable {
        WaterbucketScheduler scheduler = mock(WaterbucketScheduler.class);
        when(this.proxy.getScheduler()).thenReturn(scheduler);
        when(scheduler.scheduleAsync(any(Runnable.class))).thenAnswer(invocation -> {
            Runnable task = invocation.getArgument(0);
            task.run();
            return null;
        });

        this.plugin.setEnabled(true);
        this.plugin.eval("wb_test_schedule_tasks($plugin);");

        assertEquals(1, ((Number) this.state().get("tasks")).intValue(), "scheduled php task must run");
    }

    @Test
    void brokenPhpHandlersDoNotBreakEventDispatch() throws Throwable {
        this.plugin.setEnabled(true);
        this.plugin.eval("wb_test_install_broken_handler($plugin);");

        TestSyncEvent event = new TestSyncEvent();
        assertDoesNotThrow(() -> this.eventManager.callEvent(event), "a broken php handler must not fail dispatch");

        // a healthy handler registered after the broken one still runs
        this.plugin.eval("wb_test_install_healthy_handler($plugin);");
        TestSyncEvent second = new TestSyncEvent();
        this.eventManager.callEvent(second);
        assertTrue(second.isHealthyFlag(), "php handler must be able to mutate the event synchronously");
    }

    @Test
    void brokenCompletableHandlerDoesNotFailTheEventPipeline() throws Throwable {
        // A throwing PHP handler used to fail the event's completable future, which the
        // login/transfer paths turn into a player disconnect. Java handler errors are logged
        // and swallowed, and the PHP bridge must behave the same way.
        this.plugin.setEnabled(true);
        this.plugin.eval("wb_test_install_broken_handler($plugin);");

        TestCompletableEvent event = new TestCompletableEvent();
        CompletableFuture<?> future = this.eventManager.callEvent(event);
        future.get(5, TimeUnit.SECONDS); // must complete normally, not exceptionally
    }

    @Test
    void disableDetachesEventSubscriptionsAndCommands() throws Throwable {
        this.plugin.setEnabled(true);
        assertEquals(1, this.eventManager.subscriberCount(TestSyncEvent.class),
                "the enabled plugin holds one subscription");
        assertEquals(1, this.registeredCommands.size(), "the enabled plugin holds one command");

        this.plugin.setEnabled(false);

        assertEquals(0, this.eventManager.subscriberCount(TestSyncEvent.class),
                "disable must detach every event subscription before the engine closes");
        assertEquals(0, this.registeredCommands.size(),
                "disable must unregister every command the plugin registered");

        // Events fired after disable must not reach into the closed engine
        assertDoesNotThrow(() -> this.eventManager.callEvent(new TestSyncEvent()));
        assertDoesNotThrow(() -> this.eventManager.callEvent(new TestCompletableEvent()));
    }

    @Test
    void engineIsThreadSafeForConcurrentHandlers() throws Throwable {
        this.plugin.setEnabled(true);
        this.plugin.eval("wb_test_install_solo_handler($plugin);");
        this.plugin.eval("wb_test_reset_solo();");

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
            assertTrue(done.await(120, TimeUnit.SECONDS), "all event calls finished");
            assertTrue(error.get() == null, "no thread may observe a quercus error: " + error.get());
            String solo = PhpEngineAccess.execute(this.plugin.environment(), () -> {
                try {
                    return this.plugin.eval("return wb_test_read_solo();").toJavaString();
                } catch (Throwable e) {
                    throw new RuntimeException(e);
                }
            });
            assertEquals(String.valueOf(threads * callsPerThread), String.valueOf(solo),
                    "every concurrent event must reach the php handler exactly once");
        } finally {
            pool.shutdownNow();
        }
    }
}
