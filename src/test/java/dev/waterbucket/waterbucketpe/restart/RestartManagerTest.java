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

package dev.waterbucket.waterbucketpe.restart;

import dev.waterbucket.waterbucketpe.ProxyServer;
import dev.waterbucket.waterbucketpe.event.EventManager;
import dev.waterbucket.waterbucketpe.event.defaults.ProxyRestartEvent;
import dev.waterbucket.waterbucketpe.player.PlayerManager;
import dev.waterbucket.waterbucketpe.player.ProxiedPlayer;
import dev.waterbucket.waterbucketpe.scheduler.WaterbucketScheduler;
import dev.waterbucket.waterbucketpe.utils.config.proxy.ProxyConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests the restart sequence contract: event veto, one-shot guard, graceful player
 * disconnect, script generation and the shutdown hand-over. Nothing here executes a real
 * process - the script launch step runs with a generation that cannot locate a packaged jar
 * inside the test JVM, which is exactly the documented fallback path.
 */
class RestartManagerTest {

    @TempDir
    Path tempDir;

    private ProxyServer proxy;
    private ProxyConfig config;
    private EventManager eventManager;
    private WaterbucketScheduler scheduler;
    private RestartManager restartManager;

    /** Runnables the manager scheduled, in order - the test executes them manually. */
    private final List<Runnable> scheduled = new CopyOnWriteArrayList<>();
    private final List<String> shutdownStatuses = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        this.proxy = mock(ProxyServer.class);
        this.config = mock(ProxyConfig.class);
        this.scheduler = mock(WaterbucketScheduler.class);

        when(this.proxy.getConfiguration()).thenReturn(this.config);
        when(this.config.getIdleThreads()).thenReturn(1);
        when(this.proxy.isRunning()).thenReturn(true);
        when(this.proxy.getLogger()).thenReturn(mock(dev.waterbucket.waterbucketpe.logger.MainLogger.class));
        when(this.proxy.getDataPath()).thenReturn(this.tempDir);
        when(this.proxy.getScheduler()).thenReturn(this.scheduler);
        when(this.scheduler.scheduleTask(any(Runnable.class), org.mockito.ArgumentMatchers.anyBoolean()))
                .thenAnswer(invocation -> {
                    this.scheduled.add(invocation.getArgument(0, Runnable.class));
                    return null;
                });
        when(this.proxy.getPlayerManager()).thenReturn(mock(PlayerManager.class));
        when(this.proxy.getPlayerManager().getPlayers()).thenReturn(Map.of());

        // Real event manager so ProxyRestartEvent handlers actually run
        this.eventManager = new EventManager(this.proxy);
        when(this.proxy.getEventManager()).thenReturn(this.eventManager);

        // shutdown(0) is terminal on the real proxy; record the call instead
        org.mockito.Mockito.doAnswer(invocation -> {
            this.shutdownStatuses.add(String.valueOf(invocation.getArgument(0, Integer.class)));
            return null;
        }).when(this.proxy).shutdown(org.mockito.ArgumentMatchers.anyInt());

        when(this.config.restartUseScript()).thenReturn(false);

        this.restartManager = new RestartManager(this.proxy);
        // ProxyServer.getInstance() backs TranslationContainer.getTranslated(); the null
        // fallback path covers the test, no instance surgery needed.
    }

    @AfterEach
    void tearDown() {
        // Real EventManager subscribes listeners into per-event maps; no explicit cleanup
        // needed because each test builds a fresh manager + event manager.
    }

    @Test
    void restartSequenceBroadcastsKicksAndShutsDown() throws Exception {
        assertTrue(this.restartManager.requestRestart("console"), "the first request must be accepted");
        assertEquals(1, this.scheduled.size(), "the sequence must be scheduled exactly once");

        this.scheduled.get(0).run();

        verify(this.proxy, atLeastOnce()).broadcastMessage(any(dev.waterbucket.waterbucketpe.utils.types.TextContainer.class));
        assertEquals(List.of("0"), this.shutdownStatuses, "the sequence must end in a clean shutdown(0)");
        assertTrue(this.restartManager.isRestartingNow(), "the restarting flag stays set while the proxy is going down");
    }

    @Test
    void concurrentRequestsCollapseIntoOne() throws Exception {
        assertTrue(this.restartManager.requestRestart("console"));
        // The sequence is only executed when the test runs the scheduled task, so the
        // one-shot guard is still held here.
        assertFalse(this.restartManager.requestRestart("panel"), "a second concurrent request must be refused");
        assertFalse(this.restartManager.requestRestart("console"), "spam must be refused too");
        assertEquals(1, this.scheduled.size(), "only one sequence may be scheduled");
    }

    @Test
    void cancelledEventVetoesTheRestart() throws Exception {
        AtomicReference<String> initiator = new AtomicReference<>();
        java.util.function.Consumer<ProxyRestartEvent> veto = event -> {
            initiator.set(event.getInitiator());
            event.setCancelled(true);
        };
        this.eventManager.subscribe(ProxyRestartEvent.class, veto);

        assertFalse(this.restartManager.requestRestart("console"), "a vetoed restart must report refusal");
        assertEquals("console", initiator.get(), "the event must carry the initiator");
        assertEquals(0, this.scheduled.size(), "a vetoed restart must not schedule the sequence");

        // Remove the veto: the guard must have been released by the cancelled request
        this.eventManager.unsubscribe(ProxyRestartEvent.class, veto);
        assertTrue(this.restartManager.requestRestart("console"), "the next request must be accepted after a veto");
        assertEquals(1, this.scheduled.size(), "the accepted request schedules the sequence");
    }

    @Test
    void playersAreKickedWithTheRestartMessage() throws Exception {
        ProxiedPlayer player = mock(ProxiedPlayer.class);
        when(player.getName()).thenReturn("Steve");
        when(this.proxy.getPlayerManager().getPlayers()).thenReturn(Map.of(UUID.randomUUID(), player));

        assertTrue(this.restartManager.requestRestart("console"));
        this.scheduled.get(0).run();

        verify(player, atLeastOnce()).disconnect(org.mockito.ArgumentMatchers.any(CharSequence.class));
    }

    @Test
    void relaunchScriptFallsBackGracefullyOutsideAPackagedRun() throws Exception {
        // Inside the test JVM the code source is a classes directory, so script generation
        // cannot locate a jar: the launch must log + fall back, never throw or exec.
        when(this.config.restartUseScript()).thenReturn(true);
        when(this.config.restartGenerateScript()).thenReturn(true);
        when(this.config.restartScriptPath()).thenReturn("");

        this.restartManager.launchScriptForTest();

        // No script file may appear because generation was skipped before writing
        try (var files = Files.walk(this.tempDir)) {
            assertTrue(files.filter(Files::isRegularFile)
                    .map(p -> p.getFileName().toString())
                    .noneMatch(name -> name.equals("restart.sh") || name.equals("restart.bat")),
                    "generation must be skipped when no packaged jar is found");
        }
        // and the shutdown hand-over happens when the full sequence runs
        assertTrue(this.restartManager.requestRestart("console"));
        this.scheduled.get(0).run();
        assertEquals(List.of("0"), this.shutdownStatuses);
    }

    @Test
    void scriptGenerationWritesExecutableRelauncher() throws Exception {
        Path fakeJar = this.tempDir.resolve("Waterbucket.jar");
        Files.writeString(fakeJar, "fake");
        Path script = this.tempDir.resolve("restart.sh");

        RestartManager manager = new RestartManager(this.proxy) {
            @Override
            protected Path locateProxyJar() {
                return fakeJar;
            }
        };
        Path generated = manager.generateScript(script);
        assertNotNull(generated, "generation must succeed with a resolvable jar");
        assertTrue(Files.isRegularFile(generated));
        String content = Files.readString(generated);
        assertTrue(content.contains("#!/bin/sh"), "the unix script must have a shebang");
        assertTrue(content.contains("kill -0"), "the script must wait for the old process to exit");
        assertTrue(content.contains("exec java"), "the script must relaunch java");
        assertTrue(content.contains("Waterbucket.jar"), "the script must reference the proxy jar");
        // Executable bit on POSIX filesystems (skipped on non-POSIX mounts)
        try {
            assertTrue(Files.getPosixFilePermissions(generated)
                            .contains(java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE),
                    "the script must be executable");
        } catch (UnsupportedOperationException ignored) {
            // Non-POSIX filesystem
        }
    }
}
