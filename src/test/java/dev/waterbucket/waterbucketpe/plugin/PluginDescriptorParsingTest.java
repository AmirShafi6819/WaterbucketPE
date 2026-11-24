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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Stage B/F audit (Phase 3): plugin descriptor parsing is a metadata trust boundary that runs
 * before any plugin code executes and for every scanned jar and folder.
 *
 * P3-5: SnakeYAML 1.32 with a global-tag-enabled constructor instantiates arbitrary classes
 *       named by {@code !!} tags in the descriptor (CVE-2022-1471 class).
 * P3-6: an unknown descriptor key (e.g. {@code api-version}) must not reject an otherwise
 *       valid plugin.
 */
class PluginDescriptorParsingTest {

    /** A harmless probe whose instantiation is observable, on the app classloader. */
    public static final class GadgetProbe {
        public static final AtomicInteger INSTANCES = new AtomicInteger();

        public GadgetProbe() {
            INSTANCES.incrementAndGet();
        }
    }

    @TempDir
    Path tempDir;

    private ProxyServer proxy;
    private PluginManager manager;

    @BeforeEach
    void setUp() throws Exception {
        GadgetProbe.INSTANCES.set(0);
        this.proxy = mock(ProxyServer.class);
        when(this.proxy.getPluginPath()).thenReturn(this.tempDir);
        when(this.proxy.getLogger()).thenReturn(mock(MainLogger.class));
        this.manager = new PluginManager(this.proxy);
    }

    private Path buildJar(Map<String, String> resources) throws IOException {
        Path jar = this.tempDir.resolve("probe-" + System.nanoTime() + ".jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            for (Map.Entry<String, String> entry : resources.entrySet()) {
                out.putNextEntry(new JarEntry(entry.getKey()));
                out.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
            }
        }
        return jar;
    }

    @Test
    void globalTagsInDescriptorsInstantiateNothing() throws Exception {
        // The gadget tag is evaluated while the descriptor is parsed, before any plugin code
        // runs, so a hostile plugin.yml must not be able to construct arbitrary classes.
        String malicious = """
                name: Gadget
                version: 1.0.0
                author: test
                main: dev.waterbucket.waterbucketpe.plugin.PluginDescriptorParsingTest$GadgetProbe
                depends: [!!dev.waterbucket.waterbucketpe.plugin.PluginDescriptorParsingTest$GadgetProbe {}]
                """;
        Path jar = this.buildJar(Map.of("waterbucket.yml", malicious));

        PluginYAML config = this.manager.loadPluginDataForTest(jar.toFile());
        assertEquals(0, GadgetProbe.INSTANCES.get(),
                "parsing a descriptor must not instantiate the class named by a !! tag");
        assertNull(config, "a descriptor with global tags must be rejected");
    }

    @Test
    void unknownDescriptorKeysDoNotRejectThePlugin() throws Exception {
        // Bukkit-style descriptors routinely carry extra keys (api-version, load, libraries);
        // the jar path treated them as fatal and skipped the whole plugin.
        String descriptor = """
                name: ExtraKeys
                version: 1.0.0
                author: test
                main: dev.waterbucket.waterbucketpe.plugin.PluginDescriptorParsingTest$GadgetProbe
                api-version: 1.0
                load: STARTUP
                description: has unknown keys
                """;
        Path jar = this.buildJar(Map.of("waterbucket.yml", descriptor));
        PluginYAML config = this.manager.loadPluginDataForTest(jar.toFile());
        assertNotNull(config, "an otherwise valid descriptor with unknown keys must load");
        assertEquals("ExtraKeys", config.getName());
        assertEquals("dev.waterbucket.waterbucketpe.plugin.PluginDescriptorParsingTest$GadgetProbe", config.getMain());
        assertEquals(0, GadgetProbe.INSTANCES.get(), "metadata parsing must not run plugin code");
    }

    @Test
    void wrongValueTypesAreRejectedWithThePluginSkipped() throws Exception {
        // The hardened parser must still validate the fields it does know about: a list where
        // a string belongs is a broken descriptor, not a loadable plugin.
        String descriptor = """
                name: 42
                version: 1.0.0
                author: test
                main: {broken: mapping}
                """;
        Path jar = this.buildJar(Map.of("waterbucket.yml", descriptor));
        assertNull(this.manager.loadPluginDataForTest(jar.toFile()),
                "a descriptor with wrong value types must be rejected");
    }

    @Test
    void descriptionFieldIsParsedAndOptional() throws Exception {
        String withDescription = """
                name: Described
                version: 1.0.0
                author: test
                description: A very real plugin summary
                main: dev.waterbucket.waterbucketpe.plugin.PluginDescriptorParsingTest$GadgetProbe
                """;
        Path described = this.buildJar(Map.of("waterbucket.yml", withDescription));
        PluginYAML config = this.manager.loadPluginDataForTest(described.toFile());
        assertNotNull(config, "a valid descriptor with a description must load");
        assertEquals("A very real plugin summary", config.getDescription());

        String withoutDescription = """
                name: Plain
                version: 1.0.0
                author: test
                main: dev.waterbucket.waterbucketpe.plugin.PluginDescriptorParsingTest$GadgetProbe
                """;
        Path plain = this.buildJar(Map.of("waterbucket.yml", withoutDescription));
        PluginYAML plainConfig = this.manager.loadPluginDataForTest(plain.toFile());
        assertNotNull(plainConfig);
        assertNull(plainConfig.getDescription(), "description stays optional");
    }

    @Test
    void pluginYmlFallbackStillWorksForDescription() throws Exception {
        // Plugins written for upstream WaterdogPE carry plugin.yml; the fork accepts both names
        String descriptor = """
                name: UpstreamStyle
                version: 2.0.0
                author: test
                description: shipped as plugin.yml
                main: dev.waterbucket.waterbucketpe.plugin.PluginDescriptorParsingTest$GadgetProbe
                """;
        Path jar = this.buildJar(Map.of("plugin.yml", descriptor));
        PluginYAML config = this.manager.loadPluginDataForTest(jar.toFile());
        assertNotNull(config, "a plugin.yml descriptor must load");
        assertEquals("shipped as plugin.yml", config.getDescription());
    }
}
