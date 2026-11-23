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

package dev.waterbucket.waterbucketpe.utils.config;

import dev.waterbucket.waterbucketpe.logger.MainLogger;
import dev.waterbucket.waterbucketpe.utils.FileUtils;
import org.yaml.snakeyaml.Yaml;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The operator-facing message override file: {@code ingame-messages.yml}.
 * <p>
 * Every message the proxy shows a player (kick reasons, transfer errors, command
 * feedback, the shutdown notice) resolves from here first and falls back to the
 * built-in English defaults in {@code lang.ini} when the file has no entry. Values are
 * plain strings and support Minecraft formatting codes (§) as well as the {%0}, {%1}...
 * placeholders the defaults use, so an operator can reword and recolor everything in one
 * place without touching code.
 */
public class IngameMessages {

    private final File file;
    private final Object2ObjectOrderedMap messages = new Object2ObjectOrderedMap();

    public IngameMessages(File file) {
        this.file = file;
        this.load();
    }

    public void load() {
        this.messages.clear();
        try {
            if (!this.file.exists()) {
                FileUtils.saveFromResources("ingame-messages.yml", this.file);
                MainLogger.getLogger().info("Created default ingame-messages.yml - every player-visible message can be edited there");
            }
            String content = Files.readString(this.file.toPath(), StandardCharsets.UTF_8);
            Yaml yaml = new Yaml();
            Object parsed = yaml.load(content);
            if (!(parsed instanceof Map<?, ?> raw)) {
                return;
            }
            for (Map.Entry<?, ?> entry : raw.entrySet()) {
                if (entry.getKey() == null || entry.getValue() == null) {
                    continue;
                }
                String key = String.valueOf(entry.getKey()).trim();
                String value = String.valueOf(entry.getValue());
                if (!key.isEmpty() && !value.isEmpty()) {
                    this.messages.put(key, value);
                }
            }
        } catch (IOException e) {
            MainLogger.getLogger().error("Unable to load " + this.file.getName(), e);
        } catch (Exception e) {
            MainLogger.getLogger().error("ingame-messages.yml is not valid YAML; keeping the previous values", e);
        }
    }

    /**
     * The override for a key, or null when the file does not define one and the built-in
     * default should be used.
     */
    public String get(String key) {
        return this.messages.get(key);
    }

    public Map<String, String> view() {
        return new LinkedHashMap<>(this.messages);
    }

    /** Ordered string-to-string map so the file order survives round trips. */
    private static class Object2ObjectOrderedMap extends LinkedHashMap<String, String> {
    }
}
