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
    /*
     * Time-based scheduling (buildTask/TaskBuilder) registry, mirroring pluginTasks: one set
     * of ScheduledTask handles per owning plugin, cancelled wholesale when the plugin
     * disables. Entries are removed on completion, cancel or shutdown, so the map never
     * grows without bound.
     */
    private final Map<Plugin, Set<ScheduledTask>> timePluginTasks = new ConcurrentHashMap<>();
    private final AtomicInteger timeTaskId = new AtomicInteger();
    /**
     * Real-time scheduler pool for TaskBuilder tasks, deliberately separate from the tick
     * loop: schedules expressed in {@link java.util.concurrent.TimeUnit} must keep their
     * cadence even while the tick loop is catching up, and repeating tasks must never pile
     * up concurrent executions of themselves (scheduleWithFixedDelay guarantees that).
     * Fixed resident pool: no per-task thread churn, flat idle memory.
     */
    private final ScheduledThreadPoolExecutor timeExecutor;

    private final AtomicInteger currentId = new AtomicInteger();

    /**
     * Shared construction recipe for both proxy executors: a fixed set of resident threads
     * (flat idle memory, no per-task thread churn) with a large bounded queue for bursts and
     * a small elastic head-room when the queue itself saturates.
     * <p>
     * The previous pool was {@code (idleThreads, Integer.MAX_VALUE, SynchronousQueue)}: every
     * burst spawned fresh threads that died 60s later, so RAM and CPU bounced up and down on
     * join bursts and query storms. The queue trades a few microseconds of queuing for a
     * steady thread count; 8192 queued runnables cost ~2 MB, against ~1 MB of stack per
     * thread the old design could spawn without any cap.
     */
    public static ThreadPoolExecutor buildBoundedExecutor(String threadName, int residentThreads) {
        ThreadFactoryBuilder builder = ThreadFactoryBuilder.builder()
                .format(threadName)
                // Internal pool: must not keep the JVM alive on its own, matching the tick
                // executor and the Netty event loops.
                .daemon(true)
                .build();
        int core = Math.max(2, residentThreads);
        int maximum = core * 2;
        ThreadPoolExecutor executor = new ThreadPoolExecutor(core, maximum, 120, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(8192), builder);
        // Practically unreachable (8192-slot queue): if it ever fires, log loudly instead of
        // throwing out of submit sites or running the task on the caller thread (which for the
        // scheduler would be the tick thread and would stall the whole proxy).
        executor.setRejectedExecutionHandler((task, pool) ->
                ProxyServer.getInstance().getLogger().error("Executor {} saturated, dropping task {}",
                        threadName, task));
        return executor;
    }

    public WaterbucketScheduler(ProxyServer proxy) {
        if (instance != null) {
            throw new RuntimeException("Scheduler was already initialized!");
        }
        instance = this;
        this.proxy = proxy;
        int idleThreads = this.proxy.getConfiguration().getIdleThreads();
        this.threadedExecutor = buildBoundedExecutor("WaterbucketScheduler Executor - #%d", idleThreads);

        ThreadFactoryBuilder timeBuilder = ThreadFactoryBuilder.builder()
                .format("WaterbucketTimeScheduler - #%d")
                .daemon(true)
                .build();
        this.timeExecutor = new ScheduledThreadPoolExecutor(Math.max(2, idleThreads / 2), timeBuilder);
        // Cancelled one-shot tasks are purged from the work queue instead of being retained
        // until their delay elapses - matters for plugins that create and cancel many timers.
        this.timeExecutor.setRemoveOnCancelPolicy(true);
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
     * Cancels every task the plugin registered through the plugin-aware scheduling methods
     * and every TaskBuilder task it owns.
     * Called from {@code Plugin.setEnabled(false)}, so plugins never have to clean up
     * themselves.
     */
    public void cancelTasks(Plugin plugin) {
        // ConcurrentHashMap.remove(null) throws; a null/unknown plugin simply owns no tasks
        if (plugin == null) {
            return;
        }
        Set<Integer> taskIds = this.pluginTasks.remove(plugin);
        if (taskIds != null) {
            for (int taskId : taskIds) {
                TaskHandler<?> handler = this.taskHandlerMap.remove(taskId);
                if (handler != null) {
                    handler.cancel();
                }
            }
        }
        Set<ScheduledTask> timeTasks = this.timePluginTasks.remove(plugin);
        if (timeTasks != null) {
            for (ScheduledTask task : timeTasks) {
                task.cancel();
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

    /**
     * Starts the Velocity-style fluent time-based scheduling chain: the task runs on the
     * dedicated time-scheduler pool in real time, independent of the tick loop.
     *
     * @param owner the plugin owning the task; its tasks are cancelled when the plugin
     *              disables (null allowed for framework-internal timers)
     * @param task  the work to run; exceptions are logged, never allowed to kill the schedule
     */
    public TaskBuilder buildTask(Plugin owner, Runnable task) {
        if (task == null) {
            throw new SchedulerException("Attempted to schedule a null task!");
        }
        return new TaskBuilder(this, owner, task);
    }

    /** Unowned variant of {@link #buildTask(Plugin, Runnable)} for proxy-internal timers. */
    public TaskBuilder buildTask(Runnable task) {
        return this.buildTask(null, task);
    }

    /**
     * Materializes a TaskBuilder schedule. Repeating tasks use fixed-delay semantics: the
     * next repetition starts a full period after the previous one finished, so a slow task
     * backs up instead of overlapping itself.
     */
    ScheduledTask scheduleTimeTask(Plugin owner, Runnable task, long delayMillis, long repeatMillis) {
        if (delayMillis < 0 || repeatMillis < 0) {
            throw new SchedulerException("Attempted to register a time task with negative delay or repeat!");
        }
        ScheduledTask handle = new ScheduledTask(owner, task, this.timeTaskId.getAndIncrement());

        Runnable wrapped = () -> {
            if (handle.isCancelled()) {
                return;
            }
            try {
                task.run();
            } catch (Throwable t) {
                // A throwing task must not silently kill a repeating schedule (the
                // ScheduledThreadPoolExecutor swallows uncaught exceptions into the future).
                this.proxy.getLogger().error("Exception in scheduled time task #{} (owner={})",
                        handle.getTaskId(), owner == null ? "<none>" : owner.getName(), t);
            }
        };

        ScheduledFuture<?> future = repeatMillis > 0
                ? this.timeExecutor.scheduleWithFixedDelay(wrapped, delayMillis, repeatMillis, TimeUnit.MILLISECONDS)
                : this.timeExecutor.schedule(() -> {
                    try {
                        wrapped.run();
                    } finally {
                        this.detachTimeTask(handle);
                    }
                }, delayMillis, TimeUnit.MILLISECONDS);
        handle.attach(future);

        if (owner != null) {
            this.timePluginTasks.computeIfAbsent(owner, key -> ConcurrentHashMap.newKeySet()).add(handle);
        }
        return handle;
    }

    /**
     * Removes a finished or cancelled time task from its owner's registry. Package-private:
     * called from {@link ScheduledTask#cancel()} and from the one-shot completion wrapper.
     */
    void detachTimeTask(ScheduledTask task) {
        Plugin owner = task.getOwner();
        if (owner == null) {
            return;
        }
        Set<ScheduledTask> tasks = this.timePluginTasks.get(owner);
        if (tasks != null) {
            tasks.remove(task);
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
        this.timeExecutor.shutdown();

        int count = 25;
        while (count-- > 0) {
            try {
                boolean bothDone = this.threadedExecutor.awaitTermination(100, TimeUnit.MILLISECONDS);
                boolean timeDone = this.timeExecutor.awaitTermination(100, TimeUnit.MILLISECONDS);
                if (bothDone && timeDone) {
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
        this.timeExecutor.shutdownNow();
        // Release the task registries so queued-but-never-run handlers are not retained forever.
        this.taskHandlerMap.clear();
        this.assignedTasks.clear();
        this.pluginTasks.clear();
        this.timePluginTasks.clear();
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
