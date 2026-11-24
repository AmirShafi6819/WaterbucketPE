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

package dev.waterbucket.waterbucketpe.sleep;

import dev.waterbucket.waterbucketpe.ProxyServer;
import dev.waterbucket.waterbucketpe.player.PlayerManager;
import dev.waterbucket.waterbucketpe.player.ProxiedPlayer;
import dev.waterbucket.waterbucketpe.utils.config.proxy.ProxyConfig;
import dev.waterbucket.waterbucketpe.utils.config.proxy.SleepModeSettings;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests the sleep-mode state machine: entry after the configured idle window, instant wake
 * on contact, the players-online veto and the operator's force-sleep command path.
 */
class SleepModeManagerTest {

    private ProxyServer proxy;
    private ProxyConfig config;
    private SleepModeSettings settings;
    private Map<UUID, ProxiedPlayer> players;
    private SleepModeManager sleep;

    @BeforeEach
    void setUp() {
        this.proxy = mock(ProxyServer.class);
        this.config = mock(ProxyConfig.class);
        this.settings = new SleepModeSettings();
        this.players = new HashMap<>();

        when(this.proxy.getConfiguration()).thenReturn(this.config);
        when(this.config.getSleepModeSettings()).thenReturn(this.settings);
        when(this.proxy.getPlayerManager()).thenReturn(mock(PlayerManager.class));
        when(this.proxy.getPlayerManager().getPlayers()).thenReturn(this.players);

        this.sleep = new SleepModeManager(this.proxy);
    }

    @Test
    void heartbeatsKeepAnActiveProxyAwake() {
        this.settings.setAfterMinutes(5);
        // A player is online: every heartbeat must reset the idle window
        this.players.put(UUID.randomUUID(), mock(ProxiedPlayer.class));
        for (int i = 0; i < 1000; i++) {
            this.sleep.tick();
        }
        assertFalse(this.sleep.isSleeping(), "an occupied proxy never sleeps");
    }

    @Test
    void emptyProxyFallsAsleepAfterTheIdleWindow() {
        this.settings.setAfterMinutes(1);
        // Idle since long before now: the very next heartbeat must trigger sleep
        this.forceIdlePastThreshold();

        this.sleep.tick();
        assertTrue(this.sleep.isSleeping(), "an empty proxy past the idle window must sleep");

        // Repeat heartbeats while asleep are no-ops (no re-entry, no GC spam)
        this.sleep.tick();
        this.sleep.tick();
        assertTrue(this.sleep.isSleeping());
    }

    @Test
    void activityWakesTheProxyInstantly() {
        this.settings.setAfterMinutes(1);
        this.forceIdlePastThreshold();
        this.sleep.tick();
        assertTrue(this.sleep.isSleeping(), "precondition: asleep");

        this.sleep.onActivity();
        assertFalse(this.sleep.isSleeping(), "contact must wake the proxy");

        // And the wake keeps it awake for a fresh idle window even with no players
        this.forceIdleJustNow();
        this.sleep.tick();
        assertFalse(this.sleep.isSleeping(), "a fresh idle window must pass before sleeping again");
    }

    @Test
    void disabledFeatureNeverSleeps() {
        this.settings.enable(false);
        this.settings.setAfterMinutes(1);
        this.forceIdlePastThreshold();
        for (int i = 0; i < 100; i++) {
            this.sleep.tick();
        }
        assertFalse(this.sleep.isSleeping(), "sleep_mode.enabled=false must keep the proxy awake");
    }

    @Test
    void forceSleepRefusesWhenPlayersAreOnline() {
        this.settings.setAfterMinutes(5);
        this.players.put(UUID.randomUUID(), mock(ProxiedPlayer.class));
        assertFalse(this.sleep.forceSleep(), "an occupied proxy cannot be forced to sleep");
        assertFalse(this.sleep.isSleeping());

        this.players.clear();
        assertTrue(this.sleep.forceSleep(), "an empty proxy can be forced to sleep");
        assertTrue(this.sleep.isSleeping());
    }

    /** Back-dates the idle window so the next heartbeat crosses the threshold. */
    private void forceIdlePastThreshold() {
        // The manager runs in real time against wall-clock idle windows; the package-private
        // seam keeps the unit deterministic without waiting real minutes.
        this.sleep.backdateLastActivityForTest(System.currentTimeMillis() - 10 * 60_000L);
    }

    /** Resets the idle window to "just now" through the public activity API. */
    private void forceIdleJustNow() {
        this.sleep.onActivity();
    }
}
