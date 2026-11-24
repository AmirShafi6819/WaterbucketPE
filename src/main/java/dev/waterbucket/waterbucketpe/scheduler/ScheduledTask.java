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
import lombok.Getter;

import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Handle for a task scheduled through {@link WaterbucketScheduler#buildTask(Plugin, Runnable)}.
 * Mirrors the Velocity-style contract: a task can be cancelled while pending or between
 * repetitions, and cancelling a finished task is a harmless no-op.
 * <p>
 * The task always runs on the time-scheduler pool, never on Netty event loops or the proxy
 * tick thread - reach the proxy only through thread-safe APIs from inside these tasks.
 */
public final class ScheduledTask {

    @Getter
    private final Plugin owner;
    @Getter
    private final Runnable runnable;
    @Getter
    private final int taskId;
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private volatile ScheduledFuture<?> future;

    ScheduledTask(Plugin owner, Runnable runnable, int taskId) {
        this.owner = owner;
        this.runnable = runnable;
        this.taskId = taskId;
    }

    void attach(ScheduledFuture<?> future) {
        this.future = future;
    }

    /**
     * Cancels the task. A pending task never runs; a repeating task stops after the currently
     * executing repetition finishes; an already finished or already cancelled task is ignored.
     *
     * @return {@code true} when this call cancelled something still pending or running
     */
    public boolean cancel() {
        if (!this.cancelled.compareAndSet(false, true)) {
            return false;
        }
        ScheduledFuture<?> current = this.future;
        boolean stopped = current != null && current.cancel(false);
        WaterbucketScheduler.getInstance().detachTimeTask(this);
        return stopped;
    }

    /**
     * @return whether the task was cancelled and will not run (again)
     */
    public boolean isCancelled() {
        return this.cancelled.get();
    }
}
