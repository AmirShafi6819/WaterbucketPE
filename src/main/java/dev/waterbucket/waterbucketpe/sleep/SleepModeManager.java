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
import lombok.Getter;
import lombok.extern.log4j.Log4j2;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Idle resource saver for empty proxies.
 * <p>
 * The listener, the tick loop and every connection path stay fully alive - sleep mode only
 * pauses work that exists to monitor or tidy a proxy with players on it, so an idle proxy
 * sits close to its floor (flat CPU, GC-trimmed heap) instead of burning cycles pinging
 * servers nobody is using, and wakes instantly on the first contact:
 * <ul>
 *     <li>server-list ping ({@code ProxyServer#callPingEvent})</li>
 *     <li>query request ({@code QueryHandler})</li>
 *     <li>player login ({@code ProxiedPlayer} login)</li>
 *     <li>panel API request</li>
 *     <li>console/panel command ({@code ProxyServer#dispatchCommand})</li>
 * </ul>
 * Waking is a single volatile flip plus one log line - the proxy never has to "start"
 * anything, because nothing was stopped: the entry cost of the first player after a night
 * of sleep is the same as the entry cost after one second of idling.
 * <p>
 * {@link #onActivity()} is called from hot paths, so it is one volatile write on the awake
 * path and a CAS-guarded wake on the asleep path.
 */
@Log4j2
public class SleepModeManager {

    /** One heartbeat per second; the idle threshold is minutes, so this is plenty precise. */
    public static final int HEARTBEAT_TICKS = 20;

    private static final long MS_PER_MINUTE = 60_000L;

    private final ProxyServer proxy;
    private final MemoryMXBean memory = ManagementFactory.getMemoryMXBean();
    private final AtomicBoolean waking = new AtomicBoolean(false);

    @Getter
    private volatile boolean sleeping = false;
    /** Last player activity, ping, query, panel or command contact, in epoch millis. */
    private volatile long lastActivity = System.currentTimeMillis();
    /** Heap usage logged at sleep entry, for the wake comparison line. */
    private volatile long sleepHeapUsed;

    public SleepModeManager(ProxyServer proxy) {
        this.proxy = proxy;
    }

    /**
     * Heartbeat: called once a second from the proxy scheduler. Enters sleep mode when the
     * feature is enabled, no player has been online for the configured idle window and no
     * other contact happened in that window.
     */
    public void tick() {
        if (this.sleeping || !this.enabled()) {
            return;
        }
        long idleMinutes = this.proxy.getConfiguration().getSleepModeSettings().getAfterMinutes();
        if (idleMinutes < 1) {
            return;
        }
        if (!this.proxy.getPlayerManager().getPlayers().isEmpty()) {
            this.lastActivity = System.currentTimeMillis();
            return;
        }
        long idleFor = System.currentTimeMillis() - this.lastActivity;
        if (idleFor >= idleMinutes * MS_PER_MINUTE) {
            this.enterSleep(idleFor);
        }
    }

    private boolean enabled() {
        try {
            return this.proxy.getConfiguration().getSleepModeSettings().enable();
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Test seam (package-private): back-date the idle window so the unit tests can cross the
     * idle threshold deterministically without sleeping in real time.
     */
    void backdateLastActivityForTest(long epochMillis) {
        this.lastActivity = epochMillis;
    }

    /**
     * Records contact from any wake source. On the awake path this is one volatile write;
     * while asleep it wakes the proxy.
     */
    public void onActivity() {
        this.lastActivity = System.currentTimeMillis();
        if (this.sleeping && this.waking.compareAndSet(false, true)) {
            try {
                this.wake();
            } finally {
                this.waking.set(false);
            }
        }
    }

    private void enterSleep(long idleFor) {
        long before = this.memory.getHeapMemoryUsage().getUsed();
        this.sleeping = true;
        this.sleepHeapUsed = before;
        // One GC pass releases the join/session caches a busy day accumulated; the paused
        // periodic work keeps CPU and allocations at the floor while nobody is connected.
        System.gc();
        long after = this.memory.getHeapMemoryUsage().getUsed();
        long freed = Math.max(0, before - after);
        log.info("Proxy is falling asleep after {} of idling with no players: periodic downstream probes "
                        + "and sweeps are paused, heap trimmed by {} ({} -> {}). Wakes instantly on the first "
                        + "ping, query, login, panel request or command.",
                formatDuration(idleFor), formatBytes(freed), formatBytes(before), formatBytes(after));
    }

    /**
     * Falls asleep immediately (operator request via {@code /wbsleep sleep}); refused with
     * false when players are online - sleep mode is only defined for an empty proxy.
     */
    public boolean forceSleep() {
        if (!this.proxy.getPlayerManager().getPlayers().isEmpty() || this.sleeping) {
            return false;
        }
        this.lastActivity = System.currentTimeMillis() - Long.MAX_VALUE / 2;
        this.enterSleep(0);
        return true;
    }

    private void wake() {
        this.sleeping = false;
        long now = this.memory.getHeapMemoryUsage().getUsed();
        log.info("Proxy woke up (heap now {}, slept at {}) - everything is already running, serving immediately.",
                formatBytes(now), formatBytes(this.sleepHeapUsed));
    }

    private static String formatBytes(long bytes) {
        if (bytes >= 1024L * 1024L) {
            return String.format("%.1f MB", bytes / 1024.0 / 1024.0);
        }
        return String.format("%.0f KB", bytes / 1024.0);
    }

    private static String formatDuration(long millis) {
        long minutes = millis / MS_PER_MINUTE;
        if (minutes >= 60) {
            return String.format("%dh %02dm", minutes / 60, minutes % 60);
        }
        return minutes + "m";
    }
}
