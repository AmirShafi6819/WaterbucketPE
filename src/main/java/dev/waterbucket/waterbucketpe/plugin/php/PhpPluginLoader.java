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
import lombok.extern.log4j.Log4j2;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Discovers and loads folder-based PHP plugins (Quercus, PHP-on-JVM).
 * <p>
 * A PHP plugin is a directory under the plugin path containing a {@code waterbucket.yml}
 * (or {@code plugin.yml}) descriptor:
 * <pre>
 * name: HelloPhp
 * version: 1.0.0
 * author: Someone
 * main: main.php
 * depends: []
 * </pre>
 * {@code main} must point at a {@code .php} file relative to the plugin folder. The loader only
 * supplements the jar and Python loaders; existing plugins keep working unchanged.
 */
@Log4j2
public class PhpPluginLoader {

    private final ProxyServer proxy;

    public PhpPluginLoader(ProxyServer proxy) {
        this.proxy = proxy;
    }

    public boolean isPhpPlugin(Path path) {
        if (!Files.isDirectory(path)) {
            return false;
        }
        PluginYAML descriptor = this.loadDescriptor(path);
        return descriptor != null && descriptor.getMain() != null && descriptor.getMain().endsWith(".php");
    }

    /**
     * Reads the plugin descriptor from a plugin folder. Returns null when absent, broken, or
     * when the declared plugin name is invalid (names become directory names and map keys).
     * <p>
     * Parsing goes through the hardened {@link PluginLoader#parseDescriptor} (no global YAML
     * tags, type-checked fields) so the jar, Python and folder boundaries behave identically.
     */
    public PluginYAML loadDescriptor(Path folder) {
        Path descriptor = folder.resolve("waterbucket.yml");
        if (!Files.isRegularFile(descriptor)) {
            descriptor = folder.resolve("plugin.yml");
        }
        if (!Files.isRegularFile(descriptor)) {
            return null;
        }
        try (InputStream stream = Files.newInputStream(descriptor)) {
            PluginYAML config = PluginLoader.parseDescriptor(stream,
                    "php plugin in " + folder.getFileName());
            if (config == null) {
                log.warn("Skipping PHP plugin in {}: broken or unsafe descriptor", folder.getFileName());
            }
            return config;
        } catch (Exception e) {
            log.error("Error while reading PHP plugin descriptor in {}", folder.getFileName(), e);
            return null;
        }
    }

    /**
     * Instantiates the plugin wrapper and binds its descriptor, proxy and plugin folder.
     */
    public Plugin loadPlugin(PluginYAML config, Path folder) {
        Path main = folder.resolve(config.getMain());
        if (!PluginLoader.isInsideDirectory(folder, main)) {
            log.error("PHP plugin {} declares main '{}' which escapes its plugin folder",
                    config.getName(), config.getMain());
            return null;
        }
        if (!Files.isRegularFile(main)) {
            log.error("PHP plugin {} declares main '{}' which does not exist in {}",
                    config.getName(), config.getMain(), folder.getFileName());
            return null;
        }

        PhpPlugin plugin = new PhpPlugin(this.proxy);
        plugin.initPhp(config, this.proxy, folder.toFile());
        return plugin;
    }
}
