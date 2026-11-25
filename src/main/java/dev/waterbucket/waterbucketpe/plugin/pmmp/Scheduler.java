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

package dev.waterbucket.waterbucketpe.plugin.pmmp;

import dev.waterbucket.waterbucketpe.plugin.Plugin;
import dev.waterbucket.waterbucketpe.scheduler.WaterbucketScheduler;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * The compatibility scheduler facade over the proxy scheduler: one-shot, delayed and repeated
 * tasks on the proxy's 20-ticks-per-second loop, plus the current tick counter. Handlers carry
 * an id and a working {@link TaskHandler#cancel()}, matching the timing model of the original
 * server software. Attributed overloads hand the proxy scheduler the owning plugin, so its
 * tasks are stopped with the plugin automatically.
 */
public class Scheduler {

    private final WaterbucketScheduler delegate;
    private final AtomicInteger taskIds = new AtomicInteger(1);

    Scheduler(WaterbucketScheduler delegate) {
        this.delegate = delegate;
    }

    /**
     * The current tick of the proxy loop.
     */
    public int getCurrentTick() {
        return this.delegate.getCurrentTick();
    }

    /**
     * Runs the task on the next tick (sync). Plugins should prefer the attributed overloads so
     * their tasks stop when they disable.
     */
    public TaskHandler scheduleTask(Runnable task, boolean async) {
        return new TaskHandler(this.taskIds.getAndIncrement(),
                async ? this.delegate.scheduleAsync(task) : this.delegate.scheduleTask(task, false));
    }

    public TaskHandler scheduleTask(Plugin plugin, Runnable task, boolean async) {
        return new TaskHandler(this.taskIds.getAndIncrement(),
                async ? this.delegate.scheduleAsync(task) : this.delegate.scheduleTask(plugin, task, false));
    }

    /**
     * Runs the task once after {@code delayTicks} (sync on the tick loop unless attributed
     * async variants are used).
     */
    public TaskHandler scheduleDelayedTask(Runnable task, int delayTicks) {
        return new TaskHandler(this.taskIds.getAndIncrement(),
                this.delegate.scheduleDelayed(task, Math.max(1, delayTicks), false));
    }

    public TaskHandler scheduleDelayedTask(Plugin plugin, Runnable task, int delayTicks, boolean async) {
        return new TaskHandler(this.taskIds.getAndIncrement(),
                this.delegate.scheduleDelayed(plugin, task, Math.max(1, delayTicks), async));
    }

    /**
     * Runs the task every {@code periodTicks} ticks (sync on the tick loop). Async work belongs
     * in the attributed async overload.
     */
    public TaskHandler scheduleRepeatingTask(Runnable task, int periodTicks) {
        return new TaskHandler(this.taskIds.getAndIncrement(),
                this.delegate.scheduleRepeating(task, Math.max(1, periodTicks), false));
    }

    public TaskHandler scheduleRepeatingTask(Plugin plugin, Runnable task, int periodTicks, boolean async) {
        return new TaskHandler(this.taskIds.getAndIncrement(),
                this.delegate.scheduleRepeating(plugin, task, 0, Math.max(1, periodTicks), async));
    }

    /**
     * Runs the task once after the delay and then every {@code periodTicks}.
     */
    public TaskHandler scheduleDelayedRepeatingTask(Runnable task, int delayTicks, int periodTicks) {
        return new TaskHandler(this.taskIds.getAndIncrement(),
                this.delegate.scheduleDelayedRepeating(task, Math.max(1, delayTicks),
                        Math.max(1, periodTicks), false));
    }

    /**
     * Convenience: a task that receives the handler itself, so it can cancel itself from
     * inside (poll-style loops).
     */
    public TaskHandler scheduleRepeatingTask(Consumer<TaskHandler> task, int periodTicks) {
        TaskHandler[] holder = new TaskHandler[1];
        holder[0] = this.scheduleRepeatingTask(() -> task.accept(holder[0]), periodTicks);
        return holder[0];
    }
}
