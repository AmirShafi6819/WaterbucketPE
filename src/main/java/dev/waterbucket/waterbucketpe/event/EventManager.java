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
import dev.waterbucket.waterbucketpe.plugin.Plugin;
import dev.waterbucket.waterbucketpe.scheduler.WaterbucketScheduler;
import dev.waterbucket.waterbucketpe.utils.exceptions.EventException;
import lombok.Getter;
import lombok.extern.log4j.Log4j2;
import org.cloudburstmc.protocol.common.util.Preconditions;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * Event Manager
 * Enables Plugins to subscribe to Events, either vanilla events already implemented
 * or custom ones which are loaded as part of a plugin.
 */
@Log4j2
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
    /*
     * Annotation-listener registries (registerListener). Keyed by the listener OBJECT so a
     * plugin can detach one instance at runtime, plus an owner index so Plugin.setEnabled(false)
     * can detach every listener the plugin registered without plugins remembering to do it.
     * Both maps are written from plugin threads and read nowhere hot, so ConcurrentHashMap +
     * CopyOnWrite keep register/unregister race-free against each other.
     */
    private final ConcurrentMap<Object, List<RegisteredListener>> listenersByObject = new ConcurrentHashMap<>();
    private final ConcurrentMap<Plugin, Set<Object>> listenersByOwner = new ConcurrentHashMap<>();

    public EventManager(ProxyServer proxy) {
        this.proxy = proxy;
        // Bounded pool (see WaterbucketScheduler.buildBoundedExecutor): a fixed resident set
        // instead of the old unbounded-per-burst pool, so async event dispatch no longer
        // spawns and retires threads in bursts - the RAM/CPU sawtooth pattern.
        this.threadedExecutor = WaterbucketScheduler.buildBoundedExecutor("WaterbucketEvents Executor - #%d",
                this.proxy.getConfiguration().getIdleThreads());
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

    /**
     * Velocity-style alias for {@link #callEvent(Event)}: fires the event through the
     * subscriber pipeline and returns the pipeline future for async events.
     */
    public <T extends Event> CompletableFuture<T> fire(T event) {
        return this.callEvent(event);
    }

    /**
     * Registers every {@code @Subscribe}-annotated public method of {@code listener} for
     * event delivery, associating the registrations with {@code owner} so they are detached
     * automatically when the plugin is disabled. The declaration-style alternative to
     * {@link #subscribe(Class, Consumer, EventPriority)}.
     * <p>
     * Valid listener methods are public, non-static and take exactly one parameter whose type
     * is {@link Event} or a subclass of it. Registering the same listener instance twice
     * without unregistering in between is rejected: double delivery on every event is almost
     * never intended and silently corrupting priority state for it would be worse.
     *
     * @param owner    the plugin registering the listener; ownership enables the automatic
     *                 detach on disable (null is accepted for framework-internal listeners)
     * @param listener the object carrying the {@code @Subscribe} methods
     */
    public void registerListener(Plugin owner, Object listener) {
        Preconditions.checkArgument(listener != null, "listener can not be null");
        Preconditions.checkArgument(!this.listenersByObject.containsKey(listener),
                "listener %s is already registered - unregister it before registering again", listener.getClass().getName());

        List<RegisteredListener> registrations = this.scanListenerMethods(listener);
        if (registrations.isEmpty()) {
            log.warn("Listener {} declares no usable @Subscribe methods - nothing registered",
                    listener.getClass().getName());
            return;
        }

        this.listenersByObject.put(listener, registrations);
        if (owner != null) {
            this.listenersByOwner.computeIfAbsent(owner, key -> ConcurrentHashMap.newKeySet()).add(listener);
        }
        log.debug("Registered {} event handler(s) from listener {} (owner={})",
                registrations.size(), listener.getClass().getName(), owner == null ? "<none>" : owner.getName());
    }

    /**
     * Unowned variant of {@link #registerListener(Plugin, Object)} for listeners the proxy
     * core or tests register without plugin ownership.
     */
    public void registerListener(Object listener) {
        this.registerListener(null, listener);
    }

    /**
     * Detaches every handler of the given listener instance that was registered through
     * {@code registerListener}. Unknown listeners are ignored, making this safe to call from
     * plugin cleanup paths that may run before registration ever happened.
     */
    public void unregisterListener(Object listener) {
        if (listener == null) {
            return;
        }
        List<RegisteredListener> registrations = this.listenersByObject.remove(listener);
        if (registrations == null) {
            return;
        }
        for (RegisteredListener registration : registrations) {
            EventHandler handler = this.handlerMap.get(registration.eventClass());
            if (handler != null) {
                handler.unsubscribe(registration.consumer());
            }
        }
        for (Set<Object> owned : this.listenersByOwner.values()) {
            owned.remove(listener);
        }
        log.debug("Unregistered listener {}", listener.getClass().getName());
    }

    /**
     * Detaches every listener registered by the given plugin. Called automatically from
     * {@code Plugin.setEnabled(false)}; a plugin only needs this itself when re-arranging
     * listeners at runtime. Unknown plugins are ignored.
     */
    public void unregisterListeners(Plugin owner) {
        if (owner == null) {
            return;
        }
        Set<Object> owned = this.listenersByOwner.remove(owner);
        if (owned == null) {
            return;
        }
        for (Object listener : owned) {
            this.unregisterListener(listener);
        }
    }

    /**
     * Scans {@code listener}'s class for {@code @Subscribe} methods and builds the wrapped
     * consumers. The wrapping reproduces the functional API contract exactly: per-handler
     * exception isolation happens in {@link EventHandler}, the {@code ignoreCancelled} guard
     * runs before the method body, and reflection failures surface as a loud log line naming
     * the listener instead of a stack trace pointing at JDK internals.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private List<RegisteredListener> scanListenerMethods(Object listener) {
        List<RegisteredListener> registrations = new ArrayList<>();
        for (Method method : listener.getClass().getMethods()) {
            Subscribe annotation = method.getAnnotation(Subscribe.class);
            if (annotation == null) {
                continue;
            }
            Class<?>[] parameters = method.getParameterTypes();
            if (parameters.length != 1 || !Event.class.isAssignableFrom(parameters[0])) {
                log.warn("Listener method {} {} is annotated with @Subscribe but does not take exactly one Event parameter - skipping",
                        listener.getClass().getName(), method.getName());
                continue;
            }
            if (java.lang.reflect.Modifier.isStatic(method.getModifiers())) {
                log.warn("Listener method {} {} is static - @Subscribe only supports instance methods - skipping",
                        listener.getClass().getName(), method.getName());
                continue;
            }

            @SuppressWarnings("unchecked")
            Class<? extends Event> eventClass = (Class<? extends Event>) parameters[0];
            try {
                method.setAccessible(true);
            } catch (Throwable t) {
                log.warn("Cannot make listener method {} {} accessible: {}",
                        listener.getClass().getName(), method.getName(), t.toString());
            }

            boolean ignoreCancelled = annotation.ignoreCancelled();
            EventPriority priority = annotation.priority() == null ? EventPriority.NORMAL : annotation.priority();
            Consumer<Event> consumer = event -> {
                if (ignoreCancelled && event instanceof CancellableEvent cancellable && cancellable.isCancelled()) {
                    return;
                }
                try {
                    method.invoke(listener, event);
                } catch (InvocationTargetException e) {
                    Throwable cause = e.getCause() == null ? e : e.getCause();
                    if (cause instanceof RuntimeException runtime) {
                        throw runtime;
                    }
                    if (cause instanceof Error error) {
                        throw error;
                    }
                    throw new IllegalStateException(cause);
                } catch (IllegalAccessException e) {
                    log.error("Listener method {} {} became inaccessible after registration",
                            listener.getClass().getName(), method.getName(), e);
                }
            };
            // Raw on purpose: the parameter type was verified assignable to Event above, so
            // the consumer only ever receives instances of that event class. The generic
            // signature of subscribe() cannot express Class<? extends Event> + Consumer<Event>.
            this.subscribe((Class) eventClass, (Consumer) consumer, priority);
            registrations.add(new RegisteredListener(eventClass, consumer));
        }
        return registrations;
    }

    /**
     * One method-level registration created by {@link #scanListenerMethods(Object)}: the
     * event class the consumer was subscribed under, and the exact consumer instance, which
     * {@link EventHandler#unsubscribe(Consumer)} matches by identity.
     */
    private record RegisteredListener(Class<? extends Event> eventClass, Consumer<Event> consumer) {
    }

}
