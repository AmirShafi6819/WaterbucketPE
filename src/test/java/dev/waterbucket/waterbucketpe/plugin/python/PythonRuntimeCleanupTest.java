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
import dev.waterbucket.waterbucketpe.event.Event;
import dev.waterbucket.waterbucketpe.event.EventManager;
import dev.waterbucket.waterbucketpe.plugin.PluginYAML;
import dev.waterbucket.waterbucketpe.scheduler.TaskHandler;
import dev.waterbucket.waterbucketpe.scheduler.WaterbucketScheduler;
import dev.waterbucket.waterbucketpe.utils.config.proxy.ProxyConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Stage B runtime audit (Phase 3): Python runtime resource ownership across the disable
 * boundary.
 *
 * P3-8: scheduled tasks are not tracked, so a repeating task of a disabled plugin keeps
 *       firing into the closed interpreter until proxy shutdown.
 * P3-9: subscribe/registerCommand invoked after the context closed still register live
 *       resources pointing into a dead interpreter.
 */
class PythonRuntimeCleanupTest {

    public static class ProbeEvent extends Event {
    }

    @TempDir
    Path tempDir;

    private ProxyServer proxy;
    private EventManager eventManager;
    private final List<Command> registeredCommands = new CopyOnWriteArrayList<>();
    private final List<TaskHandler<?>> scheduledHandlers = new CopyOnWriteArrayList<>();

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
            this.registeredCommands.removeIf(c -> c.getName().equalsIgnoreCase(invocation.getArgument(0, String.class)));
            return true;
        });
        // Capture the handlers the plugin received: after disable, the plugin must have
        // cancelled every one of them.
        when(scheduler.scheduleAsync(any(Runnable.class))).thenAnswer(invocation -> {
            TaskHandler<Runnable> handler = new TaskHandler<>(invocation.getArgument(0), 1, true);
            this.scheduledHandlers.add(handler);
            return handler;
        });
        when(scheduler.scheduleDelayed(any(Runnable.class), anyInt(), anyBoolean())).thenAnswer(invocation -> {
            TaskHandler<Runnable> handler = new TaskHandler<>(invocation.getArgument(0), 2, true);
            this.scheduledHandlers.add(handler);
            return handler;
        });
        when(scheduler.scheduleRepeating(any(Runnable.class), anyInt(), anyBoolean())).thenAnswer(invocation -> {
            TaskHandler<Runnable> handler = new TaskHandler<>(invocation.getArgument(0), 3, true);
            this.scheduledHandlers.add(handler);
            return handler;
        });

        this.eventManager = new EventManager(this.proxy);
        when(this.proxy.getEventManager()).thenReturn(this.eventManager);

        Path pluginDir = this.tempDir.resolve("plugins").resolve("TasksPy");
        Files.createDirectories(pluginDir);
        Files.writeString(pluginDir.resolve("waterbucket.yml"),
                "name: TasksPy\nversion: 1.0.0\nauthor: test\nmain: main.py\n");
        Files.writeString(pluginDir.resolve("main.py"), """
                import java

                ProbeEvent = java.type(
                    "dev.waterbucket.waterbucketpe.plugin.python.PythonRuntimeCleanupTest$ProbeEvent")

                def on_startup(plugin):
                    plugin.schedule_repeating(lambda: None, 1)

                def on_enable(plugin):
                    plugin.subscribe(ProbeEvent, lambda event: None)
                """);

        PluginYAML descriptor = new PluginYAML();
        descriptor.name = "TasksPy";
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

    @Test
    void disableCancelsScheduledTasks() throws Exception {
        this.plugin.onStartup();
        this.plugin.setEnabled(true);
        assertEquals(1, this.scheduledHandlers.size(), "the script scheduled one repeating task");

        this.plugin.setEnabled(false);

        // A repeating task left alive after disable keeps firing into the closed interpreter
        // until the proxy shuts down - every handler the plugin received must be cancelled.
        assertTrue(this.scheduledHandlers.stream().allMatch(TaskHandler::isCancelled),
                "every scheduled task handler must be cancelled on disable");
    }

    @Test
    void registrationAfterDisableIsRefused() throws Exception {
        this.plugin.onStartup();
        this.plugin.setEnabled(true);
        this.plugin.setEnabled(false);

        // Simulates a stale callback reaching the plugin after the interpreter is closed.
        assertFalse(this.plugin.registerCommand("late", "d", "/late", null, null,
                        (sender, alias, args) -> true),
                "a disabled plugin must not register commands into a closed context");
        assertEquals(0, this.registeredCommands.size(), "no command may leak past disable");

        this.plugin.subscribe(ProbeEvent.class, event -> { });
        assertEquals(0, this.eventManager.subscriberCount(ProbeEvent.class),
                "a disabled plugin must not subscribe handlers into a closed context");
    }
}
