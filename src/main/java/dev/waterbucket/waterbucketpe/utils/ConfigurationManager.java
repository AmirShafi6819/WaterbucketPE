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

package dev.waterbucket.waterbucketpe.utils;

import dev.waterbucket.waterbucketpe.ProxyServer;
import dev.waterbucket.waterbucketpe.network.nethernet.NetherNetProperties;
import dev.waterbucket.waterbucketpe.network.serverinfo.ServerInfo;
import dev.waterbucket.waterbucketpe.network.serverinfo.ServerInfoMap;
import dev.waterbucket.waterbucketpe.plugin.PluginClassLoader;
import dev.waterbucket.waterbucketpe.plugin.PluginManager;
import dev.waterbucket.waterbucketpe.utils.config.*;
import dev.waterbucket.waterbucketpe.utils.config.proxy.ProxyConfig;
import lombok.AllArgsConstructor;
import lombok.Getter;
import net.cubespace.Yamler.Config.InvalidConfigurationException;

import java.io.File;
import java.io.IOException;
import java.util.ServiceLoader.Provider;
import java.util.*;

@Getter
public class ConfigurationManager {

    private final ProxyServer proxy;
    // Swapped in from the reload path (scheduler thread) and read from every event loop and
    // scheduler task - volatile so readers never pair a new config object with stale fields.
    private volatile ProxyConfig proxyConfig;
    private volatile LangConfig langConfig;

    public ConfigurationManager(ProxyServer proxy) {
        this.proxy = proxy;
    }

    @Deprecated
    public static Configuration newConfig(File file, Type type) {
        return newConfig(file.toString(), type);
    }

    @Deprecated
    public static Configuration newConfig(String file, Type type) {
        return switch (type) {
            case YAML -> new YamlConfig(file);
            case JSON -> new JsonConfig(file);
            default -> null;
        };
    }

    public void loadProxyConfig() throws InvalidConfigurationException {
        File configFile = new File(this.proxy.getDataPath().toString() + "/config.yml");
        ProxyConfig config = new ProxyConfig(configFile);
        config.init();
        NetherNetProperties.applyOverrides(config.getNetherNetSettings().getSignaling());
        this.proxyConfig = config;
    }

    public void loadServerInfos(ServerInfoMap serverInfoMap) {
        for (ServerEntry entry : this.proxyConfig.getServerList().values()) {
            try {
                ServerInfo serverInfo = serverInfoMap.fromServerEntry(entry);
                serverInfo.setDisabled(entry.isDisabled());
                if (entry.isDisabled()) {
                    this.proxy.getLogger().info("Server '{}' starts marked offline (servers.{}.disabled: true) - "
                            + "joins are refused until /wbserveroffline {} off re-enables it",
                            entry.getServerName(), entry.getServerName(), entry.getServerName());
                }
                ServerInfo previous = serverInfoMap.putIfAbsent(entry.getServerName(), serverInfo);
                if (previous != null) {
                    this.proxy.getLogger().warning("Duplicate server definition for '" + entry.getServerName()
                            + "' (server names are case-insensitive); keeping the first entry and skipping this one");
                }
            } catch (Exception e) {
                this.proxy.getLogger().error("Failed to create ServerInfo from " + entry, e);
            }
        }
    }

    public void loadLanguage() {
        File langFile = new File(this.proxy.getDataPath().toString() + "/lang.ini");
        if (!langFile.exists()) {
            try {
                FileUtils.saveFromResources("lang.ini", langFile);
            } catch (IOException e) {
                this.proxy.getLogger().error("Can not save lang file!", e);
            }
        }
        this.langConfig = new LangConfig(langFile);
    }

    /**
     * Loads the operator message overrides (ingame-messages.yml) and plugs them into the
     * language lookup: keys defined there win over the built-in lang.ini defaults.
     */
    public void loadIngameMessages() {
        File messagesFile = new File(this.proxy.getDataPath().toString() + "/ingame-messages.yml");
        IngameMessages messages = new IngameMessages(messagesFile);
        this.langConfig.setOverrides(messages);
    }

    public <T> T loadServiceProvider(String providerName, Class<T> clazz) {
        return this.loadServiceProvider(providerName, clazz, null);
    }

    public <T> T loadServiceProvider(String providerName, Class<T> clazz, PluginManager pluginManager) {
        Collection<Provider<T>> providers = new HashSet<>();
        // Lookup using main class load
        ServiceLoader.load(clazz).stream().forEach(providers::add);
        // Lookup in plugin class loaders
        if (pluginManager != null) {
            for (PluginClassLoader loader : pluginManager.getPluginClassLoaders()) {
                ServiceLoader.load(clazz, loader).stream().forEach(providers::add);
            }
        }

        for (Provider<T> provider : providers) {
            if (provider.type().getSimpleName().equals(providerName)) {
                return provider.get();
            }
        }
        return null;
    }

    @AllArgsConstructor
    public enum Type {
        JSON(1),
        YAML(2),
        UNKNOWN(-1);

        @Getter
        private final int id;

        public static Type getTypeById(int id) {
            return Arrays.stream(Type.values()).filter(type -> type.getId() == id).findFirst().orElse(Type.UNKNOWN);
        }
    }
}
