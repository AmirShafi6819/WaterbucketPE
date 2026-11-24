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

package dev.waterbucket.waterbucketpe.plugin;

import dev.waterbucket.waterbucketpe.ProxyServer;
import dev.waterbucket.waterbucketpe.logger.MainLogger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Covers the Velocity-style {@code @PluginDescriptor} descriptor path: an annotated main class
 * replaces waterbucket.yml, YAML descriptors win when both exist, invalid ids are rejected,
 * the constant-pool marker scan separates candidates from everything else, and the whole flow
 * integrates with the regular plugin load.
 */
class PluginDescriptorAnnotationTest {

    @TempDir
    Path tempDir;

    private ProxyServer proxy;
    private PluginManager manager;

    private void newManager(Path pluginFolder) {
        this.proxy = mock(ProxyServer.class);
        when(this.proxy.getPluginPath()).thenReturn(pluginFolder);
        when(this.proxy.getLogger()).thenReturn(mock(MainLogger.class));
        this.manager = new PluginManager(this.proxy);
    }

    private byte[] classFile(Class<?> clazz) throws Exception {
        String resource = clazz.getName().replace('.', '/') + ".class";
        try (InputStream stream = clazz.getClassLoader().getResourceAsStream(resource)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = stream.read(buffer)) > 0) {
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        }
    }

    private void jarEntry(JarOutputStream jar, String name, byte[] data) throws Exception {
        jar.putNextEntry(new JarEntry(name));
        jar.write(data);
        jar.closeEntry();
    }

    @Test
    void markerScanSeparatesAnnotatedClassesFromPlainClasses() throws Exception {
        byte[] annotated = this.classFile(AnnotatedDummyPlugin.class);
        byte[] plain = this.classFile(PluginDescriptorAnnotationTest.class);

        assertTrue(PluginLoader.containsDescriptorMarker(annotated), "annotated class carries the marker");
        assertFalse(PluginLoader.containsDescriptorMarker(plain), "plain class does not carry the marker");
        assertFalse(PluginLoader.containsDescriptorMarker(null));
        assertFalse(PluginLoader.containsDescriptorMarker(new byte[4]));
    }

    @Test
    void annotatedJarLoadsThroughTheNormalPluginFlow() throws Exception {
        Path pluginFolder = this.tempDir.resolve("plugins");
        Files.createDirectories(pluginFolder);

        Path jar = pluginFolder.resolve("annotation-dummy.jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            this.jarEntry(out, "dev/waterbucket/waterbucketpe/plugin/AnnotatedDummyPlugin.class",
                    this.classFile(AnnotatedDummyPlugin.class));
        }

        this.newManager(pluginFolder);
        this.manager.loadAllPlugins();

        Plugin plugin = this.manager.getPluginByName("Annotation Dummy");
        assertTrue(plugin instanceof AnnotatedDummyPlugin, "annotation descriptor plugs into the normal load flow");

        PluginYAML description = plugin.getDescription();
        // name() wins over id; the id is only the fallback when no display name is set
        assertEquals("Annotation Dummy", description.getName());
        assertEquals("9.9.9", description.getVersion());
        assertEquals("Alice, Bob", description.getAuthor());
        assertEquals("annotation descriptor fixture", description.getDescription());
        assertEquals(List.of("dep-a", "dep-b"), description.getDepends());
        assertEquals("dev.waterbucket.waterbucketpe.plugin.AnnotatedDummyPlugin", description.getMain());
    }

    @Test
    void yamlDescriptorWinsOverTheAnnotation() throws Exception {
        Path pluginFolder = this.tempDir.resolve("plugins-yaml-wins");
        Files.createDirectories(pluginFolder);

        Path jar = pluginFolder.resolve("both-descriptors.jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            this.jarEntry(out, "waterbucket.yml", String.join("\n",
                    "name: yaml-plugin",
                    "version: 2.0.0",
                    "main: dev.waterbucket.waterbucketpe.plugin.AnnotatedDummyPlugin"
            ).getBytes());
            this.jarEntry(out, "dev/waterbucket/waterbucketpe/plugin/AnnotatedDummyPlugin.class",
                    this.classFile(AnnotatedDummyPlugin.class));
        }

        this.newManager(pluginFolder);
        this.manager.loadAllPlugins();

        // The YAML identity wins; the annotation is ignored entirely
        assertNull(this.manager.getPluginByName("Annotation Dummy"), "the annotation identity is ignored when a YAML descriptor exists");
        assertTrue(this.manager.getPluginByName("yaml-plugin") instanceof AnnotatedDummyPlugin);
    }

    @Test
    void invalidAnnotationIdIsRejected() throws Exception {
        Path pluginFolder = this.tempDir.resolve("plugins-invalid");
        Files.createDirectories(pluginFolder);

        Path jar = pluginFolder.resolve("invalid-id.jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            this.jarEntry(out, "dev/waterbucket/waterbucketpe/plugin/AnnotatedInvalidIdPlugin.class",
                    this.classFile(AnnotatedInvalidIdPlugin.class));
        }

        this.newManager(pluginFolder);
        this.manager.loadAllPlugins();

        assertNull(this.manager.getPluginByName("../evil-dummy"), "path traversal id must never load");
        assertNull(this.manager.getPluginByName("evil-dummy"));
        assertEquals(0, this.manager.getPlugins().size());
    }

    @Test
    void jarWithoutAnyDescriptorLoadsNothing() throws Exception {
        Path pluginFolder = this.tempDir.resolve("plugins-empty");
        Files.createDirectories(pluginFolder);

        Path jar = pluginFolder.resolve("no-descriptor.jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            this.jarEntry(out, "dev/waterbucket/waterbucketpe/plugin/PluginDescriptorAnnotationTest.class",
                    this.classFile(PluginDescriptorAnnotationTest.class));
        }

        this.newManager(pluginFolder);
        this.manager.loadAllPlugins();

        assertEquals(0, this.manager.getPlugins().size(), "a jar without descriptor or annotation is skipped");
    }

    @Test
    void missingDependenciesStillGateAnAnnotatedPlugin() throws Exception {
        Path pluginFolder = this.tempDir.resolve("plugins-deps");
        Files.createDirectories(pluginFolder);

        // The dummy depends on dep-a/dep-b, neither of which exists
        Path jar = pluginFolder.resolve("annotation-dummy-deps.jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            this.jarEntry(out, "dev/waterbucket/waterbucketpe/plugin/AnnotatedDummyPlugin.class",
                    this.classFile(AnnotatedDummyPlugin.class));
        }

        this.newManager(pluginFolder);
        this.manager.loadAllPlugins();
        this.manager.enableAllPlugins();

        Plugin plugin = this.manager.getPluginByName("Annotation Dummy");
        assertTrue(plugin instanceof AnnotatedDummyPlugin, "the jar still loads (map registration)");
        assertFalse(plugin.isEnabled(), "missing dependencies gate annotated plugins exactly like YAML ones");
    }
}
