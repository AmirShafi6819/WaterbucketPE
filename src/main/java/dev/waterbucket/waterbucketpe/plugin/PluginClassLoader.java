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

import java.io.File;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Simple class loader which holds classes of plugins.
 * It allows plugins to access each other.
 */
public class PluginClassLoader extends URLClassLoader {

    private final PluginManager pluginManager;
    /*
     * The manager-wide negative-lookup cache records names missing from every REGISTERED
     * loader; a loader created outside registerClassLoader (public constructor) was never
     * scanned for that verdict, so it must not trust it. Production loaders are always
     * registered right after construction.
     */
    private volatile boolean registeredWithManager;

    /*
     * findClass runs on whichever thread first resolves a class - including Netty event
     * loops - so this cache must be concurrent: the previous fastutil map could lose entries
     * when two threads first-loaded different classes through a concurrent resize.
     */
    private final ConcurrentMap<String, Class<?>> classes = new ConcurrentHashMap<>();

    public PluginClassLoader(PluginManager pluginManager, ClassLoader parent, File file) throws MalformedURLException {
        super(new URL[]{file.toURI().toURL()}, parent);
        this.pluginManager = pluginManager;
    }

    /**
     * Marks this loader as part of the engine registry (called by the manager); from then on
     * the negative-lookup cache may shortcut lookups.
     */
    void markRegisteredWithManager() {
        this.registeredWithManager = true;
    }

    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException {
        return this.findClass(name, true);
    }

    protected Class<?> findClass(String name, boolean checkGlobal) throws ClassNotFoundException {
        if (name.startsWith("dev.waterbucket.waterbucketpe.")) { // Proxy classes should be known
            throw new ClassNotFoundException(name);
        }
        // A name already known to be missing from every registered plugin jar fails
        // immediately: the previous per-lookup jar walk across all loaders (plus exception
        // construction) was measured at ~194 µs per miss. Same outcome, no repeated scan.
        if (this.registeredWithManager && this.pluginManager.isKnownMissingClass(name)) {
            throw new ClassNotFoundException(name);
        }

        Class<?> result = this.classes.get(name);
        if (result != null) {
            return result;
        }

        // Guard against a lost cache entry racing a concurrent first-load: this loader has
        // already defined the class under the per-name loadClass lock, and re-entering
        // super.findClass would defineClass it a second time and throw LinkageError.
        result = this.findLoadedClass(name);
        if (result != null) {
            this.classes.put(name, result);
            return result;
        }

        if (checkGlobal) {
            result = this.pluginManager.getClassFromCache(name);
        }

        if (result == null && (result = super.findClass(name)) != null) {
            this.pluginManager.cacheClass(name, result);
        }
        if (result != null) {
            this.classes.put(name, result);
        }
        return result;
    }

}
