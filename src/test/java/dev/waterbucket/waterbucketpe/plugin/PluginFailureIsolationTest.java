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

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Stage B runtime audit (Phase 3): failure isolation and resource ownership of the Java
 * plugin runtime.
 *
 * P3-2: the loader-local class cache is mutated from any thread that first resolves a class
 *       (including Netty event loops) with a non-thread-safe map.
 * P3-4: a plugin that fails during loading keeps its classloader registered, its jar handle
 *       open, and its classes in the global cache.
 * P3-18: a plugin compiled for a newer JVM (UnsupportedClassVersionError - an Error, not an
 *       Exception) escapes loadPluginJAR's catch(Exception) and crashes the whole boot.
 */
class PluginFailureIsolationTest {

    @TempDir
    Path tempDir;

    private ProxyServer proxy;
    private PluginManager manager;

    @BeforeEach
    void setUp() throws Exception {
        this.proxy = mock(ProxyServer.class);
        when(this.proxy.getPluginPath()).thenReturn(this.tempDir);
        when(this.proxy.getLogger()).thenReturn(mock(MainLogger.class));
        this.manager = new PluginManager(this.proxy);
    }

    private static String descriptor(String name, String main) {
        return "name: " + name + "\nversion: 1.0.0\nauthor: test\nmain: " + main + "\n";
    }

    private static void buildJar(Path target, Map<String, byte[]> resources) throws IOException {
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(target))) {
            for (Map.Entry<String, byte[]> entry : resources.entrySet()) {
                out.putNextEntry(new JarEntry(entry.getKey()));
                out.write(entry.getValue());
                out.closeEntry();
            }
        }
    }

    /** Compiles java sources in-memory to a directory and returns the produced class files. */
    private static Map<String, byte[]> compile(Map<String, String> sources, Path workDir) throws IOException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "JDK compiler must be available in the test JVM");
        Path srcDir = workDir.resolve("src");
        Path outDir = workDir.resolve("out");
        Files.createDirectories(srcDir);
        Files.createDirectories(outDir);
        for (Map.Entry<String, String> source : sources.entrySet()) {
            Path file = srcDir.resolve(source.getKey());
            Files.createDirectories(file.getParent());
            Files.writeString(file, source.getValue(), StandardCharsets.UTF_8);
        }
        List<String> args = new ArrayList<>(List.of("-d", outDir.toString()));
        try (var walk = Files.walk(srcDir)) {
            walk.filter(p -> p.toString().endsWith(".java")).forEach(p -> args.add(p.toString()));
        }
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        boolean ok = compiler.run(null, null, err, args.toArray(new String[0])) == 0;
        assertTrue(ok, "test sources must compile: " + err);
        Map<String, byte[]> classes = new java.util.LinkedHashMap<>();
        try (var walk = Files.walk(outDir)) {
            walk.filter(p -> p.toString().endsWith(".class")).forEach(p -> {
                try {
                    classes.put(outDir.relativize(p).toString().replace('\\', '/'), Files.readAllBytes(p));
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        }
        return classes;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> cachedClasses() throws Exception {
        var field = PluginManager.class.getDeclaredField("cachedClasses");
        field.setAccessible(true);
        return (Map<String, Object>) field.get(this.manager);
    }

    @SuppressWarnings("unchecked")
    private Map<String, PluginClassLoader> loaders() throws Exception {
        var field = PluginManager.class.getDeclaredField("pluginClassLoaders");
        field.setAccessible(true);
        return (Map<String, PluginClassLoader>) field.get(this.manager);
    }

    private PluginYAML yaml(String name, String main) {
        PluginYAML config = new PluginYAML();
        config.name = name;
        config.version = "1.0.0";
        config.author = "test";
        config.main = main;
        return config;
    }

    @Test
    void pluginWhoseMainClassFailsToConstructIsFullyReleased() throws Exception {
        // A plugin whose constructor throws used to keep its classloader registered, its jar
        // handle open, and its classes in the global cache - getClassFromCache kept serving
        // classes of a broken plugin to every other plugin.
        Map<String, byte[]> classes = this.compile(Map.of(
                "broken/BrokenPlugin.java", """
                        package broken;
                        public class BrokenPlugin extends dev.waterbucket.waterbucketpe.plugin.Plugin {
                            public BrokenPlugin() { throw new IllegalStateException("boom"); }
                            @Override public void onEnable() { }
                        }
                        """), this.tempDir.resolve("work-broken"));

        Path jar = this.tempDir.resolve("broken-plugin.jar");
        buildJar(jar, Map.of("waterbucket.yml",
                descriptor("Broken", "broken.BrokenPlugin").getBytes(StandardCharsets.UTF_8),
                "broken/BrokenPlugin.class", classes.get("broken/BrokenPlugin.class")));

        assertNull(this.manager.loadPlugin(this.yaml("Broken", "broken.BrokenPlugin"), jar),
                "the failing plugin must be dropped");
        assertNull(this.manager.getPluginByName("Broken"));

        // The loader and every class it defined must be released, not retained
        assertFalse(loaders().containsKey("Broken"),
                "the failed plugin's classloader must not stay registered");
        for (Map.Entry<String, Object> entry : cachedClasses().entrySet()) {
            Class<?> clazz = (Class<?>) entry.getValue();
            assertNotEquals("Broken", entry.getKey(), "no cached class of the failed plugin may survive");
            assertFalse(clazz.getName().startsWith("broken."), "no broken.* class may survive in the global cache");
        }
    }

    @Test
    void pluginCompiledForANewerJvmDoesNotCrashTheBoot() throws Exception {
        // loadPluginJAR caught Exception only; UnsupportedClassVersionError is an Error and
        // escaped straight through loadAllPlugins into boot, killing the whole proxy start.
        Map<String, byte[]> classes = this.compile(Map.of(
                "future/FuturePlugin.java", """
                        package future;
                        public class FuturePlugin extends dev.waterbucket.waterbucketpe.plugin.Plugin {
                            @Override public void onEnable() { }
                        }
                        """), this.tempDir.resolve("work-future"));

        byte[] raw = classes.get("future/FuturePlugin.class");
        // Class file major version: bytes 6-7. Patch 61 (Java 17) -> 100 (no released JVM).
        byte[] patched = raw.clone();
        patched[6] = 0x00;
        patched[7] = 0x64;
        assertNotEquals(raw[7], patched[7], "the major version must have been patched");

        Path jar = this.tempDir.resolve("future-plugin.jar");
        buildJar(jar, Map.of("waterbucket.yml",
                descriptor("Future", "future.FuturePlugin").getBytes(StandardCharsets.UTF_8),
                "future/FuturePlugin.class", patched));

        assertDoesNotThrow(() -> this.manager.loadPlugin(this.yaml("Future", "future.FuturePlugin"), jar),
                "an incompatible class file version must not crash the boot with an Error");
        assertNull(this.manager.getPluginByName("Future"), "the incompatible plugin must be dropped");
        assertDoesNotThrow(this.manager::loadAllPlugins, "the boot path must survive the incompatible plugin");
    }

    @Test
    void concurrentFirstLoadsShareClassesWithoutLinkageErrors() throws Exception {
        // The loader-local class map is written by every thread that first resolves a class;
        // concurrent first-loads of DIFFERENT classes can race its internal resize. The stress
        // must resolve every class exactly once with no duplicate-definition LinkageError.
        StringBuilder source = new StringBuilder("package stress;\n");
        for (int i = 0; i < 400; i++) {
            // package-private: several top-level classes share one source file
            source.append("class Gen").append(i).append(" { public int value() { return ").append(i).append("; } }\n");
        }
        Map<String, byte[]> classes = this.compile(Map.of("stress/GenBundle.java", source.toString()),
                this.tempDir.resolve("work-stress"));
        Map<String, byte[]> entries = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, byte[]> e : classes.entrySet()) {
            if (e.getKey().startsWith("stress/Gen")) {
                entries.put(e.getKey(), e.getValue());
            }
        }
        Path jar = this.tempDir.resolve("stress-plugin.jar");
        buildJar(jar, entries);

        // The stress exercises the classloader directly: it is created during the boot scan,
        // before any plugin code runs, and is then hit from every thread that first resolves
        // a class - including Netty event loops.
        PluginClassLoader loader = new PluginClassLoader(this.manager, this.getClass().getClassLoader(), jar.toFile());
        assertNotNull(loader);

        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        ConcurrentMap<String, Class<?>> seen = new ConcurrentHashMap<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        for (int t = 0; t < threads; t++) {
            final int offset = t;
            pool.execute(() -> {
                try {
                    start.await();
                    for (int i = offset; i < 400; i += threads) {
                        Class<?> clazz = loader.loadClass("stress.Gen" + i);
                        seen.putIfAbsent("stress.Gen" + i, clazz);
                        if (seen.get("stress.Gen" + i) != clazz) {
                            failure.compareAndSet(null, new IllegalStateException("two Class objects for one name"));
                        }
                    }
                } catch (Throwable t2) {
                    failure.compareAndSet(null, t2);
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(60, TimeUnit.SECONDS), "stress finished");
        pool.shutdownNow();
        assertNull(failure.get(), "no thread may observe a class-loading failure: " + failure.get());
        assertEquals(400, seen.size(), "every generated class must have resolved");
        var classesField = PluginClassLoader.class.getDeclaredField("classes");
        classesField.setAccessible(true);
        Map<?, ?> localCache = (Map<?, ?>) classesField.get(loader);
        assertEquals(400, localCache.size(), "the loader-local cache must hold every class exactly once");
    }
}
