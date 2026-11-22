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
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.CustomClassLoaderConstructor;
import org.yaml.snakeyaml.representer.Representer;

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
        Representer representer = new Representer();
        representer.getPropertyUtils().setSkipMissingProperties(true);
        yamlLoader = new Yaml(new CustomClassLoaderConstructor(PluginManager.class.getClassLoader()), representer);
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

    private final List<Pair<PluginYAML, Path>> pluginsToLoad = new ObjectArrayList<>();
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
        return this.pluginLoader.loadPluginData(pluginFile, yamlLoader);
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
            this.pluginClassLoaders.put(config.getName(), classLoader);
            this.pluginsToLoad.add(ObjectObjectImmutablePair.of(config, path));
            this.proxy.getLogger().debug("Loaded class loader from {}", path.getFileName());
        }
        return classLoader;
    }

    public void loadAllPlugins() {
        // Iterate a snapshot: a failed load releases its class loader, so re-reaching the
        // loadPlugin re-load path (registerClassLoader) appends to pluginsToLoad - iterating
        // the live list consumed its own appends and never terminated.
        List<Pair<PluginYAML, Path>> toLoad = new ObjectArrayList<>(this.pluginsToLoad);
        this.pluginsToLoad.clear();
        for (Pair<PluginYAML, Path> pair : toLoad) {
            this.loadPlugin(pair.key(), pair.value());
        }
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
     * Releases everything a failed plugin left behind: unregisters its class loader, evicts
     * the classes it defined from the global cache, and closes the loader (which releases the
     * open jar handle). Python plugins have no loader; the call is a no-op for them.
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
        try {
            loader.close();
        } catch (IOException e) {
            this.proxy.getLogger().debug("Could not close class loader of failed plugin {}", pluginName, e);
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
            Plugin plugin = order.get(i);
            this.proxy.getLogger().info("Disabling plugin " + plugin.getName() + "!");
            try {
                plugin.setEnabled(false);
            } catch (PluginChangeStateException e) {
                this.proxy.getLogger().error(e.getMessage(), e.getCause());
            }
        }
        this.enabledOrder.clear();
    }

    public Class<?> getClassFromCache(String className) {
        Class<?> clazz = this.cachedClasses.get(className);
        if (clazz != null) {
            return clazz;
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
        return null;
    }

    protected void cacheClass(String className, Class<?> clazz) {
        this.cachedClasses.putIfAbsent(className, clazz);
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
