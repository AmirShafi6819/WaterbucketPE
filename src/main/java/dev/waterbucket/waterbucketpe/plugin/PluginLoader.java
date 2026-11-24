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

import dev.waterbucket.waterbucketpe.plugin.annotation.PluginDescriptor;
import lombok.extern.log4j.Log4j2;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.File;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

@Log4j2
public class PluginLoader {

    private final PluginManager pluginManager;

    public PluginLoader(PluginManager pluginManager) {
        this.pluginManager = pluginManager;
    }

    protected static boolean isJarFile(Path file) {
        return file.getFileName().toString().endsWith(".jar");
    }

    /**
     * Plugin names become directory names under plugins/ (data folder) and map keys, so a name
     * containing path separators or {@code ..} would escape the plugin directory. Descriptors
     * that declare such a name are rejected instead of creating/reading outside plugins/.
     */
    public static boolean isValidPluginName(String name) {
        if (name == null || name.isBlank() || name.length() > 64) {
            return false;
        }
        return name.matches("[A-Za-z0-9_][A-Za-z0-9._ -]*");
    }

    /**
     * Resolves {@code candidate} inside {@code root} and rejects anything that escapes it.
     */
    public static boolean isInsideDirectory(Path root, Path candidate) {
        return candidate.normalize().startsWith(root.normalize());
    }

    protected PluginClassLoader loadClassLoader(PluginYAML pluginConfig, File pluginJar) {
        try {
            return new PluginClassLoader(this.pluginManager, this.getClass().getClassLoader(), pluginJar);
        } catch (MalformedURLException e) {
            log.error("Error while creating class loader(plugin={})", pluginConfig.getName());
        }
        return null;
    }

    protected Plugin loadPluginJAR(PluginYAML pluginConfig, File pluginJar, PluginClassLoader loader) {
        try {
            Class<?> mainClass = loader.loadClass(pluginConfig.getMain());
            if (!Plugin.class.isAssignableFrom(mainClass)) {
                return null;
            }

            Class<? extends Plugin> castedMain = mainClass.asSubclass(Plugin.class);
            Plugin plugin = castedMain.getDeclaredConstructor().newInstance();
            plugin.init(pluginConfig, this.pluginManager.getProxy(), pluginJar);
            return plugin;
        } catch (Throwable t) {
            // Throwable, not Exception: an UnsupportedClassVersionError (plugin compiled for a
            // newer JVM) or LinkageError is an Error and used to escape straight through
            // loadAllPlugins into boot, killing the whole proxy start over one bad jar. A
            // plugin failure drops the plugin, never the proxy.
            log.error("Error while loading plugin main class(main={}, plugin={})", pluginConfig.getMain(), pluginConfig.getName(), t);
        }
        return null;
    }

    protected PluginYAML loadPluginData(File file, Yaml yaml) {
        try (JarFile pluginJar = new JarFile(file)) {
            JarEntry configEntry = pluginJar.getJarEntry("waterbucket.yml");
            if (configEntry == null) {
                configEntry = pluginJar.getJarEntry("plugin.yml");
            }

            if (configEntry == null) {
                // Not an error by itself: the caller falls back to the @PluginDescriptor
                // annotation scan before deciding the jar is unloadable.
                log.debug("Jar file {} doesnt contain a waterbucket.yml or plugin.yml", file.getName());
                return null;
            }

            try (InputStream fileStream = pluginJar.getInputStream(configEntry)) {
                return parseDescriptor(fileStream, file.getName());
            }
        } catch (Exception e) {
            log.error("Can not load plugin files in {}", file.getPath(), e);
        }
        return null;
    }

    /**
     * Parses a plugin descriptor ({@code waterbucket.yml} / {@code plugin.yml}) from a stream.
     * <p>
     * Hardened metadata boundary: descriptors are parsed for every scanned jar and folder
     * <em>before any plugin code runs</em>, so the parser must not honor SnakeYAML global tags
     * ({@code !!some.Class} would instantiate arbitrary classpath classes - the CVE-2022-1471
     * pattern). Parsing uses {@link SafeConstructor} and maps the five known fields manually.
     * <p>
     * Behavior contract kept from the previous bean parser: scalar values for string fields are
     * stringified (unquoted {@code version: 1.0} keeps working), unknown keys are ignored, and
     * a missing or invalid {@code name}/{@code main} rejects the descriptor.
     *
     * @return the parsed descriptor, or null when the descriptor is absent/broken/unsafe
     */
    public static PluginYAML parseDescriptor(InputStream stream, String source) {
        try {
            Object root = new Yaml(new SafeConstructor(new LoaderOptions())).load(stream);
            if (!(root instanceof Map)) {
                log.warn("Descriptor of {} is not a mapping", source);
                return null;
            }
            Map<?, ?> fields = (Map<?, ?>) root;

            PluginYAML config = new PluginYAML();
            config.name = requiredString(fields.get("name"), "name", source);
            config.version = scalarString(fields.get("version"), "version", source);
            config.author = scalarString(fields.get("author"), "author", source);
            config.main = requiredString(fields.get("main"), "main", source);
            config.description = scalarString(fields.get("description"), "description", source);
            config.depends = dependsList(fields.get("depends"), source);

            if (config.name == null || config.main == null) {
                log.warn("Invalid plugin.yml for {}: main and/or name property missing", source);
                return null;
            }
            if (!isValidPluginName(config.name)) {
                log.warn("Invalid plugin name '{}' in {}: names may only contain letters, digits, spaces, dots, dashes and underscores",
                        config.name, source);
                return null;
            }
            return config;
        } catch (Exception e) {
            // Includes SafeConstructor rejections of global tags: a crafted descriptor must be
            // skipped, never able to instantiate classpath classes while being parsed.
            log.warn("Cannot parse plugin descriptor of {}: {}", source, e.toString());
            return null;
        }
    }

    /** Scalars are stringified (old bean behavior); mappings/sequences reject the descriptor. */
    private static String scalarString(Object value, String field, String source) {
        if (value == null) {
            return null;
        }
        if (value instanceof String || value instanceof Number || value instanceof Boolean || value instanceof Character) {
            return String.valueOf(value);
        }
        throw new IllegalArgumentException("Descriptor of " + source + ": field '" + field + "' must be a scalar");
    }

    private static String requiredString(Object value, String field, String source) {
        String parsed = scalarString(value, field, source);
        if (parsed != null && parsed.isBlank()) {
            throw new IllegalArgumentException("Descriptor of " + source + ": field '" + field + "' is blank");
        }
        return parsed;
    }

    private static List<String> dependsList(Object value, String source) {
        if (value == null) {
            return null;
        }
        if (!(value instanceof List)) {
            throw new IllegalArgumentException("Descriptor of " + source + ": field 'depends' must be a list");
        }
        List<String> depends = new java.util.ArrayList<>();
        for (Object element : (List<?>) value) {
            String parsed = scalarString(element, "depends", source);
            if (parsed == null) {
                throw new IllegalArgumentException("Descriptor of " + source + ": 'depends' contains an empty entry");
            }
            depends.add(parsed);
        }
        return depends;
    }

    /**
     * Constant pool of a class that actually uses {@link PluginDescriptor} contains the
     * annotation's binary name; a cheap byte scan over the class file filters candidates so
     * only jars that mention the annotation ever pay for class loading during the boot scan.
     */
    private static final byte[] DESCRIPTOR_MARKER =
            "dev/waterbucket/waterbucketpe/plugin/annotation/PluginDescriptor".getBytes(StandardCharsets.US_ASCII);

    static boolean containsDescriptorMarker(byte[] data) {
        if (data == null || data.length < DESCRIPTOR_MARKER.length) {
            return false;
        }
        outer:
        for (int i = 0; i <= data.length - DESCRIPTOR_MARKER.length; i++) {
            for (int j = 0; j < DESCRIPTOR_MARKER.length; j++) {
                if (data[i + j] != DESCRIPTOR_MARKER[j]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }

    /**
     * Velocity-style descriptor fallback: when a jar carries no YAML descriptor, look for a
     * class annotated with {@link PluginDescriptor} and build the descriptor from it.
     * <p>
     * Safety contract, same spirit as the YAML parser: the scan runs for every jar before any
     * plugin code executes, so candidate classes are loaded with {@code initialize=false} -
     * no plugin static initializer runs while merely reading metadata - and the scanning
     * class loader is a throwaway parent-delegating loader closed right after the scan.
     *
     * @return the descriptor built from the first valid annotated class, or null when the jar
     * declares none
     */
    public PluginYAML findAnnotatedDescriptor(File pluginJar) {
        try (JarFile jar = new JarFile(pluginJar)) {
            List<String> candidates = new ArrayList<>();
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                String entryName = entry.getName();
                if (entry.isDirectory() || !entryName.endsWith(".class")) {
                    continue;
                }
                if (containsDescriptorMarker(jar.getInputStream(entry).readAllBytes())) {
                    candidates.add(entryName);
                }
            }
            if (candidates.isEmpty()) {
                return null;
            }

            try (URLClassLoader scanner = new URLClassLoader(new URL[]{pluginJar.toURI().toURL()},
                    PluginLoader.class.getClassLoader())) {
                PluginYAML found = null;
                for (String entryName : candidates) {
                    String className = entryName.substring(0, entryName.length() - ".class".length()).replace('/', '.');
                    Class<?> clazz;
                    try {
                        clazz = Class.forName(className, false, scanner);
                    } catch (Throwable t) {
                        // ClassNotFoundException for nested/duplicate-marker classes and
                        // LinkageError for exotic class files: both are just "not the plugin
                        // main class", so scan continues instead of failing the jar.
                        log.debug("Skipping descriptor candidate {} in {}: {}", entryName, pluginJar.getName(), t.toString());
                        continue;
                    }
                    PluginDescriptor annotation = clazz.getAnnotation(PluginDescriptor.class);
                    if (annotation == null) {
                        // Mentions the annotation without using it (imported elsewhere).
                        continue;
                    }
                    PluginYAML config = descriptorFromAnnotation(annotation, className, pluginJar.getName());
                    if (config == null) {
                        continue;
                    }
                    if (found != null) {
                        log.warn("Jar {} declares more than one @PluginDescriptor class; using {} and ignoring {}",
                                pluginJar.getName(), found.getMain(), className);
                        break;
                    }
                    found = config;
                }
                return found;
            }
        } catch (Exception e) {
            log.warn("Cannot scan {} for an @PluginDescriptor annotated plugin class", pluginJar.getPath(), e);
            return null;
        }
    }

    private static PluginYAML descriptorFromAnnotation(PluginDescriptor annotation, String className, String source) {
        String id = annotation.id();
        if (id == null || id.isBlank()) {
            log.warn("@PluginDescriptor of {} declares a blank id - ignoring", source);
            return null;
        }
        String name = annotation.name() == null || annotation.name().isBlank() ? id : annotation.name();
        if (!isValidPluginName(name)) {
            log.warn("Invalid plugin id '{}' from @PluginDescriptor of {}: names may only contain letters, digits, spaces, dots, dashes and underscores",
                    name, source);
            return null;
        }
        PluginYAML config = new PluginYAML();
        config.name = name;
        config.version = annotation.version() == null || annotation.version().isBlank() ? "1.0.0" : annotation.version();
        config.author = annotation.authors() == null || annotation.authors().length == 0 ? null : String.join(", ", annotation.authors());
        config.description = annotation.description() == null || annotation.description().isBlank() ? null : annotation.description();
        config.depends = annotation.dependencies() == null || annotation.dependencies().length == 0
                ? null : new ArrayList<>(List.of(annotation.dependencies()));
        config.main = className;
        return config;
    }
}
