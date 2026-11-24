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

package dev.waterbucket.waterbucketpe.plugin;

import dev.waterbucket.waterbucketpe.ProxyServer;
import dev.waterbucket.waterbucketpe.plugin.python.PythonPluginLoader;
import dev.waterbucket.waterbucketpe.utils.exceptions.PluginChangeStateException;
import it.unimi.dsi.fastutil.Pair;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectObjectImmutablePair;
import lombok.Getter;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;

public class PluginManager {

    public static final Yaml yamlLoader;
    static {
        // Public for API compatibility; descriptor parsing itself goes through the hardened
        // PluginLoader.parseDescriptor. SafeConstructor: this instance must never be able to
        // instantiate classpath classes from global YAML tags either.
        yamlLoader = new Yaml(new SafeConstructor(new LoaderOptions()));
    }

    @Getter
    private final ProxyServer proxy;
    private final PluginLoader pluginLoader;
    private final PythonPluginLoader pythonLoader;

    /*
     * Both registry maps are written by the boot scan and by the public runtime APIs
     * loadPlugin/enablePlugin (a plugin may call them from its own threads or scheduled
     * tasks), and read concurrently by getClassFromCache on the Netty event loops and by the
     * panel PluginsCommand, so both must be concurrent. The previous fastutil array maps
     * could corrupt entries under a runtime load while another thread iterated.
     */
    protected final ConcurrentMap<String, PluginClassLoader> pluginClassLoaders = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Plugin> pluginMap = new ConcurrentHashMap<>();
    /*
     * Written by PluginClassLoader.findClass from whichever thread first loads a class -
     * including Netty event loops - and read by getClassFromCache everywhere, so the class
     * cache must be concurrent. The loader map above is written only during the boot scan.
     */
    private final ConcurrentMap<String, Class<?>> cachedClasses = new ConcurrentHashMap<>();
    /*
     * Negative cache for plugin class lookups: a missing class used to cost one full jar scan
     * across every plugin loader plus exception construction PER lookup - measured at 194 µs
     * per miss with 32 plugins loaded. The set is cleared whenever a new loader is registered,
     * so a plugin loaded at runtime can still provide previously missing classes.
     */
    private final Set<String> missingClasses = ConcurrentHashMap.newKeySet();

    private final List<Pair<PluginYAML, Path>> pluginsToLoad = new ObjectArrayList<>();
    /**
     * Flipped at the end of {@link #loadAllPlugins()}. After boot nothing consumes
     * {@code pluginsToLoad}, so a runtime {@code loadPlugin} call that registers a class loader
     * appended its pair to the list forever - a small per-call leak of dead bookkeeping.
     */
    private volatile boolean bootScanComplete = false;
    /*
     * Appended from enablePlugin, which is public runtime API callable from plugin threads;
     * a plain list lost entries under concurrent enables and produced a wrong reverse-disable
     * order at shutdown.
     */
    private final List<Plugin> enabledOrder = new CopyOnWriteArrayList<>();

    public PluginManager(ProxyServer proxy) {
        this.proxy = proxy;
        this.pluginLoader = new PluginLoader(this);
        this.pythonLoader = new PythonPluginLoader(proxy);
        try {
            this.loadPluginsInside(this.proxy.getPluginPath());
        } catch (IOException e) {
            this.proxy.getLogger().error("Error while filtering plugin files", e);
        }
    }

    private void loadPluginsInside(Path folderPath) throws IOException {
        Comparator<PluginYAML> comparator = (o1, o2) -> {
            if (o2.getName().equals(o1.getName())) {
                return 0;
            }
            if (o2.getDepends() == null) {
                return 1;
            }
            return o2.getDepends().contains(o1.getName()) ? -1 : 1;
        };

        Map<PluginYAML, Path> plugins = new TreeMap<>(comparator);
        try (Stream<Path> stream = Files.walk(folderPath)){
            stream.filter(Files::isRegularFile).filter(PluginLoader::isJarFile).forEach(jarPath -> {
                PluginYAML config = this.loadPluginConfig(jarPath);
                if (config != null) {
                    plugins.put(config, jarPath);
                }
            });
        }
        plugins.forEach(this::registerClassLoader);

        // Folder-based Python plugins load alongside jar plugins; existing jar plugins are untouched
        try (Stream<Path> stream = Files.walk(folderPath, 2)) {
            stream.filter(path -> !path.equals(folderPath)).filter(this.pythonLoader::isPythonPlugin).forEach(dir -> {
                PluginYAML config = this.pythonLoader.loadDescriptor(dir, yamlLoader);
                if (config == null) {
                    return;
                }
                if (this.getPluginByName(config.getName()) != null) {
                    this.proxy.getLogger().warning("Plugin is already loaded: {}", config.getName());
                    return;
                }
                this.pluginsToLoad.add(ObjectObjectImmutablePair.of(config, dir));
            });
        } catch (IOException e) {
            this.proxy.getLogger().error("Error while scanning for Python plugins", e);
        }
    }

    private PluginYAML loadPluginConfig(Path path) {
        if (!Files.isRegularFile(path) || !PluginLoader.isJarFile(path)) {
            this.proxy.getLogger().warning("Cannot load plugin: Provided file is no jar file: " + path.getFileName());
            return null;
        }

        File pluginFile = path.toFile();
        if (!pluginFile.exists()) {
            return null;
        }
        PluginYAML config = this.pluginLoader.loadPluginData(pluginFile, yamlLoader);
        if (config == null) {
            // Velocity-style fallback: a jar without waterbucket.yml/plugin.yml may declare
            // its descriptor through the @PluginDescriptor annotation on its main class.
            config = this.pluginLoader.findAnnotatedDescriptor(pluginFile);
            if (config == null) {
                this.proxy.getLogger().warning("Jar file {} doesnt contain a waterbucket.yml, plugin.yml or @PluginDescriptor class!",
                        pluginFile.getName());
            }
        }
        return config;
    }

    /**
     * Package-private accessor for tests and diagnostics: parse a jar's descriptor without
     * running the full load flow.
     */
    PluginYAML loadPluginDataForTest(File file) {
        return this.pluginLoader.loadPluginData(file, yamlLoader);
    }

    private PluginClassLoader registerClassLoader(PluginYAML config, Path path) {
        if (this.getPluginByName(config.getName()) != null) {
            this.proxy.getLogger().warning("Plugin is already loaded: {}", config.getName());
            return null;
        }
        // Two jars declaring the same plugin name used to both reach loadAllPlugins (the name
        // check above sees an empty map during the scan); the loser kept a registered but
        // never-loaded classloader whose classes could shadow the winner via getClassFromCache.
        if (this.pluginClassLoaders.containsKey(config.getName())) {
            this.proxy.getLogger().warning("Duplicate plugin name {}: {} will be skipped", config.getName(), path.getFileName());
            return null;
        }

        PluginClassLoader classLoader = this.pluginLoader.loadClassLoader(config, path.toFile());
        if (classLoader != null) {
            // putIfAbsent closes the check-then-act window: two runtime loadPlugin calls for
            // the same name could both create loaders, the loser leaked (jar handle open).
            PluginClassLoader existing = this.pluginClassLoaders.putIfAbsent(config.getName(), classLoader);
            if (existing != null) {
                // Deliberately not closed (see releaseFailedPlugin): the loser becomes
                // unreachable and the GC releases its jar handle.
                this.proxy.getLogger().warning("Duplicate plugin name {}: {} will be skipped", config.getName(), path.getFileName());
                return null;
            }
            // A new loader may provide classes that were missing before: forget the negatives
            this.missingClasses.clear();
            classLoader.markRegisteredWithManager();
            if (!this.bootScanComplete) {
                this.pluginsToLoad.add(ObjectObjectImmutablePair.of(config, path));
            }
            this.proxy.getLogger().debug("Loaded class loader from {}", path.getFileName());
        }
        return classLoader;
    }

    public void loadAllPlugins() {
        // Iterate a snapshot: a failed load releases its class loader, so re-reaching the
        // loadPlugin re-load path (registerClassLoader) appends to pluginsToLoad - iterating
        // the live list consumed its own appends and never terminated. One extra drain pass
        // lets a load that registered a genuinely new loader mid-loop still be enabled, then
        // the list is closed (bootScanComplete) so runtime registrations stop accumulating.
        List<Pair<PluginYAML, Path>> toLoad = new ObjectArrayList<>(this.pluginsToLoad);
        this.pluginsToLoad.clear();
        for (Pair<PluginYAML, Path> pair : toLoad) {
            this.loadPlugin(pair.key(), pair.value());
        }
        List<Pair<PluginYAML, Path>> midLoopRegistrations = new ObjectArrayList<>(this.pluginsToLoad);
        this.pluginsToLoad.clear();
        for (Pair<PluginYAML, Path> pair : midLoopRegistrations) {
            this.loadPlugin(pair.key(), pair.value());
        }
        this.bootScanComplete = true;
    }

    public Plugin loadPlugin(PluginYAML config, Path path) {
        File pluginFile = path.toFile();
        if (this.getPluginByName(config.getName()) != null) {
            this.proxy.getLogger().warning("Plugin is already loaded: {}", config.getName());
            return null;
        }

        Plugin plugin;
        if (config.getMain() != null && config.getMain().endsWith(".py")) {
            // Folder-based Python plugin: no class loader, the script is evaluated on enable
            plugin = this.pythonLoader.loadPlugin(config, path);
        } else {
            PluginClassLoader classLoader = this.pluginClassLoaders.get(config.getName());
            if (classLoader == null) {
                classLoader = this.registerClassLoader(config, path);
            }
            if (classLoader == null) {
                return null;
            }
            plugin = this.pluginLoader.loadPluginJAR(config, pluginFile, classLoader);
        }

        if (plugin == null) {
            // The loader (if any) was registered before the failure; without releasing it the
            // failed plugin kept its jar handle open, its loader registered and its classes
            // served from the global cache to every other plugin.
            this.releaseFailedPlugin(config.getName(), this.pluginClassLoaders.get(config.getName()));
            return null;
        }

        try {
            plugin.onStartup();
        } catch (Throwable t) {
            // Throwable: same reasoning as loadPluginJAR - a plugin lifecycle failure is
            // contained to that plugin, never allowed to abort the boot.
            this.proxy.getLogger().error("Failed to load plugin {}!", config.getName(), t);
            this.releaseFailedPlugin(config.getName(), this.pluginClassLoaders.get(config.getName()));
            return null;
        }

        this.proxy.getLogger().info("Loaded plugin {} successfully! (version={}, author={})", config.getName(), config.getVersion(), config.getAuthor());
        this.pluginMap.put(config.getName(), plugin);
        return plugin;
    }

    /**
     * Releases everything a failed plugin left behind: unregisters its class loader and evicts
     * the classes it defined from the global cache. The loader itself is intentionally NOT
     * closed: URLClassLoader.close() closes the JVM-wide JarFileFactory-cached JarFile, which
     * is SHARED between loaders opened over the same file URL (reachable through the public
     * loadPlugin API with a re-named descriptor over an already-registered jar) - closing one
     * loader then broke every other loader over that jar with "zip file closed". Once the
     * loader is unregistered and its classes evicted, it becomes unreachable and the GC closes
     * the handle, which reaches the same end state without the shared-jar hazard.
     * Python plugins have no loader; the call is a no-op for them.
     */
    private void releaseFailedPlugin(String pluginName, PluginClassLoader loader) {
        if (loader == null) {
            return;
        }
        this.pluginClassLoaders.remove(pluginName, loader);
        for (Map.Entry<String, Class<?>> entry : this.cachedClasses.entrySet()) {
            if (entry.getValue().getClassLoader() == loader) {
                this.cachedClasses.remove(entry.getKey(), entry.getValue());
            }
        }
    }

    public void enableAllPlugins() {
        LinkedList<Plugin> failed = new LinkedList<>();

        for (Plugin plugin : this.pluginMap.values()) {
            if (!this.enablePlugin(plugin, null)) {
                failed.add(plugin);
            }
        }

        if (failed.isEmpty()) {
            return;
        }

        StringBuilder builder = new StringBuilder("§cFailed to load plugins: §e");
        while (failed.peek() != null) {
            Plugin plugin = failed.poll();
            builder.append(plugin.getName());
            if (failed.peek() != null) {
                builder.append(", ");
            }
        }
        this.proxy.getLogger().warning(builder.toString());
    }

    /**
     * Enables a plugin, enabling its dependencies first. Safe to call at runtime: the whole
     * operation is serialized on the manager, so two concurrent enable chains can not
     * interleave (which used to either reject a healthy dependency with a bogus "circular
     * dependency" warning or double-enable a plugin that was mid-enable).
     */
    public synchronized boolean enablePlugin(Plugin plugin, String parent) {
        // The chain set is local to this enable call: cycle detection must not leak into
        // concurrently executing chains (the previous shared HashSet did exactly that).
        return this.enablePluginChain(plugin, parent, new HashSet<>());
    }

    private boolean enablePluginChain(Plugin plugin, String parent, Set<String> chain) {
        if (plugin.isEnabled()) return true;
        String pluginName = plugin.getName();

        if (plugin.getDescription().getDepends() != null) {
            for (String depend : plugin.getDescription().getDepends()) {
                if (depend.equals(parent)) {
                    this.proxy.getLogger().warning("§cCan not enable plugin " + pluginName + " circular dependency " + parent + "!");
                    return false;
                }

                Plugin dependPlugin = this.getPluginByName(depend);
                if (dependPlugin == null) {
                    this.proxy.getLogger().warning("§cCan not enable plugin " + pluginName + " missing dependency " + depend + "!");
                    return false;
                }

                if (!dependPlugin.isEnabled()) {
                    // A plugin already on the current enable chain means a cycle of any length
                    if (!chain.add(depend)) {
                        this.proxy.getLogger().warning("§cCan not enable plugin " + pluginName + " circular dependency " + depend + "!");
                        return false;
                    }
                    try {
                        if (!this.enablePluginChain(dependPlugin, pluginName, chain)) {
                            return false;
                        }
                    } finally {
                        chain.remove(depend);
                    }
                }
            }
        }

        chain.add(pluginName);
        try {
            plugin.setEnabled(true);
        } catch (PluginChangeStateException e) {
            this.proxy.getLogger().error(e.getMessage(), e.getCause());
            return false;
        } finally {
            chain.remove(pluginName);
        }
        this.enabledOrder.add(plugin);
        return true;
    }

    public synchronized void disableAllPlugins() {
        // Disable in reverse of enable order so dependents shut down before their dependencies
        List<Plugin> order = new ObjectArrayList<>(this.enabledOrder);
        for (Plugin plugin : this.pluginMap.values()) {
            if (plugin.isEnabled() && !order.contains(plugin)) {
                order.add(plugin); // enabled outside enablePlugin; disable last
            }
        }

        for (int i = order.size() - 1; i >= 0; i--) {
            this.doDisablePlugin(order.get(i));
        }
        this.enabledOrder.clear();
    }

    /**
     * Disables a single plugin at runtime, disabling every enabled dependent first so no
     * plugin keeps running against an already-disabled dependency. Cycle-safe: a depends
     * loop between plugins can not recurse forever. Safe to call from plugin code; the
     * whole walk runs serialized with the other enable/disable operations.
     *
     * @param plugin the plugin to disable, null is ignored
     */
    public synchronized void disablePlugin(Plugin plugin) {
        if (plugin == null) {
            return;
        }
        Set<String> visited = new HashSet<>();
        Deque<Plugin> stack = new ArrayDeque<>();
        stack.push(plugin);
        while (!stack.isEmpty()) {
            Plugin current = stack.peek();
            if (!current.isEnabled()) {
                stack.pop();
                continue;
            }
            // A name already on the visited set means a depends cycle; force-disable the
            // top of the cycle so the walk still terminates.
            if (!visited.add(current.getName())) {
                stack.pop();
                this.doDisablePlugin(current);
                continue;
            }

            boolean pushed = false;
            for (Plugin dependent : this.pluginMap.values()) {
                List<String> depends = dependent.getDescription().getDepends();
                if (dependent.isEnabled() && depends != null && depends.contains(current.getName())
                        && !visited.contains(dependent.getName())) {
                    stack.push(dependent);
                    pushed = true;
                }
            }
            if (!pushed) {
                // No (unvisited) dependents left: current shuts down first.
                stack.pop();
                this.doDisablePlugin(current);
            }
        }
    }

    private void doDisablePlugin(Plugin plugin) {
        this.proxy.getLogger().info("Disabling plugin " + plugin.getName() + "!");
        try {
            plugin.setEnabled(false);
        } catch (PluginChangeStateException e) {
            this.proxy.getLogger().error(e.getMessage(), e.getCause());
        }
        this.enabledOrder.remove(plugin);
    }

    public Class<?> getClassFromCache(String className) {
        Class<?> clazz = this.cachedClasses.get(className);
        if (clazz != null) {
            return clazz;
        }
        // One full scan per missing name is enough; the outcome is the same (null), only the
        // repeated jar-walk and exception construction are saved.
        if (this.missingClasses.contains(className)) {
            return null;
        }

        for (PluginClassLoader loader : this.pluginClassLoaders.values()) {
            try {
                if ((clazz = loader.findClass(className, false)) != null) {
                    return clazz;
                }
            } catch (ClassNotFoundException e) {
                //ignore
            }
        }
        this.missingClasses.add(className);
        return null;
    }

    protected void cacheClass(String className, Class<?> clazz) {
        this.cachedClasses.putIfAbsent(className, clazz);
        this.missingClasses.remove(className);
    }

    /**
     * Whether a previous lookup already scanned every plugin loader without finding the class.
     * Package-private accessor for {@link PluginClassLoader} and tests.
     */
    boolean isKnownMissingClass(String className) {
        return this.missingClasses.contains(className);
    }

    public Map<String, Plugin> getPluginMap() {
        return Collections.unmodifiableMap(this.pluginMap);
    }

    public Collection<Plugin> getPlugins() {
        return Collections.unmodifiableCollection(this.pluginMap.values());
    }

    public Collection<PluginClassLoader> getPluginClassLoaders() {
        return Collections.unmodifiableCollection(this.pluginClassLoaders.values());
    }

    public Plugin getPluginByName(String pluginName) {
        return this.pluginMap.getOrDefault(pluginName, null);
    }

}
