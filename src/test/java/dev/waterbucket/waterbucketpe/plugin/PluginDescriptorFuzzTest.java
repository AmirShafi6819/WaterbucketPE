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
import dev.waterbucket.waterbucketpe.plugin.python.PythonPluginLoader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Phase 5 (§15): fuzzing the untrusted metadata boundaries. Descriptors are parsed for every
 * scanned jar and folder before any plugin code runs, so random, malformed, adversarial and
 * corrupted inputs must:
 *
 *  - never throw out of the parse (the result is either null or a valid descriptor),
 *  - never instantiate the class named by a global {@code !!} tag (the CVE-2022-1471 pattern),
 *  - never make {@code loadPlugin} crash on a corrupted jar file,
 *  - always produce descriptors that satisfy the documented invariants when non-null.
 *
 * Seeded and deterministic: every run uses the same corpus, so a failure is reproducible.
 */
class PluginDescriptorFuzzTest {

    /** Same probe class as PluginDescriptorParsingTest: instantiation is observable. */
    private static final String PROBE_FQCN =
            "dev.waterbucket.waterbucketpe.plugin.PluginDescriptorParsingTest$GadgetProbe";

    @TempDir
    Path tempDir;

    private ProxyServer proxy;
    private PluginManager manager;
    private PythonPluginLoader pythonLoader;

    @BeforeEach
    void setUp() throws Exception {
        PluginDescriptorParsingTest.GadgetProbe.INSTANCES.set(0);
        this.proxy = mock(ProxyServer.class);
        when(this.proxy.getPluginPath()).thenReturn(this.tempDir);
        when(this.proxy.getLogger()).thenReturn(mock(MainLogger.class));
        this.manager = new PluginManager(this.proxy);
        this.pythonLoader = new PythonPluginLoader(this.proxy);
    }

    // ---------- deterministic adversarial corpus ----------

    private static final String[] TOKENS = {
            "name", "version", "author", "main", "depends", "api-version", "load",
            "libraries", "unknown_key", "!!" + PROBE_FQCN, "!!java.lang.Runtime", "*a", "&a",
            "42", "1.5", "true", "null", "~", "''", "\"quoted\"", "with space", "UPPER_CASE",
            "[a, b]", "{a: b}", "!!!", "---", "...", "%YAML", "@&*![]{}#|>-\\", "\u00e9\u4e2d\u6587",
            "\t", "\r\n", "'", "\"", "\\\\", "../../escape", "main.py", "C:\\\\path",
    };

    private String fuzzDescriptor(Random random) {
        int lines = 2 + random.nextInt(8);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines; i++) {
            int field = random.nextInt(5);
            String key = new String[]{"name", "version", "author", "main", "depends"}[field];
            sb.append(key).append(": ");
            int kind = random.nextInt(10);
            switch (kind) {
                case 0 -> sb.append(TOKENS[random.nextInt(TOKENS.length)]);
                case 1 -> sb.append("[").append(TOKENS[random.nextInt(TOKENS.length)]).append("]");
                case 2 -> sb.append("{").append(TOKENS[random.nextInt(TOKENS.length)]).append(": ")
                        .append(TOKENS[random.nextInt(TOKENS.length)]).append("}");
                case 3 -> sb.append("\n  - ").append(TOKENS[random.nextInt(TOKENS.length)]);
                case 4 -> sb.append(random.nextInt(1_000_000));
                case 5 -> sb.append(random.nextDouble());
                case 6 -> {
                    int len = random.nextInt(4096);
                    StringBuilder big = new StringBuilder();
                    for (int c = 0; c < len; c++) {
                        big.append((char) ('a' + random.nextInt(26)));
                    }
                    sb.append(big);
                }
                case 7 -> sb.append("&a ").append(TOKENS[random.nextInt(TOKENS.length)])
                        .append("\nother: *a");
                case 8 -> {
                    int depth = 1 + random.nextInt(30);
                    sb.append("[");
                    sb.append("[".repeat(depth));
                    sb.append("]");
                    sb.append("]".repeat(depth));
                }
                default -> sb.append(TOKENS[random.nextInt(TOKENS.length)]).append(": ")
                        .append(TOKENS[random.nextInt(TOKENS.length)]);
            }
            sb.append('\n');
        }
        // occasional truncation of a valid descriptor
        if (random.nextInt(8) == 0) {
            String valid = "name: Fuzz\nversion: 1.0.0\nauthor: a\nmain: dev.x.Main\n";
            sb.setLength(0);
            sb.append(valid, 0, random.nextInt(valid.length()));
        }
        return sb.toString();
    }

    private byte[] fuzzJarBytes(Random random) {
        // Mostly-random jar payloads: corrupt zips, truncated jars, binary noise
        int len = 64 + random.nextInt(2048);
        byte[] bytes = new byte[len];
        random.nextBytes(bytes);
        if (random.nextBoolean() && len > 8) {
            bytes[0] = 'P';
            bytes[1] = 'K';
            bytes[2] = 3;
            bytes[3] = 4;
        }
        return bytes;
    }

    private void assertAcceptedDescriptorsAreValid(PluginYAML config, String context) {
        if (config == null) {
            return;
        }
        assertTrue(PluginLoader.isValidPluginName(config.getName()),
                context + ": accepted descriptor must carry a valid name, got '" + config.getName() + "'");
        assertNotNull(config.getMain(), context + ": accepted descriptor must carry a main");
        assertTrue(!config.getMain().isBlank(), context + ": accepted descriptor main must not be blank");
        if (config.getDepends() != null) {
            for (String depend : config.getDepends()) {
                assertNotNull(depend, context + ": depends entries must be scalars");
            }
        }
    }

    @Test
    void jarDescriptorFuzzingNeverThrowsOrInstantiatesGadgets() {
        Random random = new Random(20261122L);
        int accepted = 0;
        for (int i = 0; i < 1_500; i++) {
            String descriptor = this.fuzzDescriptor(random);
            Path jar = this.tempDir.resolve("fuzz-" + i + ".jar");
            try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
                out.putNextEntry(new JarEntry("waterbucket.yml"));
                out.write(descriptor.getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }

            PluginYAML config = this.manager.loadPluginDataForTest(jar.toFile());
            this.assertAcceptedDescriptorsAreValid(config, "case " + i);
            if (config != null) {
                accepted++;
            }
            assertEquals(0, PluginDescriptorParsingTest.GadgetProbe.INSTANCES.get(),
                    "case " + i + ": descriptor parsing must never instantiate !! gadget classes\n"
                            + "----- descriptor -----\n" + descriptor);
        }
        assertTrue(accepted > 0, "the corpus must also contain acceptable descriptors");
    }

    @Test
    void folderDescriptorFuzzingNeverThrowsOrInstantiatesGadgets() throws Exception {
        Random random = new Random(0x5742C0DE);
        for (int i = 0; i < 1_500; i++) {
            Path folder = this.tempDir.resolve("fuzz-folder-" + i);
            Files.createDirectories(folder);
            Files.writeString(folder.resolve("waterbucket.yml"),
                    this.fuzzDescriptor(random), StandardCharsets.UTF_8);

            PluginYAML config = this.pythonLoader.loadDescriptor(folder, null);
            this.assertAcceptedDescriptorsAreValid(config, "folder case " + i);
            assertEquals(0, PluginDescriptorParsingTest.GadgetProbe.INSTANCES.get(),
                    "folder case " + i + ": descriptor parsing must never instantiate !! gadget classes");
        }
    }

    @Test
    void corruptedJarFilesNeverCrashDiscoveryOrLoading() throws Exception {
        Random random = new Random(0xC0FFEE);
        List<byte[]> payloads = new ArrayList<>();
        for (int i = 0; i < 128; i++) {
            payloads.add(this.fuzzJarBytes(random));
        }
        // empty file and truncated valid descriptor jar
        payloads.add(new byte[0]);
        byte[] valid = new byte[0];
        try (var bos = new java.io.ByteArrayOutputStream();
             var out = new JarOutputStream(bos)) {
            out.putNextEntry(new JarEntry("waterbucket.yml"));
            out.write("name: Trunc\nversion: 1.0.0\nauthor: a\nmain: dev.x.Main\n".getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
            out.flush();
            valid = bos.toByteArray();
        }
        payloads.add(java.util.Arrays.copyOf(valid, valid.length / 2));

        AtomicInteger dropped = new AtomicInteger();
        for (int i = 0; i < payloads.size(); i++) {
            Path jar = this.tempDir.resolve("corrupt-" + i + ".jar");
            Files.write(jar, payloads.get(i));
            PluginYAML config = assertDoesNotThrowDescriptor(jar);
            if (config == null) {
                dropped.incrementAndGet();
            }
        }
        assertTrue(dropped.get() >= payloads.size() - 1,
                "corrupted jars must be rejected, only at most one valid jar may pass");
    }

    private PluginYAML assertDoesNotThrowDescriptor(Path jar) {
        try {
            return this.manager.loadPluginDataForTest(jar.toFile());
        } catch (Throwable t) {
            throw new AssertionError("descriptor parsing of a corrupted jar threw: " + jar.getFileName(), t);
        }
    }
}
