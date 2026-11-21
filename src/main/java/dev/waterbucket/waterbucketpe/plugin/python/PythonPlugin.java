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

package dev.waterbucket.waterbucketpe.plugin.python;

import dev.waterbucket.waterbucketpe.ProxyServer;
import dev.waterbucket.waterbucketpe.plugin.Plugin;
import dev.waterbucket.waterbucketpe.plugin.PluginYAML;
import lombok.extern.log4j.Log4j2;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Value;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Bridges the Waterbucket plugin API into an embedded Python interpreter (GraalVM Polyglot).
 * <p>
 * A Python plugin is a folder under {@code plugins/} containing a {@code waterbucket.yml}
 * (or {@code plugin.yml}) descriptor whose {@code main} points at a {@code .py} file, plus the
 * script itself. The script may define the optional functions {@code on_startup(plugin)},
 * {@code on_enable(plugin)} and {@code on_disable(plugin)}; everything else (events, commands,
 * scheduling) goes through the {@code plugin} object passed to those functions, which is this
 * class, so the full Java API of {@link Plugin} and {@link ProxyServer} stays reachable through
 * the {@code import java} interop module as well.
 * <p>
 * GraalPy declares itself thread-safe: the context serializes execution internally while
 * allowing entry from any thread, so handlers run on the calling thread exactly like Java
 * plugin handlers and sync event semantics (cancellation before the pipeline continues) are
 * preserved. Long-running Python work should still be scheduled with
 * {@link #scheduleAsync(Runnable)} so event loops never wait on it.
 */
@Log4j2
public class PythonPlugin extends Plugin {

    /**
     * One live event subscription of this plugin: the Java event class plus the exact wrapped
     * consumer instance that was registered with the event manager, so it can be detached on
     * disable.
     */
    private record Subscription(Class<? extends dev.waterbucket.waterbucketpe.event.Event> eventClass,
                                Consumer<dev.waterbucket.waterbucketpe.event.Event> handler) {
    }

    private final ProxyServer proxy;
    private volatile Context context;
    private volatile Value module;
    private final List<Subscription> eventSubscriptions = Collections.synchronizedList(new ArrayList<>());
    private final List<String> registeredCommands = Collections.synchronizedList(new ArrayList<>());

    public PythonPlugin(ProxyServer proxy) {
        this.proxy = proxy;
    }

    /**
     * Package-private bridge for {@link PythonPluginLoader}: {@link Plugin#init} is protected.
     */
    void initPython(PluginYAML description, ProxyServer proxy, File pluginFolder) {
        this.init(description, proxy, pluginFolder);
    }

    /**
     * Loads and evaluates the plugin's main script. The script runs with {@code plugin},
     * {@code proxy} and {@code logger} already bound as globals; sibling {@code .py} files of
     * the plugin folder become importable modules.
     */
    public boolean loadScript() {
        try {
            this.context = Context.newBuilder("python")
                    .allowAllAccess(true)
                    // Exposes the `import java` module so scripts can reach any Java API
                    .option("python.EmulateJython", "true")
                    // On a standard JDK the interpreter runs without JVMCI; the warning is noise
                    // for plugins (see docs/PLUGINS.md for the GraalVM-JDK JIT note)
                    .option("engine.WarnInterpreterOnly", "false")
                    .build();

            Value bindings = this.context.getBindings("python");
            bindings.putMember("plugin", this);
            bindings.putMember("proxy", this.proxy);
            bindings.putMember("logger", this.getLogger());
            // The plugin folder joins sys.path through a bindings member: the previous source-
            // text interpolation broke on paths containing quotes or backslashes.
            bindings.putMember("_wb_plugin_dir", this.getPluginFile().getAbsolutePath());
            this.context.eval("python", "import sys as _wb_sys\nif _wb_plugin_dir not in _wb_sys.path:\n    _wb_sys.path.insert(0, _wb_plugin_dir)");

            Path main = Path.of(this.getPluginFile().getAbsolutePath(), this.getDescription().getMain());
            if (!dev.waterbucket.waterbucketpe.plugin.PluginLoader.isInsideDirectory(
                    Path.of(this.getPluginFile().getAbsolutePath()), main)) {
                this.getLogger().error("Python plugin {} declares main '{}' which escapes its plugin folder",
                        this.getName(), this.getDescription().getMain());
                this.closeContext();
                return false;
            }
            this.module = this.context.eval("python", Files.readString(main));
            return true;
        } catch (Exception e) {
            this.getLogger().error("Failed to load Python plugin {}!", this.getName(), e);
            this.closeContext();
            return false;
        }
    }

    /**
     * Invokes an optional Python function of the module (no-op when the script does not define it).
     */
    public void callHook(String function, Object... args) {
        Value hook = this.module == null ? null : this.module.getMember(function);
        if (hook == null || !hook.canExecute()) {
            // A silently missing hook is a classic "wrong module object" symptom; make it visible
            this.getLogger().warn("Python plugin {}: hook {} not found (module={})", this.getName(), function,
                    this.module == null ? "null" : this.module.hasMembers() ? "members:" + String.join(",",
                            this.module.getMemberKeys().stream().limit(20).toList()) : "opaque");
            return;
        }
        try {
            hook.execute(args);
        } catch (Throwable t) {
            this.getLogger().error("Python plugin {} failed in {}", this.getName(), function, t);
        }
    }

    @Override
    public void onStartup() {
        // Matches the Java plugin flow: the script loads at load time, before onEnable. If a
        // plugin was enabled without onStartup (edge case), onEnable loads it as a fallback.
        if (this.module == null && !this.loadScript()) {
            throw new IllegalStateException("Python script of " + this.getName() + " failed to load");
        }
        this.callHook("on_startup", this);
    }

    @Override
    public void onEnable() {
        if (this.module == null && !this.loadScript()) {
            throw new IllegalStateException("Python script of " + this.getName() + " failed to load");
        }
        this.callHook("on_enable", this);
    }

    @Override
    public void onDisable() {
        this.callHook("on_disable", this);
        // Detach everything that points into the interpreter BEFORE closing it. Handlers and
        // commands left behind used to be invoked after the context was closed - every later
        // event dispatched a PolyglotException into a dead context, and re-enabling duplicated
        // handlers behind commands that could no longer be re-registered.
        this.detachEventSubscriptions();
        this.unregisterRegisteredCommands();
        this.closeContext();
    }

    private void detachEventSubscriptions() {
        List<Subscription> subscriptions;
        synchronized (this.eventSubscriptions) {
            subscriptions = new ArrayList<>(this.eventSubscriptions);
            this.eventSubscriptions.clear();
        }
        for (Subscription subscription : subscriptions) {
            this.detachSubscription(subscription.eventClass(), subscription.handler());
        }
    }

    private <T extends dev.waterbucket.waterbucketpe.event.Event> void detachSubscription(
            Class<T> eventClass, Consumer<dev.waterbucket.waterbucketpe.event.Event> handler) {
        // Safe: the wrapped consumer was registered for exactly this event class
        @SuppressWarnings("unchecked")
        Consumer<T> typed = (Consumer<T>) handler;
        this.proxy.getEventManager().unsubscribe(eventClass, typed);
    }

    private void unregisterRegisteredCommands() {
        List<String> names;
        synchronized (this.registeredCommands) {
            names = new ArrayList<>(this.registeredCommands);
            this.registeredCommands.clear();
        }
        for (String name : names) {
            this.proxy.getCommandMap().unregisterCommand(name);
        }
    }

    private void closeContext() {
        Context ctx = this.context;
        this.context = null;
        this.module = null;
        if (ctx != null) {
            // interruptRunning cancels a stuck script instead of blocking the shutdown
            ctx.close(true);
        }
    }

    /**
     * Subscribes a Python callable to a proxy event. Synchronous handlers run inline like Java
     * handlers; async events receive a future so the pipeline waits for the Python handler.
     * <p>
     * A throwing handler behaves exactly like a throwing Java handler: the error is logged and
     * dispatch continues. It never fails the event pipeline - failing the completable future
     * used to surface as a disconnect for the player whose login/transfer event the broken
     * plugin subscribed to.
     * <p>
     * All subscriptions are detached automatically in {@link #onDisable()}.
     *
     * @param eventClass Java event class, obtained e.g. via {@code java.type("...")}
     * @param handler    Python function receiving the event object
     */
    @SuppressWarnings("unchecked")
    public <T extends dev.waterbucket.waterbucketpe.event.Event> void subscribe(Class<T> eventClass, Consumer<? super T> handler) {
        Consumer<T> wrapped = event -> {
            try {
                if (event.isCompletable()) {
                    CompletableFuture<Void> done = new CompletableFuture<>();
                    event.addCompletableFuture(done);
                    try {
                        handler.accept(event);
                    } finally {
                        done.complete(null);
                    }
                } else {
                    handler.accept(event);
                }
            } catch (Throwable t) {
                this.getLogger().error("Python plugin {} threw in an event handler", this.getName(), t);
            }
        };
        this.proxy.getEventManager().subscribe(eventClass, wrapped);
        this.eventSubscriptions.add(new Subscription(eventClass, (Consumer<dev.waterbucket.waterbucketpe.event.Event>) wrapped));
    }

    /**
     * Registers a console/player command whose executor is a Python callable.
     *
     * @param name       command name, e.g. {@code hello}
     * @param description short description shown in /help
     * @param usage      usage message
     * @param aliases    additional names, may be null
     * @param permission permission required to run, may be null
     * @param handler    Python callable {@code handler(sender, alias, args) -> bool}
     * @return false when a command with that name already exists
     */
    public boolean registerCommand(String name, String description, String usage, List<String> aliases,
                                   String permission, PythonCommandExecutor handler) {
        PythonCommand command = new PythonCommand(this, name, description, usage, aliases, permission, handler);
        if (this.proxy.getCommandMap().registerCommand(command)) {
            this.registeredCommands.add(command.getName());
            return true;
        }
        return false;
    }

    /**
     * Python-style alias for {@link #registerCommand}.
     */
    public boolean register_command(String name, String description, String usage, List<String> aliases,
                                   String permission, PythonCommandExecutor handler) {
        return this.registerCommand(name, description, usage, aliases, permission, handler);
    }

    /**
     * Runs a Python callable on the proxy scheduler as an async task. Use this for slow work
     * (HTTP, file IO) so event loops never wait on the interpreter.
     */
    public void scheduleAsync(Runnable task) {
        this.proxy.getScheduler().scheduleAsync(() -> {
            try {
                task.run();
            } catch (Throwable t) {
                this.getLogger().error("Python plugin {} task failed", this.getName(), t);
            }
        });
    }

    /**
     * Runs a Python callable on the proxy scheduler after the given delay (ticks).
     */
    public void scheduleDelayed(Runnable task, int delayTicks) {
        this.proxy.getScheduler().scheduleDelayed(() -> {
            try {
                task.run();
            } catch (Throwable t) {
                this.getLogger().error("Python plugin {} task failed", this.getName(), t);
            }
        }, delayTicks, true);
    }

    /**
     * Runs a Python callable on the proxy scheduler every {@code periodTicks} ticks (async).
     */
    public void scheduleRepeating(Runnable task, int periodTicks) {
        this.proxy.getScheduler().scheduleRepeating(() -> {
            try {
                task.run();
            } catch (Throwable t) {
                this.getLogger().error("Python plugin {} task failed", this.getName(), t);
            }
        }, periodTicks, true);
    }

    /**
     * Python-style aliases for the scheduling helpers.
     */
    public void schedule_async(Runnable task) {
        this.scheduleAsync(task);
    }

    public void schedule_delayed(Runnable task, int delayTicks) {
        this.scheduleDelayed(task, delayTicks);
    }

    public void schedule_repeating(Runnable task, int periodTicks) {
        this.scheduleRepeating(task, periodTicks);
    }

    public ProxyServer getProxy() {
        return this.proxy;
    }

    /**
     * The loaded Python module; used by tools and tests to invoke script functions directly.
     */
    public Value getModule() {
        return this.module;
    }

    @Override
    public java.io.InputStream getResourceFile(String filename) {
        // Python plugins are folders, not jars: resources live next to the main script.
        // Normalize and confine the path so '..' entries cannot read outside the folder.
        try {
            Path root = Path.of(this.getPluginFile().getAbsolutePath());
            Path path = root.resolve(filename);
            if (!dev.waterbucket.waterbucketpe.plugin.PluginLoader.isInsideDirectory(root, path)) {
                this.getLogger().warn("Resource {} escapes the plugin folder of {}", filename, this.getName());
                return null;
            }
            if (Files.exists(path)) {
                return Files.newInputStream(path);
            }
        } catch (Exception e) {
            this.proxy.getLogger().error("Can not get plugin resource!", e);
        }
        return null;
    }
}
