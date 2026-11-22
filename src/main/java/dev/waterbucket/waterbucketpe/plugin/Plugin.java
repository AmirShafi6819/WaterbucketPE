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
import dev.waterbucket.waterbucketpe.WaterbucketPE;
import dev.waterbucket.waterbucketpe.utils.config.Configuration;
import dev.waterbucket.waterbucketpe.utils.FileUtils;
import dev.waterbucket.waterbucketpe.utils.config.YamlConfig;
import dev.waterbucket.waterbucketpe.utils.exceptions.PluginChangeStateException;
import lombok.Getter;
import lombok.extern.log4j.Log4j2;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.config.AppenderRef;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.cloudburstmc.protocol.common.util.Preconditions;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Base plugin class all plugins must extend
 */
@Log4j2()
public abstract class Plugin {

    @Getter
    protected boolean enabled = false;
    @Getter
    private PluginYAML description;
    @Getter
    private ProxyServer proxy;
    @Getter
    private Logger logger;
    @Getter
    private File pluginFile;
    @Getter
    private File dataFolder;
    private File configFile;
    private Configuration config;
    private boolean initialized = false;

    public Plugin() {
    }

    protected final void init(PluginYAML description, ProxyServer proxy, File pluginFile) {
        Preconditions.checkArgument(!this.initialized, "Plugin has been already initialized!");
        this.initialized = true;
        this.description = description;
        this.proxy = proxy;
        this.logger = this.buildLogger();

        this.pluginFile = pluginFile;
        this.dataFolder = new File(proxy.getPluginPath() + "/" + description.getName().toLowerCase() + "/");
        if (!this.dataFolder.exists() && !this.dataFolder.mkdirs()) {
            // A silent mkdirs failure surfaced only later as failing config writes; make it
            // visible immediately.
            this.logger.warn("Could not create plugin data folder {}", this.dataFolder.getAbsolutePath());
        }
        this.configFile = new File(this.dataFolder, "config.yml");
    }

    private Logger buildLogger() {
        LoggerContext context = (LoggerContext) LogManager.getContext(false);
        org.apache.logging.log4j.core.config.Configuration config = context.getConfiguration();

        Level logLevel = WaterbucketPE.version().debug() ? Level.DEBUG : Level.INFO;

        AppenderRef[] appenderRefs = new AppenderRef[]{
                AppenderRef.createAppenderRef("File-Plugin", null, null),
                AppenderRef.createAppenderRef("Console-Plugin", logLevel, null)
        };

        // Primary logger
        LoggerConfig logger = LoggerConfig.createLogger(false, logLevel, this.getName(),
                "", appenderRefs, null, config, null);
        logger.addAppender(config.getAppender("File-Plugin"), null, null);
        logger.addAppender(config.getAppender("Console-Plugin"), logLevel, null);
        config.addLogger(this.getName(), logger);

        // Plugin class logger. getCanonicalName() is null for anonymous/hidden classes; a
        // plugin implemented as an anonymous subclass used to crash init with an NPE, so fall
        // back to the binary name.
        String classLoggerName = this.getClass().getCanonicalName() != null
                ? this.getClass().getCanonicalName() : this.getClass().getName();
        LoggerConfig clazzLogger = LoggerConfig.createLogger(false, logLevel, classLoggerName,
                "", appenderRefs, null, config, null);
        clazzLogger.getAppenders().keySet().forEach(clazzLogger::removeAppender);
        clazzLogger.addAppender(config.getAppender("File-Plugin"), null, null);
        clazzLogger.addAppender(config.getAppender("Console-Plugin"), logLevel, null);
        config.addLogger(classLoggerName, clazzLogger);

        context.updateLoggers();
        return LogManager.getLogger(this.getName());
    }

    /**
     * Called when the plugin is loaded into the server, but before it was enabled.
     * Can be used to load important information or to establish connections
     */
    public void onStartup() {
    }

    /**
     * Called when the base server startup is done and the plugins are getting enabled.
     * Also called whenever the plugin state changes to enabled
     */
    public abstract void onEnable();

    /**
     * Called on server shutdown, or when the plugin gets disabled, for example by another plugin or when an error occurred.
     * Also gets called when the plugin state changes to disabled
     */
    public void onDisable() {
    }

    /**
     * @param filename the file name to read
     * @return Returns a file from inside the plugin jar as an InputStream
     */
    public InputStream getResourceFile(String filename) {
        // The previous implementation opened a JarFile per call and never closed it (a file
        // descriptor leak per call), and getInputStream(null) threw a bare NullPointerException
        // when the entry did not exist. The returned stream must survive the JarFile close, so
        // the entry is fully read here; plugin resources are config-sized files.
        try (JarFile pluginJar = new JarFile(this.pluginFile)) {
            JarEntry entry = pluginJar.getJarEntry(filename);
            if (entry == null) {
                this.proxy.getLogger().warning("Resource {} was not found in plugin {}", filename, this.getName());
                return null;
            }
            try (InputStream stream = pluginJar.getInputStream(entry)) {
                return new java.io.ByteArrayInputStream(stream.readAllBytes());
            }
        } catch (IOException e) {
            this.proxy.getLogger().error("Can not get plugin resource!", e);
        }
        return null;
    }

    public boolean saveResource(String filename) {
        return this.saveResource(filename, false);
    }

    public boolean saveResource(String filename, boolean replace) {
        return this.saveResource(filename, filename, replace);
    }

    /**
     * Saves a resource from the plugin jar's resources to the plugin folder
     *
     * @param filename   the name of the file in the jar's resources
     * @param outputName the name the file should be saved as in the plugin folder
     * @param replace    whether the file should be replaced even if present already
     * @return returns false if an exception occurred, the file already exists and shouldn't be replaced, and when the file could
     * not be found in the jar
     * returns true if the file overwrite / copy was successful
     */
    public boolean saveResource(String filename, String outputName, boolean replace) {
        Preconditions.checkArgument(filename != null && !filename.trim().isEmpty(), "Filename can not be null!");
        Preconditions.checkArgument(outputName != null && !outputName.trim().isEmpty(), "Output name can not be null!");

        // The output name is plugin-supplied: confine it to the data folder so a '../' entry
        // cannot write outside the plugin directory (mirrors the Python resource confinement).
        java.nio.file.Path dataRoot = this.dataFolder.toPath();
        java.nio.file.Path target = dataRoot.resolve(outputName).normalize();
        if (!PluginLoader.isInsideDirectory(dataRoot, target)) {
            this.logger.warn("Plugin {} tried to save resource {} outside its data folder", this.getName(), outputName);
            return false;
        }

        File file = target.toFile();
        if (file.exists() && !replace) {
            return false;
        }

        try (InputStream resource = this.getResourceFile(filename)) {
            if (resource == null) {
                return false;
            }
            File outFolder = file.getParentFile();
            if (!outFolder.exists()) {
                outFolder.mkdirs();
            }
            FileUtils.writeFile(file, resource);
        } catch (IOException e) {
            this.proxy.getLogger().error("Can not save plugin file!", e);
            return false;
        }
        return true;
    }

    /**
     * Loads the config.yml from the plugin jar if not present in the data folder and loads it as a YamlConfig
     */
    public void loadConfig() {
        try {
            this.saveResource("config.yml");
            this.config = new YamlConfig(this.configFile);
        } catch (Exception e) {
            // Log the actual cause: a swallowed "Can not load plugin config!" with no exception
            // left broken plugin configs indistinguishable from missing ones, and getConfig()
            // returned null for a confusing NPE at the plugin call site.
            this.proxy.getLogger().error("Can not load plugin config!", e);
        }
    }

    /**
     * @return Returns the config.yml class, loads it if not loaded yet using loadConfig()
     */
    public Configuration getConfig() {
        if (this.config == null) {
            this.loadConfig();
        }
        return this.config;
    }

    /**
     * Changes the plugin's state
     *
     * @param enabled whether the plugin should be enabled or disabled
     * @throws PluginChangeStateException Thrown whenever an uncaught error occurred in onEnable() or onDisable() of a plugin
     */
    public void setEnabled(boolean enabled) throws PluginChangeStateException {
        if (this.enabled == enabled) {
            return;
        }
        this.enabled = enabled;
        try {
            if (enabled) {
                this.onEnable();
            } else {
                this.onDisable();
            }
        } catch (Exception e) {
            if (enabled) {
                // A failed enable must not leave the plugin stuck in enabled-but-dead state.
                // The manager no longer calls setEnabled(false) after a failed enable, so
                // onDisable() no longer runs against a half-initialized plugin either.
                this.enabled = false;
            }
            throw new PluginChangeStateException("Can not " + (enabled ? "enable" : "disable") + " plugin " + this.description.getName() + "!", e);
        }
    }

    public String getName() {
        return this.description.getName();
    }

}
