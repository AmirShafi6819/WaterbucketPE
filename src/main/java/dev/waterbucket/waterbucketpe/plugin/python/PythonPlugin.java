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

    private final ProxyServer proxy;
    private volatile Context context;
    private volatile Value module;

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
                    .build();

            Value bindings = this.context.getBindings("python");
            bindings.putMember("plugin", this);
            bindings.putMember("proxy", this.proxy);
            bindings.putMember("logger", this.getLogger());
            this.context.eval("python", "import sys as _wb_sys\n_wb_sys.path.insert(0, r'" +
                    this.getPluginFile().getAbsolutePath().replace("'", "\\'") + "')");

            this.module = this.context.eval("python", Files.readString(
                    Path.of(this.getPluginFile().getAbsolutePath(), this.getDescription().getMain())));
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
        this.closeContext();
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
     *
     * @param eventClass Java event class, obtained e.g. via {@code java.type("...")}
     * @param handler    Python function receiving the event object
     */
    public <T extends dev.waterbucket.waterbucketpe.event.Event> void subscribe(Class<T> eventClass, Consumer<? super T> handler) {
        this.proxy.getEventManager().subscribe(eventClass, event -> {
            try {
                if (event.isCompletable()) {
                    CompletableFuture<Void> done = new CompletableFuture<>();
                    event.addCompletableFuture(done);
                    try {
                        handler.accept(event);
                        done.complete(null);
                    } catch (Throwable t) {
                        done.completeExceptionally(t);
                        throw t;
                    }
                } else {
                    handler.accept(event);
                }
            } catch (Throwable t) {
                this.getLogger().error("Python plugin {} threw in an event handler", this.getName(), t);
            }
        });
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
        return this.proxy.getCommandMap().registerCommand(name, command);
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
        // Python plugins are folders, not jars: resources live next to the main script
        try {
            Path path = Path.of(this.getPluginFile().getAbsolutePath(), filename);
            if (Files.exists(path)) {
                return Files.newInputStream(path);
            }
        } catch (Exception e) {
            this.proxy.getLogger().error("Can not get plugin resource!", e);
        }
        return null;
    }
}
