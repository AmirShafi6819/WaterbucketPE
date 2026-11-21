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
import dev.waterbucket.waterbucketpe.plugin.PluginLoader;
import dev.waterbucket.waterbucketpe.plugin.PluginYAML;
import lombok.extern.log4j.Log4j2;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Discovers and loads folder-based Python plugins.
 * <p>
 * A Python plugin is a directory under the plugin path containing a {@code waterbucket.yml}
 * (or {@code plugin.yml}) descriptor:
 * <pre>
 * name: HelloPy
 * version: 1.0.0
 * author: Someone
 * main: main.py
 * depends: []
 * </pre>
 * {@code main} must point at a {@code .py} file relative to the plugin folder. The loader only
 * supplements the jar loader; existing Java plugins keep working unchanged.
 */
@Log4j2
public class PythonPluginLoader {

    private final ProxyServer proxy;

    public PythonPluginLoader(ProxyServer proxy) {
        this.proxy = proxy;
    }

    public boolean isPythonPlugin(Path path) {
        if (!Files.isDirectory(path)) {
            return false;
        }
        PluginYAML descriptor = this.loadDescriptor(path, null);
        return descriptor != null && descriptor.getMain() != null && descriptor.getMain().endsWith(".py");
    }

    /**
     * Reads the plugin descriptor from a plugin folder. Returns null when absent, broken, or
     * when the declared plugin name is invalid (names become directory names and map keys).
     */
    public PluginYAML loadDescriptor(Path folder, Yaml yaml) {
        Path descriptor = folder.resolve("waterbucket.yml");
        if (!Files.isRegularFile(descriptor)) {
            descriptor = folder.resolve("plugin.yml");
        }
        if (!Files.isRegularFile(descriptor)) {
            return null;
        }
        try (InputStream stream = Files.newInputStream(descriptor)) {
            Yaml parser = yaml != null ? yaml
                    : new Yaml(new org.yaml.snakeyaml.constructor.CustomClassLoaderConstructor(
                            PythonPluginLoader.class.getClassLoader()));
            PluginYAML config = parser.loadAs(stream, PluginYAML.class);
            if (config != null && !PluginLoader.isValidPluginName(config.getName())) {
                log.warn("Skipping Python plugin in {}: {}", folder.getFileName(),
                        config.getName() == null ? "descriptor declares no name"
                                : "invalid plugin name '" + config.getName() + "'");
                return null;
            }
            return config;
        } catch (Exception e) {
            log.error("Error while reading Python plugin descriptor in {}", folder.getFileName(), e);
            return null;
        }
    }

    /**
     * Instantiates the plugin wrapper and binds its descriptor, proxy and plugin folder.
     */
    public Plugin loadPlugin(PluginYAML config, Path folder) {
        Path main = folder.resolve(config.getMain());
        if (!PluginLoader.isInsideDirectory(folder, main)) {
            log.error("Python plugin {} declares main '{}' which escapes its plugin folder",
                    config.getName(), config.getMain());
            return null;
        }
        if (!Files.isRegularFile(main)) {
            log.error("Python plugin {} declares main '{}' which does not exist in {}",
                    config.getName(), config.getMain(), folder.getFileName());
            return null;
        }

        PythonPlugin plugin = new PythonPlugin(this.proxy);
        plugin.initPython(config, this.proxy, folder.toFile());
        return plugin;
    }
}
