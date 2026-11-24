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

package dev.waterbucket.waterbucketpe.event;

import dev.waterbucket.waterbucketpe.ProxyServer;
import dev.waterbucket.waterbucketpe.command.CommandMap;
import dev.waterbucket.waterbucketpe.event.defaults.PlayerCommandEvent;
import dev.waterbucket.waterbucketpe.player.ProxiedPlayer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers PlayerCommandEvent: the event shape, and the ProxyServer.handlePlayerCommand wiring
 * that fires it for every command a player types, lets handlers cancel it (consuming the
 * command) and applies command rewrites.
 */
class PlayerCommandEventTest {

    private final ProxiedPlayer player = mock(ProxiedPlayer.class);
    private final ProxyServer eventHost = mock(ProxyServer.class);
    private EventManager events;

    @BeforeEach
    void setUp() {
        // The EventManager constructor reads idleThreads from the config
        dev.waterbucket.waterbucketpe.utils.config.proxy.ProxyConfig config =
                mock(dev.waterbucket.waterbucketpe.utils.config.proxy.ProxyConfig.class);
        when(config.getIdleThreads()).thenReturn(1);
        when(this.eventHost.getConfiguration()).thenReturn(config);
        this.events = new EventManager(this.eventHost);
    }

    @Test
    void eventCarriesPlayerAndMutableCommand() {
        PlayerCommandEvent event = new PlayerCommandEvent(this.player, "/hub");

        assertSame(this.player, event.getPlayer(), "player events must expose their player");
        assertEquals("/hub", event.getCommand());
        assertFalse(event.isChanged(), "a fresh event is not marked as changed");
        assertFalse(event.isCancelled(), "commands must not be cancelled by default");

        event.setCommand("/lobby");
        assertTrue(event.isChanged(), "rewriting the command must mark it changed");
        assertEquals("/lobby", event.getCommand());

        event.setCommand("/lobby");
        assertTrue(event.isChanged(), "writing the same value must keep the changed flag");
    }

    /**
     * handlePlayerCommand reads the private eventManager/commandMap/commandRateLimiter fields
     * directly, so a partial mock needs them injected instead of just stubbing the getters.
     * The injected rate limiter sees the mocked config's {@code getCommandRateLimit()} (0 =
     * disabled), so these tests exercise the event path, not flood protection.
     */
    private ProxyServer proxyWithRealCommandPath(CommandMap commandMap) throws Exception {
        ProxyServer proxy = mock(ProxyServer.class);
        Field eventManagerField = ProxyServer.class.getDeclaredField("eventManager");
        eventManagerField.setAccessible(true);
        eventManagerField.set(proxy, this.events);
        Field commandMapField = ProxyServer.class.getDeclaredField("commandMap");
        commandMapField.setAccessible(true);
        commandMapField.set(proxy, commandMap);
        // The rate limiter reads the config; a plain mock returns commandRateLimit()=0, which
        // disables flood protection for these event-path tests.
        dev.waterbucket.waterbucketpe.utils.config.proxy.ProxyConfig config =
                mock(dev.waterbucket.waterbucketpe.utils.config.proxy.ProxyConfig.class);
        when(proxy.getConfiguration()).thenReturn(config);
        Field rateLimiterField = ProxyServer.class.getDeclaredField("commandRateLimiter");
        rateLimiterField.setAccessible(true);
        rateLimiterField.set(proxy, new dev.waterbucket.waterbucketpe.command.CommandRateLimiter(proxy));
        doCallRealMethod().when(proxy).handlePlayerCommand(any(), anyString());
        return proxy;
    }

    @Test
    void cancellingTheEventConsumesTheCommand() throws Exception {
        CommandMap commandMap = mock(CommandMap.class);
        ProxyServer proxy = this.proxyWithRealCommandPath(commandMap);

        this.events.subscribe(PlayerCommandEvent.class, PlayerCommandEvent::setCancelled);

        assertTrue(proxy.handlePlayerCommand(this.player, "/secret"),
                "a cancelled command must be consumed, not forwarded downstream");
        verify(commandMap, never()).handleMessage(any(), anyString());
    }

    @Test
    void nonCommandPrefixStillReachesDownstreamAfterTheEvent() throws Exception {
        CommandMap commandMap = mock(CommandMap.class);
        when(commandMap.handleMessage(any(), anyString())).thenReturn(false);
        ProxyServer proxy = this.proxyWithRealCommandPath(commandMap);

        assertFalse(proxy.handlePlayerCommand(this.player, "hello there"),
                "a message without the command prefix must still be forwarded");
        verify(commandMap).handleMessage(any(), eq("hello there"));
    }

    @Test
    void handlersCanRewriteTheCommandBeforeDispatch() throws Exception {
        CommandMap commandMap = mock(CommandMap.class);
        when(commandMap.handleMessage(any(), eq("/rewritten"))).thenReturn(false);
        ProxyServer proxy = this.proxyWithRealCommandPath(commandMap);

        this.events.subscribe(PlayerCommandEvent.class, event -> event.setCommand("/rewritten"));

        proxy.handlePlayerCommand(this.player, "/original");

        verify(commandMap).handleMessage(any(), eq("/rewritten"));
        verify(commandMap, never()).handleMessage(any(), eq("/original"));
    }

    @Test
    void priorityOrderingAppliesToCommandHandlers() {
        AtomicReference<String> result = new AtomicReference<>("/");
        this.events.subscribe(PlayerCommandEvent.class, event -> result.set(result.get() + "low"),
                EventPriority.LOWEST);
        this.events.subscribe(PlayerCommandEvent.class, event -> result.set(result.get() + "high"),
                EventPriority.HIGHEST);

        this.events.callEvent(new PlayerCommandEvent(this.player, "/order"));

        assertEquals("/lowhigh", result.get(), "LOWEST must run before HIGHEST");
    }
}
