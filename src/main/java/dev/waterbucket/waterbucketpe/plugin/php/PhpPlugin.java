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

package dev.waterbucket.waterbucketpe.plugin.php;

import dev.waterbucket.waterbucketpe.ProxyServer;
import dev.waterbucket.waterbucketpe.plugin.Plugin;
import dev.waterbucket.waterbucketpe.plugin.PluginLoader;
import dev.waterbucket.waterbucketpe.plugin.PluginYAML;
import dev.waterbucket.waterbucketpe.plugin.compat.CompatApi;
import dev.waterbucket.waterbucketpe.scheduler.TaskHandler;
import com.caucho.quercus.QuercusContext;
import com.caucho.quercus.env.ArrayValue;
import com.caucho.quercus.env.ArrayValueImpl;
import com.caucho.quercus.env.Callback;
import com.caucho.quercus.env.Env;
import com.caucho.quercus.env.Value;
import com.caucho.quercus.program.QuercusProgram;
import com.caucho.quercus.script.QuercusScriptEngine;
import com.caucho.quercus.script.QuercusScriptEngineFactory;
import com.caucho.vfs.NullWriteStream;
import lombok.extern.log4j.Log4j2;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Bridges the Waterbucket plugin API into an embedded PHP interpreter (Quercus, PHP-on-JVM).
 * <p>
 * A PHP plugin is a folder under {@code plugins/} containing a {@code waterbucket.yml}
 * (or {@code plugin.yml}) descriptor whose {@code main} points at a {@code .php} file, plus the
 * script itself. The script may define the optional functions {@code on_startup($plugin)},
 * {@code on_enable($plugin)} and {@code on_disable($plugin)}; everything else (events, commands,
 * scheduling) goes through the {@code $plugin} object passed to those functions, which is this
 * class, so the full Java API of {@link Plugin} and {@link ProxyServer} stays reachable through
 * Quercus' native Java interop.
 * <p>
 * Architecture (all verified against the bundled Quercus 4.0.65 runtime):
 * <ul>
 *     <li>One {@link Env} per plugin, created through Quercus' full constructor so the
 *     function/class/constant lookup tables are initialized; the JSR-223 engine's per-eval
 *     throwaway environments lose state between calls, so it is not used after boot.</li>
 *     <li>State persists inside that one environment: functions defined by the main script stay
 *     callable for the whole plugin lifetime, which is what the lifecycle hooks dispatch on.</li>
 *     <li>PHP closures marshal natively into {@link Callback} when passed to Java methods, so
 *     subscriptions, commands and tasks keep a direct handle to the PHP code and are invoked
 *     with the persistent environment. Nothing is re-evaluated per dispatch.</li>
 *     <li>All PHP execution is serialized per plugin with a lock ({@link #phpLock}) and Quercus'
 *     thread-local current-environment is set for the duration of each dispatch. A handler that
 *     blocks only stalls that plugin's work, never the proxy.</li>
 * </ul>
 */
@Log4j2
public class PhpPlugin extends Plugin {

    private final ProxyServer proxy;
    private QuercusScriptEngine engine;
    private Env env;
    private QuercusProgram hookProgram;

    /**
     * Serializes registration (subscribe/registerCommand/schedule*) against disable, exactly
     * like the Python bridge: the check-then-act window between "context alive?" and the actual
     * registration must be closed, or a handler registered concurrently with onDisable would
     * fire into a dead interpreter forever.
     */
    private final Object lifecycleLock = new Object();
    private volatile boolean disabling;
    /**
     * Quercus environments are not thread-safe: every PHP execution of this plugin holds this
     * lock, so concurrent events serialize instead of corrupting shared state.
     */
    private final ReentrantLock phpLock = new ReentrantLock();

    private final List<Subscription> eventSubscriptions = Collections.synchronizedList(new ArrayList<>());
    private final List<String> registeredCommands = Collections.synchronizedList(new ArrayList<>());
    private final List<TaskHandler<?>> scheduledTasks = Collections.synchronizedList(new ArrayList<>());
    /**
     * Diagnostics: the lifecycle hook that last completed without throwing. Operators can
     * match a "hook not found" or "failed in" log line against it; tests assert on it because
     * the interpreter is closed by the time a disable sequence returns.
     */
    private volatile String lastCompletedHook;

    public PhpPlugin(ProxyServer proxy) {
        this.proxy = proxy;
    }

    /**
     * Package-private bridge for {@link PhpPluginLoader}: {@link Plugin#init} is protected.
     */
    void initPhp(PluginYAML description, ProxyServer proxy, File pluginFolder) {
        this.init(description, proxy, pluginFolder);
    }

    /**
     * Evaluates the plugin's main script into the persistent environment. The script runs with
     * {@code $plugin}, {@code $proxy} and {@code $logger} bound as globals; the lifecycle hook
     * registry is set up first so {@code on_startup}/{@code on_enable}/{@code on_disable}
     * functions defined by the script are dispatched automatically.
     *
     * @return true when the script compiled and ran
     */
    public boolean loadScript() {
        try {
            this.engine = (QuercusScriptEngine) new QuercusScriptEngineFactory().getScriptEngine();
            QuercusContext quercus = this.engine.getQuercus();
            // The full constructor initializes the function/class/constant lookup tables; the
            // JSR-223 engine's internal throwaway environments skip them for speed.
            this.env = new Env(quercus, null, new NullWriteStream(), null, null);
            this.env.setGlobalValue("plugin", this.env.wrapJava(this));
            this.env.setGlobalValue("proxy", this.env.wrapJava(this.proxy));
            this.env.setGlobalValue("logger", this.env.wrapJava(this.getLogger()));
            // The compatibility plugin surface: $compat->getServer(), ->playerJoinEvent(), ... like
            // the Java package exposes them.
            this.env.setGlobalValue("compat", this.env.wrapJava(new CompatApi()));

            // Registry, then the plugin script itself, then the lifecycle scan - the scan must
            // run after the script so function_exists sees what the script defined.
            this.execute(this.compile(PhpRuntime.BOOTSTRAP));
            Path main = Path.of(this.getPluginFile().getAbsolutePath(), this.getDescription().getMain());
            if (!PluginLoader.isInsideDirectory(Path.of(this.getPluginFile().getAbsolutePath()), main)) {
                this.getLogger().error("PHP plugin {} declares main '{}' which escapes its plugin folder",
                        this.getName(), this.getDescription().getMain());
                this.closeEngine();
                return false;
            }
            if (!Files.isRegularFile(main)) {
                this.getLogger().error("PHP plugin {} declares main '{}' which does not exist in {}",
                        this.getName(), this.getDescription().getMain(), this.getPluginFile().getName());
                this.closeEngine();
                return false;
            }
            String source = this.readScript(main);
            if (source == null) {
                this.closeEngine();
                return false;
            }
            this.execute(this.compile(source));
            this.execute(this.compile(PhpRuntime.LIFECYCLE_SCAN));
            // Hook dispatch program: call_user_func_array over the PHP-side hook registry
            this.hookProgram = this.compile(PhpRuntime.DISPATCH_HOOK);
            return true;
        } catch (Throwable t) {
            this.getLogger().error("Failed to load PHP plugin {}!", this.getName(), t);
            this.closeEngine();
            return false;
        }
    }

    /**
     * Strips the opening PHP tag (and any UTF-8 BOM) so the file content can be compiled as
     * plain PHP code. Quercus {@code parseCode} expects raw statements, while plugin authors
     * naturally write {@code <?php} at the top of main.php.
     */
    private String readScript(Path main) {
        try {
            String content = Files.readString(main, StandardCharsets.UTF_8);
            if (content.startsWith("\uFEFF")) {
                content = content.substring(1);
            }
            String trimmed = content.stripLeading();
            if (trimmed.startsWith("<?php")) {
                content = trimmed.substring("<?php".length());
            } else if (trimmed.startsWith("<?=")) {
                // echo tags are not valid plugin code; reject with a clear message
                this.getLogger().error("PHP plugin {} main file must start with '<?php'", this.getName());
                return null;
            } else if (!trimmed.isEmpty()) {
                // No tag at all is also accepted: Quercus treats it as plain PHP code
                this.getLogger().debug("PHP plugin {} main file has no '<?php' opening tag; evaluating as raw PHP", this.getName());
            }
            return content;
        } catch (Exception e) {
            this.getLogger().error("Cannot read PHP plugin main file of {}", this.getName(), e);
            return null;
        }
    }

    private QuercusProgram compile(String source) throws Throwable {
        return this.engine.getQuercus().parseCode(
                (com.caucho.quercus.env.StringValue) com.caucho.quercus.env.StringValue.create(source));
    }

    /**
     * Invokes an optional lifecycle hook of the plugin (no-op when the script did not define
     * it). The registry lookup happens in PHP land, so a missing hook is a cheap empty map
     * access instead of an error.
     */
    public void callHook(String hook) {
        if (this.hookProgram == null) {
            return;
        }
        try {
            this.env.setGlobalValue("_WB_NAME", com.caucho.quercus.env.StringValue.create(hook));
            ArrayValue args = new ArrayValueImpl();
            args.append(this.env.wrapJava(this));
            this.env.setGlobalValue("_WB_ARGS", args);
            this.execute(this.hookProgram);
            this.lastCompletedHook = hook;
        } catch (Throwable t) {
            this.getLogger().error("PHP plugin {} failed in {}", this.getName(), hook, t);
        }
    }

    /**
     * The lifecycle hook that last completed without throwing; null before the first hook.
     */
    public String getLastCompletedHook() {
        return this.lastCompletedHook;
    }

    @Override
    public void onStartup() {
        // Matches the Java plugin flow: the script loads at load time, before onEnable. If a
        // plugin was enabled without onStartup (edge case), onEnable loads it as a fallback.
        if (this.env == null && !this.loadScript()) {
            throw new IllegalStateException("PHP script of " + this.getName() + " failed to load");
        }
        this.callHook("on_startup");
    }

    @Override
    public void onEnable() {
        if (this.env == null && !this.loadScript()) {
            throw new IllegalStateException("PHP script of " + this.getName() + " failed to load");
        }
        this.callHook("on_enable");
    }

    @Override
    public void onDisable() {
        // Publish the disabling flag before any detach work: see lifecycleLock.
        synchronized (this.lifecycleLock) {
            this.disabling = true;
        }
        this.callHook("on_disable");
        // Stop scheduler-driven work first, then detach everything that points into the
        // interpreter BEFORE closing it - the same ordering contract as the Python bridge.
        this.cancelScheduledTasks();
        this.detachEventSubscriptions();
        this.unregisterRegisteredCommands();
        this.closeEngine();
    }

    private void closeEngine() {
        this.env = null;
        this.hookProgram = null;
        QuercusScriptEngine engine = this.engine;
        this.engine = null;
        if (engine != null) {
            try {
                engine.close();
            } catch (Throwable t) {
                this.getLogger().debug("PHP engine close failed for {}", this.getName(), t);
            }
        }
    }

    /**
     * Subscribes a PHP callable to a proxy event. Synchronous handlers run inline like Java
     * handlers; async (completable) events receive a future so the pipeline waits for the PHP
     * handler. All subscriptions are detached automatically in {@link #onDisable()}.
     * <p>
     * The event class is resolved by simple name ({@code PlayerChatEvent}), fully qualified
     * name, or from any plugin's class loader, so plugin-defined events work too.
     *
     * @param eventClass event class name, e.g. {@code PlayerChatEvent}
     * @param handler    PHP callable receiving the event object
     */
    @SuppressWarnings("unchecked")
    public <T extends dev.waterbucket.waterbucketpe.event.Event> void subscribe(String eventClass, Callback handler) {
        if (eventClass == null || eventClass.isBlank()) {
            this.getLogger().warn("PHP plugin {} subscribed with an empty event class name", this.getName());
            return;
        }
        if (handler == null) {
            this.getLogger().warn("PHP plugin {} subscribed to {} with a null handler", this.getName(), eventClass);
            return;
        }
        synchronized (this.lifecycleLock) {
            if (this.env == null || this.disabling) {
                this.getLogger().warn("PHP plugin {} is not enabled; ignoring subscribe request for {}",
                        this.getName(), eventClass);
                return;
            }
            Class<T> resolved = (Class<T>) this.resolveEventClass(eventClass);
            if (resolved == null) {
                this.getLogger().error("PHP plugin {} subscribed to unknown event '{}'", this.getName(), eventClass);
                return;
            }
            Consumer<T> wrapped = (Consumer<T>) this.wrapHandler(handler);
            this.proxy.getEventManager().subscribe(resolved, wrapped);
            this.eventSubscriptions.add(new Subscription(resolved, (Consumer<dev.waterbucket.waterbucketpe.event.Event>) wrapped));
        }
    }

    /**
     * Resolves an event class by simple name, FQCN, or any loaded plugin's class loader.
     */
    @SuppressWarnings("unchecked")
    private Class<? extends dev.waterbucket.waterbucketpe.event.Event> resolveEventClass(String name) {
        String candidate = name;
        if (name.indexOf('.') < 0) {
            // Simple names resolve against the proxy's own events first, then the compatibility
            // events, so scripts can subscribe with the short form in both worlds.
            candidate = "dev.waterbucket.waterbucketpe.event.defaults." + name;
        }
        Class<?> resolved = null;
        try {
            resolved = Class.forName(candidate);
        } catch (ClassNotFoundException ignored) {
            // Fall through to the compatibility package and the plugin class loader scan
        }
        if (resolved == null && name.indexOf('.') < 0) {
            try {
                resolved = Class.forName("dev.waterbucket.waterbucketpe.plugin.compat.event." + name);
            } catch (ClassNotFoundException ignored) {
                // Fall through to the plugin class loader scan
            }
        }
        if (resolved == null && this.proxy.getPluginManager() != null) {
            resolved = this.proxy.getPluginManager().getClassFromCache(name);
        }
        if (resolved == null || !dev.waterbucket.waterbucketpe.event.Event.class.isAssignableFrom(resolved)) {
            return null;
        }
        return (Class<? extends dev.waterbucket.waterbucketpe.event.Event>) resolved;
    }

    /**
     * Wraps a PHP callback into a Java event consumer with the pipeline-safety contract of the
     * Java and Python bridges: handler errors are logged and swallowed, completable events are
     * completed even when the handler throws, so a broken plugin can never disconnect players.
     */
    private Consumer<dev.waterbucket.waterbucketpe.event.Event> wrapHandler(Callback handler) {
        return event -> {
            try {
                if (event.isCompletable()) {
                    CompletableFuture<Void> done = new CompletableFuture<>();
                    event.addCompletableFuture(done);
                    try {
                        this.invokeCallback(handler, event);
                    } finally {
                        done.complete(null);
                    }
                } else {
                    this.invokeCallback(handler, event);
                }
            } catch (Throwable t) {
                this.getLogger().error("PHP plugin {} threw in an event handler", this.getName(), t);
            }
        };
    }

    /**
     * Registers a console/player command whose executor is a PHP callable
     * {@code handler($sender, $alias, $args) -> bool}.
     *
     * @return false when a command with that name already exists
     */
    public boolean registerCommand(String name, String description, String usage, List<String> aliases,
                                   String permission, Callback handler) {
        synchronized (this.lifecycleLock) {
            if (this.env == null || this.disabling) {
                this.getLogger().warn("PHP plugin {} is not enabled; ignoring command registration for /{}",
                        this.getName(), name);
                return false;
            }
            if (handler == null) {
                this.getLogger().warn("PHP plugin {} registered command /{} with a null handler", this.getName(), name);
                return false;
            }
            PhpCommand command = new PhpCommand(this, name, description, usage, aliases, permission, handler);
            if (this.proxy.getCommandMap().registerCommand(command)) {
                this.registeredCommands.add(command.getName());
                return true;
            }
            return false;
        }
    }

    /**
     * Invoked by {@link PhpCommand}: runs the command's PHP executor with the sender, alias and
     * argument array, converting the PHP return value into the Java command result.
     */
    boolean executeCommand(Callback handler, Object sender, String alias, String[] args) {
        try {
            Boolean result = this.<Boolean>invokeCallback(handler,
                    value -> value == null || value.toBoolean(), sender, alias, args);
            return result == null || result;
        } catch (Throwable t) {
            this.getLogger().error("PHP command failed in {}", this.getName(), t);
            return false;
        }
    }

    /**
     * Runs a PHP callable on the proxy scheduler as an async task. Use this for slow work
     * (HTTP, file IO) so event loops never wait on the interpreter. Scheduled tasks are
     * cancelled automatically when the plugin is disabled.
     */
    public void scheduleAsync(Callback task) {
        this.scheduleChecked(task, callback -> this.proxy.getScheduler().scheduleAsync(this.wrapTask(callback)));
    }

    /**
     * Runs a PHP callable on the proxy scheduler after the given delay (ticks). Scheduled
     * tasks are cancelled automatically when the plugin is disabled.
     */
    public void scheduleDelayed(Callback task, int delayTicks) {
        this.scheduleChecked(task, callback ->
                this.proxy.getScheduler().scheduleDelayed(this.wrapTask(callback), delayTicks, true));
    }

    /**
     * Runs a PHP callable on the proxy scheduler every {@code periodTicks} ticks (async).
     * Scheduled tasks are cancelled automatically when the plugin is disabled.
     */
    public void scheduleRepeating(Callback task, int periodTicks) {
        this.scheduleChecked(task, callback ->
                this.proxy.getScheduler().scheduleRepeating(this.wrapTask(callback), periodTicks, true));
    }

    private void scheduleChecked(Callback task, Function<Callback, TaskHandler<?>> scheduler) {
        synchronized (this.lifecycleLock) {
            if (this.env == null || this.disabling) {
                this.getLogger().warn("PHP plugin {} is not enabled; ignoring schedule request", this.getName());
                return;
            }
            if (task == null) {
                this.getLogger().warn("PHP plugin {} scheduled a null task", this.getName());
                return;
            }
            TaskHandler<?> handler = scheduler.apply(task);
            if (handler != null) {
                this.scheduledTasks.add(handler);
            }
        }
    }

    private Runnable wrapTask(Callback task) {
        return () -> {
            try {
                this.invokeCallback(task);
            } catch (Throwable t) {
                this.getLogger().error("PHP plugin {} task failed", this.getName(), t);
            }
        };
    }

    /**
     * Invokes a stored PHP callback with Java arguments inside the persistent environment.
     * The arguments are converted on demand: plain objects wrap as Java values, string arrays
     * become PHP arrays. Returns null when the engine is already closed.
     */
    private Value invokeCallback(Callback callback, Object... arguments) throws Throwable {
        return this.invokeCallback(callback, null, arguments);
    }

    /**
     * Invokes a stored PHP callback and applies {@code converter} to the result while Quercus'
     * thread-local environment is still installed: value conversions read ini/error settings
     * through {@code Env.getInstance()} and crash outside an execution, so conversion and
     * invocation must share one region.
     */
    @SuppressWarnings("unchecked")
    private <R> R invokeCallback(Callback callback, java.util.function.Function<Value, R> converter,
                                 Object... arguments) throws Throwable {
        ReentrantLock lock = this.phpLock;
        lock.lock();
        try {
            Env env = this.env;
            if (env == null) {
                return null;
            }
            Value[] args;
            if (arguments == null || arguments.length == 0) {
                args = Value.NULL_ARGS;
            } else if (arguments.length == 1 && arguments[0] instanceof Value value) {
                args = new Value[]{value};
            } else {
                args = new Value[arguments.length];
                for (int i = 0; i < arguments.length; i++) {
                    args[i] = this.toValue(env, arguments[i]);
                }
            }
            Value result = PhpEngineAccess.execute(env, () -> callback.call(env, args));
            return converter == null ? null : converter.apply(result);
        } finally {
            lock.unlock();
        }
    }

    private Value toValue(Env env, Object argument) {
        if (argument instanceof Value value) {
            return value;
        }
        if (argument instanceof String[] strings) {
            ArrayValue array = new ArrayValueImpl();
            for (String element : strings) {
                array.append(com.caucho.quercus.env.StringValue.create(element));
            }
            return array;
        }
        return env.wrapJava(argument);
    }

    /**
     * Runs one compiled program inside the persistent environment, holding Quercus' thread-local
     * current environment for the duration (Quercus internals resolve it per thread).
     */
    private void execute(QuercusProgram program) throws Throwable {
        ReentrantLock lock = this.phpLock;
        lock.lock();
        try {
            Env env = this.env;
            if (env == null) {
                return;
            }
            PhpEngineAccess.execute(env, () -> program.execute(env));
        } finally {
            lock.unlock();
        }
    }

    private void cancelScheduledTasks() {
        List<TaskHandler<?>> tasks;
        synchronized (this.scheduledTasks) {
            tasks = new ArrayList<>(this.scheduledTasks);
            this.scheduledTasks.clear();
        }
        for (TaskHandler<?> task : tasks) {
            task.cancel();
        }
    }

    private void detachEventSubscriptions() {
        List<Subscription> subscriptions;
        synchronized (this.eventSubscriptions) {
            subscriptions = new ArrayList<>(this.eventSubscriptions);
            this.eventSubscriptions.clear();
        }
        for (Subscription subscription : subscriptions) {
            // Safe: the wrapped consumer was registered for exactly this event class
            detachSubscription(subscription.eventClass(), subscription.handler());
        }
    }

    private <T extends dev.waterbucket.waterbucketpe.event.Event> void detachSubscription(
            Class<T> eventClass, Consumer<dev.waterbucket.waterbucketpe.event.Event> handler) {
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

    public ProxyServer getProxy() {
        return this.proxy;
    }

    /**
     * The live PHP environment; used by tools and tests to inspect plugin state directly.
     */
    Env environment() {
        return this.env;
    }

    /**
     * Runs arbitrary compiled PHP in the plugin's environment. Test/diagnostic escape hatch:
     * production code paths use the compiled programs only.
     */
    Value eval(String phpCode) throws Throwable {
        ReentrantLock lock = this.phpLock;
        lock.lock();
        try {
            Env env = this.env;
            if (env == null) {
                return null;
            }
            QuercusProgram program = this.compile(phpCode);
            return PhpEngineAccess.execute(env, () -> program.execute(env));
        } finally {
            lock.unlock();
        }
    }

    /**
     * One live event subscription of this plugin: the Java event class plus the exact wrapped
     * consumer instance that was registered with the event manager, so it can be detached on
     * disable.
     */
    private record Subscription(Class<? extends dev.waterbucket.waterbucketpe.event.Event> eventClass,
                                Consumer<dev.waterbucket.waterbucketpe.event.Event> handler) {
    }

    @Override
    public java.io.InputStream getResourceFile(String filename) {
        // PHP plugins are folders, not jars: resources live next to the main script.
        // Normalize and confine the path so '..' entries cannot read outside the folder.
        try {
            Path root = Path.of(this.getPluginFile().getAbsolutePath());
            Path path = root.resolve(filename);
            if (!PluginLoader.isInsideDirectory(root, path)) {
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
