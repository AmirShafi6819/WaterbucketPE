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

package dev.waterbucket.waterbucketpe.event;

import dev.waterbucket.waterbucketpe.ProxyServer;
import dev.waterbucket.waterbucketpe.logger.MainLogger;
import dev.waterbucket.waterbucketpe.utils.exceptions.EventException;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;
import java.util.function.Consumer;

/**
 * Internal EventHandler Class
 * Manages Event Calling, priorities, execution ordering.
 * Should not be modified if not necessary
 */
public class EventHandler {

    private final EventManager eventManager;
    private final Class<? extends Event> eventClass;

    /*
     * Handlers are read on every event call, which happens concurrently on Netty event loops,
     * while plugins may subscribe (and resubscribe after reloads) at any time from other
     * threads. The previous EnumMap + ArrayList pair could throw ConcurrentModificationException
     * or drop a handler mid-iteration, so reads iterate an immutable snapshot and subscriptions
     * replace the whole map atomically.
     */
    private volatile Map<EventPriority, List<Consumer<Event>>> priority2handlers = Map.of();

    public EventHandler(Class<? extends Event> eventClass, EventManager eventManager) {
        this.eventClass = eventClass;
        this.eventManager = eventManager;
    }

    public <T extends Event>  CompletableFuture<T> handle(T event) {
        if (!this.eventClass.isInstance(event)) {
            throw new EventException("Tried to handle invalid event type!");
        }

        if (!event.isAsync()) {
            return this.handleSync(event);
        }

        CompletableFuture<T> future = new CompletableFuture<>();
        // Fast path: with no subscribed handlers an async event completes inline instead of
        // paying an executor queue round-trip. The join path fires several async events
        // (login, initial server determined, pre-connect), so a plugin-free proxy would
        // otherwise pay 3+ context switches per join for work that does nothing. Handlers
        // registered later still see the next event; the read is the same atomic snapshot
        // the dispatch loop uses, so no handler can be skipped.
        if (this.priority2handlers.isEmpty()) {
            event.completeFuture(future);
            return future;
        }
        Runnable dispatch = () -> {
            for (EventPriority priority : EventPriority.VALUES) {
                this.handlePriority(priority, event);
            }
            event.completeFuture(future);
        };
        ExecutorService executor = this.eventManager.getThreadedExecutor();
        try {
            CompletableFuture.runAsync(dispatch, executor).whenComplete((ignore, error) -> {
                if (error != null && !future.isDone()) {
                    future.completeExceptionally(error);
                    ProxyServer.getInstance().getLogger().error("Exception was thrown in event handler", error);
                }
            });
        } catch (RejectedExecutionException e) {
            // Late event while the proxy is shutting down: dispatch on the caller thread
            // instead of throwing out of callEvent and aborting the caller's cleanup path.
            try {
                dispatch.run();
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        }
        return future;
    }

    private <T extends Event> CompletableFuture<T> handleSync(T event) {
        if (!event.isCompletable()) {
            for (EventPriority priority : EventPriority.VALUES) {
                this.handlePriority(priority, event);
            }
            // Non-completable events does not provide future.
            return null;
        }

        try {
            for (EventPriority priority : EventPriority.VALUES) {
                this.handlePriority(priority, event);
            }
        } catch (Exception e) {
            return CompletableFuture.failedFuture(e);
        }

        if (event.getCompletableFutures().isEmpty()) {
            return CompletableFuture.completedFuture(event);
        }

        CompletableFuture<T> future = new CompletableFuture<>();
        event.completeFuture(future);
        return future;
    }

    private void handlePriority(EventPriority priority, Event event) {
        List<Consumer<Event>> handlerList = this.priority2handlers.get(priority);
        if (handlerList != null) {
            for (Consumer<Event> eventHandler : handlerList) {
                try {
                    eventHandler.accept(event);
                } catch (Throwable t) {
                    // One broken plugin handler must not break the caller or the remaining handlers.
                    MainLogger.getLogger().error("Exception was thrown in " + event.getClass().getSimpleName() + " handler", t);
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    public void subscribe(Consumer<Event> handler, EventPriority priority) {
        Map<EventPriority, List<Consumer<Event>>> current, next;
        do {
            current = this.priority2handlers;
            next = new EnumMap<>(EventPriority.class);
            for (Map.Entry<EventPriority, List<Consumer<Event>>> entry : current.entrySet()) {
                next.put(entry.getKey(), entry.getValue());
            }
            List<Consumer<Event>> handlerList = next.computeIfAbsent(priority, priority1 -> new ArrayList<>());
            // Check if the event is already registered
            if (!handlerList.contains(handler)) {
                // Handler is not registered yet
                List<Consumer<Event>> updated = new ArrayList<>(handlerList);
                updated.add(handler);
                next.put(priority, List.copyOf(updated));
            } else {
                next.put(priority, List.copyOf(handlerList));
            }
        } while (!HANDLERS_UPDATER.compareAndSet(this, current, next));
    }

    /**
     * Removes a previously subscribed handler. Matching is by handler identity (lambda
     * equality), mirroring {@link #subscribe(Consumer, EventPriority)} dedup semantics.
     * Unknown handlers are ignored.
     */
    public void unsubscribe(Consumer<Event> handler) {
        Map<EventPriority, List<Consumer<Event>>> current, next;
        do {
            current = this.priority2handlers;
            next = new EnumMap<>(EventPriority.class);
            boolean removed = false;
            for (Map.Entry<EventPriority, List<Consumer<Event>>> entry : current.entrySet()) {
                List<Consumer<Event>> filtered = new ArrayList<>(entry.getValue());
                removed |= filtered.removeIf(candidate -> candidate == handler);
                next.put(entry.getKey(), List.copyOf(filtered));
            }
            if (!removed) {
                return; // nothing to change; skip the CAS round-trip
            }
        } while (!HANDLERS_UPDATER.compareAndSet(this, current, next));
    }

    /**
     * Total number of currently subscribed handlers across all priorities; diagnostics and
     * tests use it to verify listener cleanup.
     */
    public int subscribedCount() {
        int total = 0;
        for (List<Consumer<Event>> list : this.priority2handlers.values()) {
            total += list.size();
        }
        return total;
    }

    private static final AtomicReferenceFieldUpdater<EventHandler, Map> HANDLERS_UPDATER =
            AtomicReferenceFieldUpdater.newUpdater(EventHandler.class, Map.class, "priority2handlers");
}
