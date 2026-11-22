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

package dev.waterbucket.waterbucketpe.utils.config;

import dev.waterbucket.waterbucketpe.logger.MainLogger;
import dev.waterbucket.waterbucketpe.utils.FileUtils;
import dev.waterbucket.waterbucketpe.utils.types.TextContainer;
import dev.waterbucket.waterbucketpe.utils.types.TranslationContainer;
import it.unimi.dsi.fastutil.objects.Object2ObjectMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;

import java.io.File;
import java.io.IOException;

public class LangConfig {

    protected final Object2ObjectMap<String, String> transactionMap = new Object2ObjectOpenHashMap<>();
    private final File file;

    public LangConfig(File file) {
        this.file = file;
        this.load();
    }

    public void load() {
        // Reload must not keep keys that were removed from the file since the last load.
        this.transactionMap.clear();
        try {
            String content = FileUtils.readFile(this.file);
            for (String line : content.split("\n")) {
                line = line.trim();
                if (line.equals("") || line.charAt(0) == '#') {
                    continue;
                }
                String[] t = line.split("=");
                if (t.length < 2) {
                    continue;
                }
                // Trim the key: "key = value" used to register a key with a trailing space that
                // no lookup could ever match.
                String key = t[0].trim();
                StringBuilder value = new StringBuilder();
                for (int i = 1; i < t.length - 1; i++) {
                    value.append(t[i]).append("=");
                }
                value.append(t[t.length - 1]);
                if (value.toString().equals("")) {
                    continue;
                }
                this.transactionMap.put(key, value.toString());
            }

        } catch (IOException e) {
            MainLogger.getLogger().error("Unable to load Config " + this.file.toString(), e);
        }
    }

    public String translateString(String key, String... args) {
        String string = this.getTransaction(key);
        if (string == null) {
            // Language files written for WaterdogPE use the legacy "waterdog." key prefix;
            // fall back to it so existing language files keep working on this fork.
            String legacy = LEGACY_PREFIX_REPLACER.apply(key);
            if (legacy != null) {
                string = this.getTransaction(legacy);
            }
        }
        if (string == null) return key;

        if (args != null) {
            for (int i = 0; i < args.length; i++) {
                string = string.replace("{%" + i + "}", String.valueOf(args[i]));
            }
        }
        return string;
    }

    private static final String CURRENT_PREFIX = "waterbucket.";
    private static final String LEGACY_PREFIX = "waterdog.";

    /**
     * Maps "waterbucket.xxx" keys to their legacy "waterdog.xxx" equivalent, or null when the
     * key does not carry the fork's prefix. Never allocates for keys that cannot match.
     */
    private static final java.util.function.Function<String, String> LEGACY_PREFIX_REPLACER = key -> {
        if (key.length() > LEGACY_PREFIX.length() && key.startsWith(CURRENT_PREFIX)) {
            return LEGACY_PREFIX + key.substring(CURRENT_PREFIX.length());
        }
        return null;
    };

    public String translateContainer(TextContainer textContainer) {
        if (!(textContainer instanceof TranslationContainer)) {
            return textContainer.getMessage();
        }
        return this.translateString(textContainer.getMessage(), ((TranslationContainer) textContainer).getParams());
    }

    public String getTransaction(String key) {
        return this.transactionMap.get(key);
    }

    public Object2ObjectMap<String, String> getTransactionMap() {
        return this.transactionMap;
    }
}
