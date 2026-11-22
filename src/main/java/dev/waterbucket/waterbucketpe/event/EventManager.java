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
import dev.waterbucket.waterbucketpe.utils.ThreadFactoryBuilder;
import lombok.Getter;
import org.cloudburstmc.protocol.common.util.Preconditions;

import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * Event Manager
 * Enables Plugins to subscribe to Events, either vanilla events already implemented
 * or custom ones which are loaded as part of a plugin.
 */
public class EventManager {

    private final ProxyServer proxy;
    @Getter
    private final ExecutorService threadedExecutor;
    /*
     * Events are fired from every Netty event loop while plugins subscribe from their own
     * threads, so this map must be concurrent. It was a fastutil hash map before, which is
     * not thread-safe and could corrupt entries or lose subscriptions under load.
     */
    private final ConcurrentHashMap<Class<? extends Event>, EventHandler> handlerMap = new ConcurrentHashMap<>();

    public EventManager(ProxyServer proxy) {
        this.proxy = proxy;
        ThreadFactoryBuilder builder = ThreadFactoryBuilder.builder()
                .format("WaterbucketEvents Executor - #%d")
                // Internal pool: must not keep the JVM alive on its own, matching the other
                // proxy executors.
                .daemon(true)
                .build();
        int idleThreads = this.proxy.getConfiguration().getIdleThreads();
        this.threadedExecutor = new ThreadPoolExecutor(idleThreads, Integer.MAX_VALUE, 60, TimeUnit.SECONDS, new SynchronousQueue<>(true), builder);
    }

    public <T extends Event> void subscribe(Class<T> event, Consumer<T> handler) {
        this.subscribe(event, handler, EventPriority.NORMAL);
    }

    /**
     * Can be used to subscribe to events. Once subscribed, the handler will be called each time the event is called.
     *
     * @param event    A class reference to the target event you want to subscribe to, for example ProxyPingEvent.class
     * @param handler  A method reference or lambda with one parameter, the event which you want to handle
     * @param priority The Priority of your event handler. Can be used to execute one handler after / before another
     * @param <T>      The class reference to the event you want to subscribe to
     * @see AsyncEvent
     * @see EventPriority
     */
    public <T extends Event> void subscribe(Class<T> event, Consumer<T> handler, EventPriority priority) {
        // Clear contract-violation messages instead of a deep NullPointerException from map
        // internals when a plugin passes null arguments.
        Preconditions.checkArgument(event != null, "event class can not be null");
        Preconditions.checkArgument(handler != null, "handler can not be null");
        Preconditions.checkArgument(priority != null, "priority can not be null");
        EventHandler eventHandler = this.handlerMap.computeIfAbsent(event, e -> new EventHandler(event, this));
        eventHandler.subscribe((Consumer<Event>) handler, priority);
    }

    /**
     * Removes a handler that was registered with {@link #subscribe(Class, Consumer, EventPriority)}.
     * Matching is by identity, so plugins should keep the lambda reference they subscribed.
     * Calling it for an unknown handler or an event class without subscribers is a no-op.
     * <p>
     * Plugins should unsubscribe in {@code onDisable}: handlers left behind keep firing after
     * the plugin was disabled, and the Python front-end depends on this to detach its handlers
     * before the interpreter context is closed.
     *
     * @param event   the event class the handler was registered for
     * @param handler the exact consumer instance passed to subscribe
     * @param <T>     the event type
     */
    @SuppressWarnings("unchecked")
    public <T extends Event> void unsubscribe(Class<T> event, Consumer<T> handler) {
        EventHandler eventHandler = this.handlerMap.get(event);
        if (eventHandler != null) {
            eventHandler.unsubscribe((Consumer<Event>) handler);
        }
    }

    /**
     * Used to call an provided event.
     * If the target event has the annotation AsyncEvent present, the CompletableFuture.whenComplete can be used to
     * execute code once the event has passed the whole event pipeline. If the annotation is not present, you can
     * ignore the return and use the direct variable reference of your event
     *
     * @param event the instance of an event to be called
     * @return CompletableFuture<Event> if event has AsyncEvent annotation present or null in case of non-async event
     */
    public <T extends Event> CompletableFuture<T> callEvent(T event) {
        Preconditions.checkArgument(event != null, "event can not be null");
        EventHandler eventHandler = this.handlerMap.computeIfAbsent(event.getClass(), e -> new EventHandler(event.getClass(), this));
        return eventHandler.handle(event);
    }

    /**
     * Package-private pipeline accessor for tests and diagnostics.
     */
    EventHandler handlerFor(Class<? extends Event> event) {
        return this.handlerMap.get(event);
    }

    /**
     * Number of handlers currently subscribed to the given event class, 0 when none.
     * Diagnostics use: verifying listener cleanup after a plugin was disabled.
     */
    public int subscriberCount(Class<? extends Event> event) {
        EventHandler eventHandler = this.handlerMap.get(event);
        return eventHandler == null ? 0 : eventHandler.subscribedCount();
    }

}
