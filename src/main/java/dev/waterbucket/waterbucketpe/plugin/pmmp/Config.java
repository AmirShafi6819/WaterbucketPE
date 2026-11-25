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

package dev.waterbucket.waterbucketpe.plugin.pmmp;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The config/data file utility of the original server software, ported to the proxy: a
 * YAML (or JSON) file loaded into a nested map with dot-separated key access, save and
 * reload. Economy-style plugins keep their player data in one of these.
 * <p>
 * Supported key syntax: {@code "a"}, {@code "a.b"}, {@code "a.b.3"} (list index) - exactly the
 * nested access the original Config offers. Missing parents are created on {@link #set}.
 */
public class Config {

    /**
     * File types, mirroring the constants of the original utility.
     */
    public enum Type {
        YAML,
        JSON
    }

    private final Path file;
    private final Type type;
    private Map<String, Object> data = new LinkedHashMap<>();
    private volatile boolean changed;

    public Config(Path file) {
        this(file, Type.YAML);
    }

    public Config(Path file, Type type) {
        this.file = file;
        this.type = type;
        this.reload();
    }

    /**
     * Loads a default resource shipped inside the plugin jar into the file when the file does
     * not exist yet, then loads the file - the classic "save default config" flow.
     *
     * @return true when the default was written
     */
    public boolean loadDefault(InputStream defaultResource) {
        if (Files.exists(this.file) || defaultResource == null) {
            return false;
        }
        try {
            Files.createDirectories(this.file.getParent());
            Files.copy(defaultResource, this.file);
            this.reload();
            return true;
        } catch (IOException e) {
            throw new IllegalStateException("Could not write the default config to " + this.file, e);
        }
    }

    @SuppressWarnings("unchecked")
    public void reload() {
        try {
            if (!Files.exists(this.file)) {
                this.data = new LinkedHashMap<>();
                this.changed = false;
                return;
            }
            String content = Files.readString(this.file, StandardCharsets.UTF_8);
            if (content.isBlank()) {
                this.data = new LinkedHashMap<>();
                this.changed = false;
                return;
            }
            Object parsed = this.type == Type.JSON
                    ? new Gson().fromJson(content, Map.class)
                    : new Yaml().load(content);
            this.data = parsed instanceof Map ? (Map<String, Object>) parsed : new LinkedHashMap<>();
            this.changed = false;
        } catch (IOException | com.google.gson.JsonParseException | org.yaml.snakeyaml.error.YAMLException e) {
            throw new IllegalStateException("Could not parse " + this.file, e);
        }
    }

    /**
     * Reads a value by key (dot-separated nesting supported). Returns null when missing.
     */
    public Object get(String key) {
        return this.get(key, null);
    }

    /**
     * Reads a value with a fallback for the missing case.
     */
    @SuppressWarnings("unchecked")
    public <T> T get(String key, T defaultValue) {
        Object current = this.data;
        int start = 0;
        String path = key;
        while (current != null) {
            int dot = path.indexOf('.', start);
            String segment = dot == -1 ? path.substring(start) : path.substring(start, dot);
            if (!(current instanceof Map<?, ?> map)) {
                return defaultValue;
            }
            current = map.get(segment);
            if (current == null) {
                return defaultValue;
            }
            if (dot == -1) {
                return (T) current;
            }
            start = dot + 1;
        }
        return defaultValue;
    }

    public String getString(String key, String defaultValue) {
        Object value = this.get(key, defaultValue);
        return value == null ? defaultValue : String.valueOf(value);
    }

    public int getInt(String key, int defaultValue) {
        Object value = this.get(key);
        return value instanceof Number number ? number.intValue() : defaultValue;
    }

    public long getLong(String key, long defaultValue) {
        Object value = this.get(key);
        return value instanceof Number number ? number.longValue() : defaultValue;
    }

    public double getDouble(String key, double defaultValue) {
        Object value = this.get(key);
        return value instanceof Number number ? number.doubleValue() : defaultValue;
    }

    public boolean getBoolean(String key, boolean defaultValue) {
        Object value = this.get(key);
        return value instanceof Boolean b ? b : defaultValue;
    }

    /**
     * Whether a key exists (dot-separated nesting supported).
     */
    public boolean exists(String key) {
        return this.get(key) != null;
    }

    /**
     * Writes a value under the key, creating missing parent maps along the way.
     */
    @SuppressWarnings("unchecked")
    public void set(String key, Object value) {
        Map<String, Object> map = this.data;
        int start = 0;
        String path = key;
        while (true) {
            int dot = path.indexOf('.', start);
            String segment = dot == -1 ? path.substring(start) : path.substring(start, dot);
            if (dot == -1) {
                if (value == null) {
                    map.remove(segment);
                } else {
                    map.put(segment, value);
                }
                break;
            }
            Object child = map.get(segment);
            if (!(child instanceof Map)) {
                child = new LinkedHashMap<String, Object>();
                map.put(segment, (Map<String, Object>) child);
            }
            map = (Map<String, Object>) child;
            start = dot + 1;
        }
        this.changed = true;
    }

    /**
     * Removes a key; null-valued {@link #set} does the same.
     */
    public void remove(String key) {
        this.set(key, null);
    }

    public Map<String, Object> getAll() {
        return java.util.Collections.unmodifiableMap(this.data);
    }

    public void setAll(Map<String, Object> data) {
        this.data = data == null ? new LinkedHashMap<>() : data;
        this.changed = true;
    }

    public boolean hasChanged() {
        return this.changed;
    }

    /**
     * Writes the current data back to the file (only when something changed).
     *
     * @return true when a write happened
     */
    public synchronized boolean save() {
        if (!this.changed) {
            return false;
        }
        try {
            Files.createDirectories(this.file.getParent());
            String content = this.type == Type.JSON
                    ? new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(this.data)
                    : yaml().dump(this.data);
            Files.writeString(this.file, content, StandardCharsets.UTF_8);
            this.changed = false;
            return true;
        } catch (IOException e) {
            throw new IllegalStateException("Could not save " + this.file, e);
        }
    }

    private Yaml yaml() {
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setIndent(2);
        return new Yaml(options);
    }

    public Path getFile() {
        return this.file;
    }

    public Type getType() {
        return this.type;
    }
}
