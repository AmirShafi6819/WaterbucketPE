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

package dev.waterbucket.waterbucketpe.plugin.compat;

import dev.waterbucket.waterbucketpe.plugin.Plugin;
import dev.waterbucket.waterbucketpe.plugin.PluginManager;
import lombok.Getter;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * The compatibility plugin manager facade: lookup, enabled state and the loaded plugin list of
 * every language (Java, Python, PHP) through the live proxy manager.
 */
@Getter
public class PluginManagerCompat {

    private final PluginManager handle;

    PluginManagerCompat(PluginManager handle) {
        this.handle = handle;
    }

    /**
     * The plugin with the given name - exact match first, then case-insensitive, matching the
     * lookup behavior of the original software.
     */
    public Plugin getPlugin(String name) {
        if (name == null || name.isEmpty()) {
            return null;
        }
        Plugin plugin = this.handle.getPluginByName(name);
        if (plugin != null) {
            return plugin;
        }
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        for (Plugin candidate : this.handle.getPlugins()) {
            if (candidate.getName().toLowerCase(java.util.Locale.ROOT).equals(lower)) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * Every loaded plugin, any language.
     */
    public Collection<Plugin> getPlugins() {
        return this.handle.getPlugins();
    }

    public boolean isPluginEnabled(String name) {
        Plugin plugin = this.getPlugin(name);
        return plugin != null && plugin.isEnabled();
    }

    /**
     * The names of every loaded plugin, for status lines.
     */
    public List<String> getPluginNames() {
        List<String> names = new ArrayList<>();
        for (Plugin plugin : this.handle.getPlugins()) {
            names.add(plugin.getName());
        }
        return names;
    }
}
