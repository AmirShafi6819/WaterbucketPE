/*
 * Copyright 2022 WaterbucketTEAM
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

import dev.waterbucket.waterbucketpe.ProxyServer;
import dev.waterbucket.waterbucketpe.plugin.Plugin;
import dev.waterbucket.waterbucketpe.utils.ThreadFactoryBuilder;
import dev.waterbucket.waterbucketpe.utils.exceptions.SchedulerException;
import io.netty.util.internal.PlatformDependent;
import lombok.Getter;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public class WaterbucketScheduler {

    @Getter
    private static WaterbucketScheduler instance;
    private final ProxyServer proxy;

    @Getter
    private final ExecutorService threadedExecutor;

    private final Map<Integer, TaskHandler<?>> taskHandlerMap = new ConcurrentHashMap<>();
    private final Map<Integer, LinkedList<TaskHandler<?>>> assignedTasks = new ConcurrentHashMap<>();
    private final Queue<TaskHandler<?>> pendingTasks = PlatformDependent.newMpscQueue();
    /**
     * Tasks registered per owning plugin: {@code scheduleRepeating(plugin, runnable, ...)} and
     * friends append here, and {@code cancelTasks(plugin)} cancels the whole set at once when
     * the plugin disables. Entries are removed as soon as a task completes, cancels or the
     * scheduler shuts down, so the map never grows without bound.
     */
    private final Map<Plugin, Set<Integer>> pluginTasks = new ConcurrentHashMap<>();

    private final AtomicInteger currentId = new AtomicInteger();

    public WaterbucketScheduler(ProxyServer proxy) {
        if (instance != null) {
            throw new RuntimeException("Scheduler was already initialized!");
        }
        instance = this;
        this.proxy = proxy;

        ThreadFactoryBuilder builder = ThreadFactoryBuilder.builder()
                .format("WaterbucketScheduler Executor - #%d")
                // Internal pool: must not keep the JVM alive on its own, matching the tick
                // executor and the Netty event loops.
                .daemon(true)
                .build();
        int idleThreads = this.proxy.getConfiguration().getIdleThreads();
        this.threadedExecutor = new ThreadPoolExecutor(idleThreads, Integer.MAX_VALUE, 60, TimeUnit.SECONDS, new SynchronousQueue<>(), builder);
    }

    public <T extends Runnable> TaskHandler<T> scheduleAsync(T task) {
        return this.scheduleTask(task, true);
    }

    public <T extends Runnable> TaskHandler<T> scheduleTask(T task, boolean async) {
        return this.addTask(task, 0, 0, async);
    }

    public <T extends Runnable> TaskHandler<T> scheduleDelayed(T task, int delay) {
        return this.scheduleDelayed(task, delay, false);
    }

    public <T extends Runnable> TaskHandler<T> scheduleDelayed(T task, int delay, boolean async) {
        return this.addTask(task, delay, 0, async);
    }

    public <T extends Runnable> TaskHandler<T> scheduleRepeating(T task, int period) {
        return this.scheduleRepeating(task, period, false);
    }

    public <T extends Runnable> TaskHandler<T> scheduleRepeating(T task, int period, boolean async) {
        return this.addTask(task, 0, period, async);
    }

    public <T extends Runnable> TaskHandler<T> scheduleDelayedRepeating(T task, int delay, int period) {
        return this.scheduleDelayedRepeating(task, delay, period, false);
    }

    public <T extends Runnable> TaskHandler<T> scheduleDelayedRepeating(T task, int delay, int period, boolean async) {
        return this.addTask(task, delay, period, async);
    }

    public <T extends Runnable> TaskHandler<T> addTask(T task, int delay, int period, boolean async) {
        return this.addTask(null, task, delay, period, async);
    }

    public <T extends Runnable> TaskHandler<T> addTask(Plugin plugin, T task, int delay, int period, boolean async) {
        if (delay < 0 || period < 0) {
            throw new SchedulerException("Attempted to register a task with negative delay or period!");
        }

        int currentTick = this.getCurrentTick();
        int taskId = this.currentId.getAndIncrement();

        TaskHandler<T> handler = new TaskHandler<>(task, taskId, async);
        handler.setDelay(delay);
        handler.setPeriod(period);
        handler.setNextRunTick(handler.isDelayed() ? currentTick + delay : currentTick);

        this.pendingTasks.offer(handler);
        this.taskHandlerMap.put(taskId, handler);
        if (plugin != null) {
            handler.setOwner(plugin);
            this.pluginTasks.computeIfAbsent(plugin, key -> ConcurrentHashMap.newKeySet()).add(taskId);
        }
        return handler;
    }

    /**
     * Schedules {@code runnable} on behalf of {@code plugin}. The task is cancelled
     * automatically when the plugin disables, so a repeating task can not leak past its
     * owner's lifetime.
     *
     * @param delay ticks to wait before the first run
     */
    public TaskHandler<?> scheduleDelayed(Plugin plugin, Runnable runnable, int delay) {
        return this.scheduleDelayed(plugin, runnable, delay, false);
    }

    public TaskHandler<?> scheduleDelayed(Plugin plugin, Runnable runnable, int delay, boolean async) {
        return this.addTask(plugin, runnable, delay, 0, async);
    }

    public TaskHandler<?> scheduleTask(Plugin plugin, Runnable runnable, boolean async) {
        return this.addTask(plugin, runnable, 0, 0, async);
    }

    /**
     * Schedules {@code runnable} on behalf of {@code plugin}, running every {@code interval}
     * ticks after {@code delay} ticks. Cancelled automatically when the plugin disables.
     */
    public TaskHandler<?> scheduleRepeating(Plugin plugin, Runnable runnable, int delay, int interval) {
        return this.scheduleRepeating(plugin, runnable, delay, interval, false);
    }

    public TaskHandler<?> scheduleRepeating(Plugin plugin, Runnable runnable, int delay, int interval, boolean async) {
        return this.addTask(plugin, runnable, delay, interval, async);
    }

    /**
     * Cancels a single pending or repeating task by its id. Unknown ids are ignored.
     */
    public void cancelTask(int taskId) {
        TaskHandler<?> handler = this.taskHandlerMap.remove(taskId);
        if (handler != null) {
            handler.cancel();
            this.unregisterPluginTask(handler);
        }
    }

    /**
     * Cancels every task the plugin registered through the plugin-aware scheduling methods.
     * Called from {@code Plugin.setEnabled(false)}, so plugins never have to clean up
     * themselves.
     */
    public void cancelTasks(Plugin plugin) {
        // ConcurrentHashMap.remove(null) throws; a null/unknown plugin simply owns no tasks
        if (plugin == null) {
            return;
        }
        Set<Integer> taskIds = this.pluginTasks.remove(plugin);
        if (taskIds == null) {
            return;
        }
        for (int taskId : taskIds) {
            TaskHandler<?> handler = this.taskHandlerMap.remove(taskId);
            if (handler != null) {
                handler.cancel();
            }
        }
    }

    private void unregisterPluginTask(TaskHandler<?> handler) {
        Plugin owner = handler.getOwner();
        if (owner != null) {
            Set<Integer> taskIds = this.pluginTasks.get(owner);
            if (taskIds != null) {
                taskIds.remove(handler.getTaskId());
            }
        }
    }

    public void onTick(int currentTick) {
        // 1. Assign all tasks to queue by nextRunTick
        TaskHandler<?> task;
        while ((task = this.pendingTasks.poll()) != null) {
            int tick = Math.max(currentTick, task.getNextRunTick());
            this.assignedTasks.computeIfAbsent(tick, integer -> new LinkedList<>()).add(task);
        }

        // 2. Run all tasks assigned to current tick
        LinkedList<TaskHandler<?>> queued = this.assignedTasks.remove(currentTick);
        if (queued == null) return;

        for (TaskHandler<?> taskHandler : queued) {
            this.runTask(taskHandler, currentTick);
        }
    }

    private void runTask(TaskHandler<?> taskHandler, int currentTick) {
        if (taskHandler.isCancelled()) {
            this.taskHandlerMap.remove(taskHandler.getTaskId());
            this.unregisterPluginTask(taskHandler);
            return;
        }

        if (taskHandler.isAsync()) {
            // A repeating async task is re-armed immediately below, so without the execution
            // guard one run slower than its period piled up concurrent executions (and executor
            // threads) without bound. A run due while the previous one is still executing is
            // skipped; the task keeps its schedule either way.
            if (taskHandler.beginExecution()) {
                this.threadedExecutor.execute(() -> {
                    try {
                        taskHandler.onRun(currentTick);
                    } finally {
                        taskHandler.endExecution();
                    }
                });
            }
        } else {
            taskHandler.onRun(currentTick);
        }

        if (taskHandler.calculateNextTick(currentTick)) {
            this.pendingTasks.offer(taskHandler);
            return;
        }

        // The task finished normally: unregister it WITHOUT cancelling. cancel() invokes
        // Task.onCancel(), which for CallbackTask fires every callback a second time - with
        // null instead of the produced result - after the task already completed successfully.
        this.taskHandlerMap.remove(taskHandler.getTaskId());
        this.unregisterPluginTask(taskHandler);
    }

    public void shutdown() {
        this.proxy.getLogger().debug("Scheduler shutdown initialized!");
        this.threadedExecutor.shutdown();

        int count = 25;
        while (count-- > 0) {
            try {
                if (this.threadedExecutor.awaitTermination(100, TimeUnit.MILLISECONDS)) {
                    break;
                }
            } catch (InterruptedException e) {
                // Stop waiting and force the issue below instead of busy-looping on a
                // continuously interrupted thread.
                break;
            }
        }
        // Tasks still running after the grace period get interrupted; without this they kept
        // the (non-daemon) workers alive past the shutdown, delaying or blocking process exit.
        this.threadedExecutor.shutdownNow();
        // Release the task registries so queued-but-never-run handlers are not retained forever.
        this.taskHandlerMap.clear();
        this.assignedTasks.clear();
        this.pluginTasks.clear();
        TaskHandler<?> leftover;
        while ((leftover = this.pendingTasks.poll()) != null) {
            // drain; MPSC queues have no clear()
        }
    }

    public int getCurrentTick() {
        return this.proxy.getCurrentTick();
    }

    /**
     * Package-private lookup used by tests and diagnostics to inspect the live task registry.
     */
    TaskHandler<?> taskHandlerFor(int taskId) {
        return this.taskHandlerMap.get(taskId);
    }
}
