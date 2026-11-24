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

package dev.waterbucket.waterbucketpe.scheduler;

import dev.waterbucket.waterbucketpe.plugin.Plugin;
import org.cloudburstmc.protocol.common.util.Preconditions;

import java.util.concurrent.TimeUnit;

/**
 * Fluent, Velocity-style builder for time-based tasks: {@code delay} and {@code repeat} are
 * expressed in real time ({@link TimeUnit}) instead of proxy ticks, so schedules keep their
 * cadence even while the tick loop catches up after a slow burst.
 * <p>
 * Usage:
 * <pre>{@code
 * proxy.getScheduler().buildTask(this, () -> server.pingDownstreams())
 *      .delay(10, TimeUnit.SECONDS)
 *      .repeat(30, TimeUnit.SECONDS)
 *      .schedule();
 * }</pre>
 * Semantics after {@link #schedule()}:
 * <ul>
 *     <li>{@code repeat == 0}: the task runs once after {@code delay} (default immediately)</li>
 *     <li>{@code repeat > 0}: the task runs {@code delay} from now, then every {@code repeat},
 *     with the next repetition always starting a full {@code repeat} after the previous one
 *     finished - a slow task can never pile up concurrent executions of itself</li>
 *     <li>Tasks built with a {@link Plugin} owner are cancelled automatically when the plugin
 *     is disabled</li>
 * </ul>
 */
public final class TaskBuilder {

    private final WaterbucketScheduler scheduler;
    private final Plugin owner;
    private final Runnable runnable;
    private long delayMillis;
    private long repeatMillis;

    TaskBuilder(WaterbucketScheduler scheduler, Plugin owner, Runnable runnable) {
        this.scheduler = scheduler;
        this.owner = owner;
        this.runnable = runnable;
    }

    /**
     * Sets how long to wait before the first run. {@code 0} schedules the first run for as
     * soon as the scheduler pool picks the task up.
     *
     * @throws IllegalArgumentException for a negative duration or a null unit
     */
    public TaskBuilder delay(long time, TimeUnit unit) {
        Preconditions.checkArgument(unit != null, "time unit can not be null");
        Preconditions.checkArgument(time >= 0, "delay can not be negative");
        this.delayMillis = Math.max(0, unit.toMillis(time));
        return this;
    }

    /**
     * Sets the repetition period. {@code 0} (the default) makes the task one-shot.
     *
     * @throws IllegalArgumentException for a negative duration or a null unit
     */
    public TaskBuilder repeat(long time, TimeUnit unit) {
        Preconditions.checkArgument(unit != null, "time unit can not be null");
        Preconditions.checkArgument(time >= 0, "repeat can not be negative");
        this.repeatMillis = Math.max(0, unit.toMillis(time));
        return this;
    }

    /** Resets the delay to zero (run as soon as scheduled). */
    public TaskBuilder clearDelay() {
        this.delayMillis = 0;
        return this;
    }

    /** Resets the repetition to zero (run exactly once). */
    public TaskBuilder clearRepeat() {
        this.repeatMillis = 0;
        return this;
    }

    /**
     * Registers the task with the scheduler.
     *
     * @return the live task handle; cancel it to stop the schedule
     */
    public ScheduledTask schedule() {
        return this.scheduler.scheduleTimeTask(this.owner, this.runnable, this.delayMillis, this.repeatMillis);
    }
}
